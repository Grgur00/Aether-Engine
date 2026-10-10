# Training Cache Daemon Lifecycle Functions

[Function index](FUNCTION-INDEX.md) | [Cache operations](TRAINING-CACHE-FUNCTIONS.md) | [Protocol and tracing](TRAINING-PROTOCOL-FUNCTIONS.md)

This reference covers every explicit constructor and function in the TCP, Unix
and TLS training-cache daemons. They are local wrappers around the same Java cache
and request handler, not the general Java client/RPC/Raft service. Persistent ML
experiments can reuse one daemon process; that lifecycle is chosen by orchestration,
not automatically enforced by these listener classes.

## Shared Architecture

| Layer | Ownership and behavior |
| --- | --- |
| Daemon instance | Owns cache, listener, worker executor, request semaphore and protocol counters |
| Accept task | Accepts connections and submits one serve task per connection |
| Connection task | Owns its socket/channel; optionally handshakes TLS; delegates streams to `TrainingCacheProtocol.serve` |
| Protocol loop | Sequentially reads, decodes, dispatches and responds on a connection; multiple connections may operate concurrently |
| Cache owner | Handles immutable publication, integrity, accounting, segments and database lifetime |

Each daemon uses 128 request permits shared across its connections. The protocol
tries to acquire a permit **after** allocating/reading a frame and decoding keys
and values. This limits admitted dispatches, not accepted connection count, TLS
handshakes, idle connections, frame allocation, or total request bytes in memory.
The default semaphore is nonfair; saturation returns an error rather than waiting.

Accepted sockets are not retained in a shutdown registry. Worker interruption
does not by itself establish that every blocked connection has finished before
cache closure, and the close methods do not join workers. No listener installs
per-connection read/idle timeouts or per-namespace authorization in these files.

## Loopback TCP Daemon

Source: [TrainingCacheDaemon.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheDaemon.java).

### Constructors and Accessors

| Function | Behavior |
| --- | --- |
| `TrainingCacheDaemon(directory, port, maximumBytes)` | Delegates with RECOVERABLE durability. |
| `TrainingCacheDaemon(directory, port, maximumBytes, durability)` | Requires directory, opens cache, binds `ServerSocket` with backlog 128 to JVM-selected loopback, creates a cached platform-thread pool and submits accept task. |
| `port()` | Returns listener's actual local port; port 0 selects an ephemeral port. |
| `protocolMetrics()` | Returns the protocol counter snapshot, not cache payload counters. |

The public `DEFAULT_PORT` is 0, not the Python client's default port or the
general RPC registry's port. The caller must use the selected listening address
and returned port. This constructor exposes durability but not storage policy;
cache open defaults to AUTO.

The cache is opened before socket binding. A later bind/initialization failure
has no constructor cleanup wrapper closing earlier resources. The listener is
loopback-only, not a configurable bind-host consumer. Legacy VERSION/GET/PUT/status
and frame constants declared in this class do not implement request dispatch;
the shared protocol class is the authoritative handler.

### acceptLoop and serve

Private `acceptLoop()` continues until the server is closed. Each accepted socket
is submitted to the same cached executor. An accept `IOException` during normal
shutdown is ignored; while still open it is wrapped in `IllegalStateException`.
The submitted task's future is not retained, so a failed accept task is not
automatically surfaced through `main` or restarted.

Private `serve(socket)` owns the socket with try-with-resources, enables TCP_NODELAY,
then calls the shared protocol with streams/cache/permits/counters. It silently
catches `Exception`; there is no per-connection error log here. This does not catch
all `Error` subclasses. Stream lifetime is also closed by the protocol wrapper.

### main and close

`main(arguments)` requires one to four arguments:

```text
<directory> [port] [maximumBytes] [durability]
```

It parses numbers, defaults port to 0 and maximum to 10 GiB, parses the durability
with exact enum `valueOf`, constructs a daemon, prints its actual port, then calls
`Thread.currentThread().join()` to wait indefinitely. It does not poll a shutdown
file or expose a wire STOP operation. Interruption/normal unwinding executes the
try-with-resources close; abrupt process termination is a different boundary.

`close()` closes listener, calls `workers.shutdownNow()`, then closes cache in that
order. It has no `finally` structure ensuring all cleanup stages run after a prior
failure, no explicit idempotence state, and no await-termination barrier.

## Unix-Domain Daemon

Source: [TrainingCacheUnixDaemon.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheUnixDaemon.java).

| Function | Behavior |
| --- | --- |
| `TrainingCacheUnixDaemon(directory, socketPath, maximumBytes)` | Converts path to absolute normalized form, deletes anything already at that path, opens default RECOVERABLE/AUTO cache, opens UNIX server channel, binds and submits accept task. |
| `socketPath()` | Returns the normalized bound path. |
| `protocolMetrics()` | Returns counter snapshot. |
| `acceptLoop()` | Accepts channels while server is open; submits connection tasks to a virtual-thread-per-task executor. Accept I/O failure while still open becomes an exception in the submitted accept task. |
| `serve(socket)` | Owns channel, adapts streams with `Channels`, calls shared protocol, silently catches `Exception`. |
| `main(arguments)` | Requires directory/socket path and optional maximum; prints bound path and self-joins indefinitely in a try-with-resources scope. No durability option. |
| `close()` | Closes listener, interrupts workers, closes cache, then deletes socket path; earlier failure can skip later stages. |

The constructor does not prove an existing socket is stale before deleting it and
does not restrict deletion to socket file types. The supplied path must be a
dedicated endpoint, not an arbitrary existing file or another active daemon's path.
It does not create socket parent directories, set restrictive socket permissions,
check peer credentials, or install an application authorization policy. Availability
depends on the platform's UNIX channel support. Virtual threads do not change the
protocol's post-decode admission boundary.

## Loopback TLS Daemon

Source: [TrainingCacheTlsDaemon.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheTlsDaemon.java).

| Function | Behavior |
| --- | --- |
| `TrainingCacheTlsDaemon(directory, port, maximumBytes, context, requireClientAuthentication)` | Requires directory, opens default cache, requires SSLContext, creates loopback SSL server with backlog 128, sets need-client-auth flag, submits accept task on virtual-thread executor. |
| `port()` | Returns actual listening port. |
| `protocolMetrics()` | Returns shared protocol counters. |
| `acceptLoop()` | Accepts SSL sockets and submits serve tasks; same open-versus-closed accept failure distinction. |
| `serve(socket)` | Owns SSL socket, enables TCP_NODELAY, explicitly completes handshake, then serves cache requests. Silently catches `Exception`, including handshake failures. |
| `close()` | Closes listener, interrupts executor and closes cache sequentially without joining connection tasks. |

There is no `main` in this class. An embedding caller constructs the SSLContext
with key/trust material. Cipher/version defaults come from that context/JVM; these
functions do not configure credential files, hostname verification, or map client
certificates to namespace permissions. Client authentication is optional via the
constructor flag. Handshake happens before `TrainingCacheProtocol.serve`, so failed
handshakes are not counted by the protocol's connection counter.

TLS construction opens the cache before checking context or binding; later failure
has no explicit resource rollback. This listener's TLS is independent of the
general config registry's production security flags. Opening its cache still uses
the training cache's local development configuration.

## Shutdown and Persistent Experiment Boundaries

The daemons provide no shared multi-version training scheduler. A research runner
must start one process, keep its store/endpoint stable across versions, and stop it
after all training/update work. Restarting the process reopens the cache and runs
its validation/index reconstruction, so it changes the lifecycle being measured.

[paper_common.py](../../scripts/paper_common.py) contains a TCP-daemon launch path;
[client.py](../../clients/python/aether_training_cache/client.py) contains Python
endpoint/connection ownership. Those callers are not inventoried by this page.
Do not infer process cleanup guarantees solely from these Java `close` methods.

## Verification Scope

The compiler-tree documentation check covers all explicit declarations in these
three files. [TrainingCacheTraceTest](../../modules/aether-training-cache/src/test/java/io/aetherdb/training/cache/TrainingCacheTraceTest.java)
exercises the shared handler with in-memory streams, not actual listener shutdown,
port binding, Unix endpoint permissions, TLS certificates, or idle-connection limits.
Those are distinct runtime integration checks; documentation link/layout tests do
not establish them.
