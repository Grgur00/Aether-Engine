# Development RPC Server and Socket Functions

[Function index](FUNCTION-INDEX.md) | [RPC API](RPC-API-FUNCTIONS.md) | [Transport limits](RPC-TRANSPORT-LIMITS.md)

Source: [PlaintextDevelopmentRpc](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/PlaintextDevelopmentRpc.java).
This guide covers **32 explicit declarations**: factories/shared wire helpers,
FrameInput, DevelopmentServer (including its anonymous responder), Cancellation
and SetOfConnections. The [client reference](RPC-CLIENT-FUNCTIONS.md) covers the
other 22 declarations; the file contains 54 explicit declarations altogether.

## Server Architecture

```text
bind -> listener + virtual-thread accept loop
  -> accepted socket -> tracked connection -> virtual-thread serve
  -> first HELLO -> peer role/cluster/node checks -> local HELLO
  -> REQUEST BEGIN -> admission snapshot -> reserve declared bytes
  -> per-stream assembler -> complete body -> shared handler executor
  -> exactly-once responder attempt -> release stream -> RESPONSE frames
CANCEL / socket failure -> cooperative cancellation callbacks
close -> close listener/sockets + shutdownNow both executors
```

The implementation is development-only plaintext, with CRC and bounded framing,
not TLS or authenticated cluster membership. It does not use RpcConnectionState
or RpcFlowController. HELLO limits are advertised, but the server does not retain
remote limits or negotiate minima for response fragmentation.

## Factories and Shared Wire Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.PlaintextDevelopmentRpc()` | Private constructor for factory utility. Static initialization resolves development defaults and creates one reusable AdmissionController. |
| `PlaintextDevelopmentRpc.bind(identity, host, port)` | Creates DevelopmentServer using static defaults; port zero permits OS-selected port. Constructor starts serving before return. |
| `PlaintextDevelopmentRpc.bind(identity, host, port, configuration)` | Resolves/validates configuration before creating server. Does not select TLS even if caller configuration describes security settings. |
| `PlaintextDevelopmentRpc.client(identity)` | Constructs DevelopmentClient using defaults. Connection creation/reuse is described by the client implementation, not this factory. |
| `PlaintextDevelopmentRpc.client(identity, configuration)` | Resolves transport limits and constructs DevelopmentClient; does not eagerly connect. |
| `PlaintextDevelopmentRpc.hello(identity, role, configuration)` | Chooses nonzero ThreadLocalRandom nonce, constructs HELLO with supplied identity/limits, fixed keepalive 30000/10000 ms, engine 0.2.0 and Java 21. Those runtime fields are constants, not detected installed versions. Nonce is not a cryptographic authentication token. |
| `PlaintextDevelopmentRpc.helloFrame(hello)` | Encodes HELLO into one stream-zero BEGIN+END frame with zero invocation/code/timeout/credit, total equal to payload length. Allocates/copies bytes through codec/frame ownership. |
| `PlaintextDevelopmentRpc.validatePeer(local, remote, expectedRole, expectedNode)` | Requires expected role, matching cluster, remote node different from local, and matching optional expected node. Throws RpcProtocolException on mismatch. Does not authenticate claimed identity, compare session IDs or negotiate limits. |
| `PlaintextDevelopmentRpc.configuredSocket()` | Creates unconnected Socket with TCP_NODELAY, keepalive and requested send/receive buffers 256 KiB. OS can adjust buffer sizes; this is not application framing or deadline configuration. IOException propagates. |
| `PlaintextDevelopmentRpc.writeFrame(output, writeLock, frame)` | Encodes outside lock, then synchronizes write and flush on shared lock. Serializes individual frames, allowing messages to interleave by stream between frames. IOException propagates; no socket-write deadline or automatic retry here. |
| `PlaintextDevelopmentRpc.FrameInput.FrameInput(input, configuration)` | Stores input and creates decoder with local frame limit. Each instance also owns a 64 KiB read buffer and queue of completed frames. |
| `PlaintextDevelopmentRpc.FrameInput.next()` | Reads until decoder yields queued frames, returns first. EOF raises IOException even when decoder is idle; does not distinguish partial-frame EOF. Decoding failures propagate. Coalesced frames queue for subsequent calls; a zero-byte read simply retries. |

Accepted sockets receive the same TCP settings in acceptLoop rather than calling
configuredSocket. No server HELLO/read timeout is set here: a silent peer can
hold a connection task. TCP keepalive is not the advertised application keepalive
protocol, and no PING/PONG handler is supplied by the server path.

## Listener and Connection Functions

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.DevelopmentServer.DevelopmentServer(identity, host, port, configuration)` | Requires identity/config, nonblank host and port 0..65535. Creates reuse-address ServerSocket, resolves/binds host, stores actual endpoint and submits acceptLoop. IOException becomes IllegalStateException. Virtual-thread connection/handler executors are initialized with the object; failed construction has no explicit cleanup block for all partial resources. |
| `PlaintextDevelopmentRpc.DevelopmentServer.register(operation, handler)` | Rejects closed server/null arguments, then atomic putIfAbsent by code; duplicate raises IllegalArgumentException. Closed check and insertion are not one atomic operation, so this is not a linearized registration/shutdown barrier. |
| `PlaintextDevelopmentRpc.DevelopmentServer.endpoint()` | Returns stored actual bound endpoint, including assigned port. Still returns it after close; not a readiness probe. |
| `PlaintextDevelopmentRpc.DevelopmentServer.acceptLoop()` | While open, accepts/configures/tracks socket and submits serve. IOException while not closed triggers server close. Task rejection/runtime exceptions are not caught by its IOException catch; no global accepted-connection cap is implemented. |
| `PlaintextDevelopmentRpc.DevelopmentServer.serve(socket)` | Owns socket/input/output with try-with-resources, per-connection stream map, inbound counter and write lock. Requires first frame type HELLO, decodes/validates DIALER identity and responds with ACCEPTOR HELLO, then processes frames. IOException/RuntimeException cancels stream tokens; finally removes tracked socket. Failures are swallowed without a diagnostic log or protocol-error response. |

serve checks the first frame's type and HELLO payload, not a separate exact
HELLO-header flag/code policy. Cancellation callback failure can interrupt the
failure-path iteration; try-with-resources still closes owned socket and finally
removes tracking. Resource counters/maps are connection-local, not one server-wide
admission budget.

## Assembly and Dispatch Functions

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.DevelopmentServer.applyServerFrame(frame, streams, inboundBytes, output, writeLock)` | CANCEL looks up stream ID, requests cancellation and releases an incomplete assembly; it does not compare CANCEL invocation identity. Other types must be REQUEST. New streams require BEGIN, evaluate inbound admission and reserve declared bytes before creating assembler/token/state. Rejections send mapped error status without installing stream. Valid fragments assemble; unknown completed operation releases state and sends INVALID_ARGUMENT. Known complete body gets deadline and asynchronous invoke dispatch. |
| `PlaintextDevelopmentRpc.DevelopmentServer.invoke(operation, body, header, stream, streams, inboundBytes, deadline, output, writeLock)` | Creates atomic completion guard/responder. Checks wall-clock deadline before dispatch, otherwise constructs defensively copied RpcServerRequest and calls handler. RuntimeException/Error before completion becomes safe INTERNAL detail after releasing stream. No forced deadline interrupt, automatic completion for a handler that returns silently or lane-specific executor selection. |
| `PlaintextDevelopmentRpc.DevelopmentServer.anonymous.success(response)` | Delegates to finish with OK and empty detail. Response body validation happens there; no pre-completion copying in this method. |
| `PlaintextDevelopmentRpc.DevelopmentServer.anonymous.fail(status, detail)` | Delegates to finish with empty response bytes. Does not independently reject OK; fail(OK, empty detail) can complete as an empty success. Null detail can fail after completion has been claimed. |
| `PlaintextDevelopmentRpc.DevelopmentServer.anonymous.finish(status, response, detail)` | Atomically claims completion, throwing on a second attempt; releases stream before validation/write. Rejects null status or OK with nonempty detail. Non-OK detail encodes UTF-8 with 4096-byte cap; successful response uses operation response limit. Null/oversized successful body or oversized error becomes RESOURCE_EXHAUSTED with safe fixed payload. Then sends response. Claimed completion is not rolled back on validation or encoding failure. |
| `PlaintextDevelopmentRpc.DevelopmentServer.reserveInbound(inboundBytes, bytes)` | CAS loop adds declared bytes, rejecting aggregate above configuration.inboundBytes(). Internal caller supplies validated nonnegative length; helper has no independent negative check. Snapshot admission is supplemented by actual reservation. |
| `PlaintextDevelopmentRpc.DevelopmentServer.releaseServerStream(streams, inboundBytes, streamId, stream)` | Conditional remove of exact stream value then subtracts its reservation once. Does not cancel handler, overwrite body or release unrelated streams. |
| `PlaintextDevelopmentRpc.DevelopmentServer.respond(output, writeLock, stream, invocation, status, payload, responseLimit)` | Replaces payload beyond supplied limit with empty bytes, fragments RESPONSE using local frame limit and flushes each frame. Swallows IOException; runtime codec errors propagate. No remote-limit negotiation, error-detail sanitization beyond caller logic or confirmation of peer receipt. |
| `PlaintextDevelopmentRpc.DevelopmentServer.close()` | Atomic idempotent closed flag; closes listener, tracked sockets and shutdownNow connection/handler executors. Ignores listener IOException. Does not send GOAWAY, wait for accepted work to finish or guarantee cooperative handlers stop. |

For existing streams, assembler checks stable type/code/stream/invocation and
contiguous offsets. The server does not enforce dialer odd parity, permanent
stream-ID nonreuse or authenticated operation authorization. A rejected fragmented
BEGIN leaves no stream; later continuation can therefore terminate the connection
as fragment-without-admitted-stream instead of being drained.

Deadline starts **after complete assembly**, using BEGIN timeout captured in
ServerStream (zero means operation default), not when first byte arrives. Instant
is wall-clock here, and descriptor millisecond conversion can lose submillisecond
precision. The deadline is checked once before handler call; cancellation is
cooperative. All execution-policy values use the same handler executor today.

An active handler retains its stream/reserved bytes until responder completion;
returning without completing leaves those resources occupied. CANCEL on an already
assembled request sets token but does not release stream itself. After successful
completion the map entry is removed, so a malicious peer can reuse an ID or send
another BEGIN while the prior handler still executes after responding. Exactly-once
responder claim is not durable exactly-once operation execution.

Error handling also has boundaries: if finish claims completion then fails
validation, invoke's catch cannot claim again to send INTERNAL. The generic handler
failure path bounds its fixed detail by the operation response limit, which can
produce empty detail for small limits. Safe fixed handler-error text avoids exposing
exception strings, but explicit handler detail is not comprehensively sanitized.

RegisteredOperation and ServerStream are generated-method records; they hold
descriptor/handler and per-stream identity/assembler/token/timeout/code/reservation.
They have no explicit declarations in the compiler inventory.

## Cancellation and Socket Tracking

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.Cancellation.isCancelled()` | AtomicBoolean observation; no blocking or interrupt. |
| `PlaintextDevelopmentRpc.Cancellation.onCancel(callback)` | Synchronized registration, rejects null. If already cancelled invokes callback immediately while holding monitor; otherwise appends callback. Callback exceptions propagate. |
| `PlaintextDevelopmentRpc.Cancellation.cancel()` | Under monitor atomically marks once, snapshots callbacks and clears list; invokes snapshot outside monitor on cancelling thread. Repeated cancellation returns. A throwing callback prevents later snapshot callbacks from running; no exception isolation. |
| `PlaintextDevelopmentRpc.SetOfConnections.add(socket)` | Adds socket to concurrent set; no closed-state check or ownership transfer guarantee beyond tracking. |
| `PlaintextDevelopmentRpc.SetOfConnections.remove(socket)` | Removes tracked socket; does not close it. serve's try-with-resources owns closing. |
| `PlaintextDevelopmentRpc.SetOfConnections.closeAll()` | Iterates concurrent set, closes each socket ignoring IOException, then clears set. Concurrent additions are not coordinated by a shutdown lock; clearing is not proof every racing addition was visited/closed. |

## Coverage and Verification

Compiler-tree coverage checks **32/32 declarations in this partition (100%)**.
This is **32/54 (59.3%)** of PlaintextDevelopmentRpc; the client reference covers
the other **22/54 (40.7%)**, completing explicit declaration coverage for this file.
[PlaintextDevelopmentRpcTest](../../modules/aether-rpc-transport/src/test/java/io/aetherdb/rpc/transport/PlaintextDevelopmentRpcTest.java)
contains loopback multiplexing, cancellation, identity mismatch, unsafe retry and
admission checks. These do not prove production TLS, graceful drain or every race
and malformed-frame behavior described above. Runtime code is unchanged.
