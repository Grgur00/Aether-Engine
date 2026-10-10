# Python ML Client Lifecycle and Operations

[Function index](FUNCTION-INDEX.md) | [Transform cache](PYTHON-TRANSFORM-FUNCTIONS.md) | [Framework integration](PYTHON-FRAMEWORK-FUNCTIONS.md)

This reference covers every explicit function in
[config.py](../../clients/python/aether_ml/config.py),
[lifecycle.py](../../clients/python/aether_ml/lifecycle.py),
[namespace.py](../../clients/python/aether_ml/namespace.py),
[metrics.py](../../clients/python/aether_ml/metrics.py), and
[cli.py](../../clients/python/aether_ml/cli.py). Exception types are defined in
[exceptions.py](../../clients/python/aether_ml/exceptions.py); public exports are
listed in [__init__.py](../../clients/python/aether_ml/__init__.py).

## Architecture and Ownership

```text
explicit AetherConfig or process environment
  -> create_client
  -> construct low-level AetherTrainingCache
  -> engine_info exchange / capability validation
  -> return one client to the transform-cache owner
  -> owner eventually calls client.close
```

The lifecycle module does not launch Java, open a database directory, choose bulk
loading, or enforce a persistent-daemon research protocol. It connects to a
separately managed service. Closing the Python client closes that connection,
not the daemon or stored artifacts.

`AetherTransformCache(client=None, ...)` stores create_client as a lazy factory;
the first enabled lookup/plan triggers this path. Supplying an explicit client
bypasses create_client's capability check unless the caller invokes it separately.
Supplying a factory lets each process obtain its own client through the wrapper's
PID check; the factory is not automatically called once per thread or per batch.

## Configuration Functions and Data

`AetherConfig` is a frozen dataclass with host, port, timeout, and optional
namespace. Its generated constructor/accessors are implicit, not handwritten
function declarations. Defaults are loopback, port 9484, timeout 30 seconds, and
namespace None. Frozen prevents normal field assignment; it does not validate
values or make a network connection.

| Function | Behavior and failure boundary |
| --- | --- |
| `AetherConfig.from_environment()` | Reads AETHER_HOST, AETHER_PORT, AETHER_TIMEOUT, and AETHER_NAMESPACE from the current process environment. Converts port with int and timeout with float; invalid numeric text raises ValueError. Returns a new configuration on every call, without changing environment variables. |

Missing variables use defaults; present empty strings do not. Empty host or
namespace is retained; empty numeric text fails conversion. Negative/out-of-range
ports and negative/nonfinite timeouts are not checked here. They can fail later in
socket operations. The classmethod returns cls, so a compatible subclass can be
constructed; it does not create a shared global configuration registry.

The namespace field is descriptive configuration here: create_client passes only
host, port, and timeout. AetherTransformCache still requires its own namespace
argument. AETHER_NAMESPACE does not automatically override that argument.

This configuration has no TLS context, Unix-socket, request trace, authentication,
or server-launch fields. The low-level client's separate constructor supports
additional transport settings; create_client does not expose them. See the
[client reference](PYTHON-CLIENT-FUNCTIONS.md) before choosing a different transport.
Do not infer secure remote transport from an ML configuration's host string.

## Capability and Client Functions

| Function | Behavior |
| --- | --- |
| `validate_capabilities(client, required_features)` | Calls engine_info and wraps failures of that call as ProtocolCompatibilityError. Requires reported protocol to compare equal to 1, computes required minus advertised features, and rejects missing features with a sorted diagnostic. Returns the original info dictionary without copying it. Default features are get-many, put-many, and contains-many. |
| `create_client(config)` | Imports the low-level AetherTrainingCache lazily, selects explicit config or from_environment, constructs a host/port/timeout client, and validates capabilities. On a validation Exception it closes the client and re-raises. On success returns the client for caller-owned cleanup. |

The low-level constructor does not connect immediately. engine_info drives the
actual exchange and may use the client's normal connection/retry machinery;
create_client itself has no extra retry loop. A constructor failure occurs before
the cleanup try block and propagates directly.

The capability check is feature negotiation, not a validation of every payload or
an attestation of source commit, Java version, checksum policy, or durability mode.
It does not inspect implementation semantics behind an advertised feature name.
The default feature set is a frozenset, and callers may supply another required set.

Malformed engine_info values are not uniformly wrapped: dictionary .get and set
conversion run outside the protected engine_info call. A non-dictionary response,
unhashable features, or incompatible values can raise ordinary Python errors.
Protocol comparison uses normal equality, not a strict integer type check. Cleanup
in create_client still runs for those ordinary Exceptions, but client.close failure
can replace the original validation error. BaseException paths are not covered by
this cleanup handler.

## Namespace Function

| Function | Behavior |
| --- | --- |
| `normalize_namespace(namespace)` | Strips outer whitespace, splits on slash, removes empty path components, and rejoins. Requires nonempty ASCII letters/digits/dot/underscore/hyphen components; otherwise raises IdentityError. Does not lowercase or hash the result. |

For example, `"  team//images/  "` becomes `"team/images"`. Interior spaces remain
invalid. Dot and dot-dot components are accepted by this character rule; the result
is a logical cache namespace, not a validated filesystem path. Never use it as a
substitute for managed-path security validation.

This helper is opt-in. artifact_key checks only for a nonempty string namespace;
the dataset/transform constructors do not call normalize_namespace. Equivalent
spellings will therefore remain different cache identities unless the caller
normalizes before constructing them. Non-string arguments can fail at strip rather
than producing IdentityError.

## Metrics Export Function

| Function | Behavior |
| --- | --- |
| `export_metrics(stats, emit, prefix)` | Iterates its fixed name mapping, emits only keys present in stats as float values, and invokes emit with prefix/name. Does not fetch statistics, reset counters, import a metric service, or catch sink exceptions. |

| Wrapper counter | Exported suffix |
| --- | --- |
| lookups / hits / misses | cache_lookups / cache_hits / cache_misses |
| publishes / hitRate | cache_publishes / cache_hit_rate |
| bytesRead / bytesPublished | bytes_read / bytes_written |
| cacheErrors | cache_errors |

The exporter emits cumulative wrapper counters, not server measurements or per-step
deltas. bytes_written means encoded successfully published payload bytes as counted
by the wrapper, not physical disk writes, WAL bytes, or compaction amplification.
lookupNs and nested latency summaries are not exported. Conversion can lose integer
precision for very large values. If float conversion or the sink fails, earlier
emissions remain; there is no transactional metrics batch or automatic retry.

To send values to a TensorBoard or other sink, provide a callable conforming to
emit(name, float). Any step/epoch association is owned by that callable, not this
module. Missing counter keys are omitted rather than emitted as zeros.

## CLI Functions and Trust Boundary

| Function | Behavior |
| --- | --- |
| `load_dataset(path)` | Resolves the Python configuration path, constructs an import specification named aether_ml_user_config, executes that module, and retrieves its dataset attribute. Requires a non-None value with plan/populate/stats attributes. Returns it without a concrete AetherDataset type check. |
| `main(argv)` | Parses plan/populate/stats, config path, and integer --workers (default 1). Loads the dataset, invokes the chosen operation, JSON-prints sorted/indented output, and calls dataset.close in finally after a successful load. |

The installed `aether-ml` entry point targets cli.main. Argument parsing errors
follow argparse's normal usage/error exit behavior. --workers is passed only to
populate; it does not change plan or stats. Workers are the dataset's population
threads, not a daemon count or a guarantee of multiprocessing/GPU execution.

The configuration file is **executable Python**, not inert YAML/JSON. Importing it
can run arbitrary code, access files, start work, or create clients. Use trusted
configuration files only. Resolving its pathname is not a sandbox. The loader's
attribute checks do not verify that plan/populate/stats are callable and do not
require close, although main unconditionally calls close in finally.

The loader does not change the process working directory, insert the configuration
directory into sys.path, or register the loaded module in sys.modules. Relative
paths inside configuration code follow the process's current directory unless
the configuration resolves them explicitly. A new module object is executed on
each load rather than reusing a normally imported configuration module.

The cleanup try begins after load_dataset returns. A configuration that creates a
resource and then raises during import is not automatically cleaned up by main.
Conversely, once loaded, close is attempted after operation/JSON/printing failures;
close failure can replace those failures. The CLI prints results, not a sealed
scientific receipt, and has no progress log, checkpoint, resumable campaign, daemon
launcher, or inference gate.

Each CLI invocation executes configuration anew. stats therefore reports the
dataset object's current wrapper statistics, not automatically historical metrics
from another process or the Java server. A configuration could implement different
behavior; attribute names alone do not establish persistence semantics.

## Exception Hierarchy

All custom types below have empty bodies and inherit normal Python exception
construction/string behavior. No type automatically performs a retry or cleanup.

| Type | Parent and use |
| --- | --- |
| AetherMLError | Exception; root integration error. |
| IdentityError | AetherMLError; source/namespace/schema identity failures. |
| TransformIdentityError | IdentityError; unsupported transform description/configuration. |
| ArtifactDecodeError | AetherMLError; payload framing/decoding failures. |
| ArtifactSchemaMismatch | ArtifactDecodeError; exported specialization, not raised by the current built-in codecs. |
| CacheMissError | AetherMLError; read-only lookup with missing keys. |
| CacheUnavailableError | AetherMLError; lookup/planning failure. |
| PublishError | CacheUnavailableError; publication failure, potentially with an uncertain server outcome. |
| WorkerLifecycleError | AetherMLError; exported specialization, not raised by the current worker-init helper. |
| ProtocolCompatibilityError | AetherMLError; capability/protocol negotiation failure. |

The low-level client's exceptions are a different hierarchy. Wrappers translate
some errors and leave callback, NumPy/Torch, import, numeric conversion, and malformed
metadata errors untouched. Catching AetherMLError alone is not a universal safety
boundary for every operation these adapters call.

## Verification and Reading Exercise

[test_aether_ml.py](../../clients/python/tests/test_aether_ml.py) exercises feature
negotiation, metrics export, and the transform cache's factory replacement on a PID
change. [Documented-contract tests](../../scripts/tests/test_ml_documented_contracts.py)
check configuration/namespace behavior, capability cleanup, CLI lifetime, and the
small framework identity boundaries with local fixtures. Those tests do not start
a Java daemon or prove an actual remote service advertises the correct semantics.

Trace the first lookup from a client=None transform cache through environment
reading and engine_info. Then trace the same cache with an explicit client and
explain why negotiation is no longer automatic. Finally distinguish closing a
connection from stopping the process that owns the database directory.
