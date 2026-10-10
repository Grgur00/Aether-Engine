# Research Build, Environment, and Process Functions

[Function index](FUNCTION-INDEX.md) | [Campaign identity](RESEARCH-CAMPAIGN-FUNCTIONS.md) | [Stage and service ownership](RESEARCH-LIFECYCLE-FUNCTIONS.md)

Source: [paper_common.py](../../scripts/paper_common.py). All nine explicit functions,
including the nested stdout reader, are covered. ROOT is the module's checkout
root and its clients/python path is prepended at import. A separately bootstrapped
candidate checkout can therefore select different dependency bytes from the active
development tree; recorded and executing roots must not be conflated.

## Shared Ownership Architecture

```text
source inventory + classpath file + runtime files -> Java build receipt validation
environment probes + selected source hashes -> descriptive provenance report
JSON producer -> .tmp bytes -> os.replace -> visible document
java_daemon -> owned child + stderr file + stdout reader -> announced port
caller body -> bounded background drain/export -> terminate/wait/kill -> reader cleanup
```

These helpers do not choose sample sizes, statistical hypotheses, backend order or
dataset revision rules. They provide file/process mechanics that different runners
measure differently. In particular, closing java_daemon drains background work;
it is not merely an instantaneous process-handle release.

## File and Probe Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `write_json(path, value)` | Creates parents, serializes indented JSON with allow_nan=False and newline, writes UTF-8 to the fixed sibling path whose suffix ends .tmp, then os.replace publishes destination. Rejects nonfinite JSON values. No fsync, unique temp name, cleanup-on-failure, no-overwrite or multi-file atomicity. Concurrent writers need caller coordination. |
| `sha256(path)` | Reads a file in bounded 1 MiB binary chunks through hashlib SHA-256. Returns exact byte hash. Does not validate schema, trusted path, immutable input or scientific correctness. |
| `capture(command)` | Runs command in ROOT, captures text stdout/stderr, applies 30-second timeout and strips output. Nonzero exits remain result objects. Only OS launch failures/timeouts become an unavailable object; programming/decoding exceptions can propagate. Does not stream progress or raise automatically for nonzero status. |
| `environment()` | Collects Python/executable/platform/CPU count/time, Git/Java/pip/GPU/CPU/storage/mount probes and eight performance-related environment variables. Hashes selected Python/Java/build/config/lock patterns, optionally attaches the Java build manifest and archive provenance verification. Returns descriptive report; unavailable probes need not abort. Does not itself run java_classpath or require clean source/CUDA. |

write_json's replace gives a single visible-file publication boundary, not a
power-loss durable receipt. Serialization happens before writing the temporary
file; serialization failure preserves destination. Write/replace failure may leave
the fixed temporary file. An existing destination may be overwritten. Campaign
locking and new-output guards are separate policies, not implicit in this utility.

Environment source inventory includes the specified recursive Python, module Java,
Gradle Kotlin, paper JSON and environment-lock patterns. It is not all repository
content: datasets/results and unlisted formats/directories are not automatically
bound. Archive verification checks each declared file within ROOT, requires a
nonempty files mapping for verified=true, and compares bytes; it does not reject
all additional undeclared files or authenticate an originating Git commit.
sourceClean is copied separately and can be true while verified is false.

Eight captured performance variables are OMP_NUM_THREADS, MKL_NUM_THREADS,
OPENBLAS_NUM_THREADS, CUDA_VISIBLE_DEVICES, CUDA_DEVICE_ORDER, CUBLAS_WORKSPACE_CONFIG,
NVIDIA_TF32_OVERRIDE and PYTORCH_CUDA_ALLOC_CONF. Other environment changes are not
automatically covered. Generic probes are records, not a capability smoke test.

## Java Build Identity Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `java_build_sources(root)` | Hashes regular files matching declared root Gradle/properties/wrapper, module build/main source and build-logic patterns. Returns sorted POSIX relative names. Includes main resources under broad patterns, excludes module test source and other unlisted paths. No symlink rejection/containment guard or dependency-resolution verification here. Expects a Path-like root with glob. |
| `java_runtime_record(path)` | Returns file kind/SHA, directory kind/recursive file SHA mapping, or absent kind. Follows ordinary filesystem file/dir checks; no independent size, signature, nonempty-directory or symlink policy. Receipts describe absence as well as presence. |
| `java_classpath()` | Requires paper-runtime-classpath.txt and adjacent paper-runtime-build.json, validates schema/classpath byte hash and exact current Java source inventory, then records each nonempty classpath entry and compares exact runtime map. Returns stripped classpath. Supported IO/parse/type/attribute failures become a RuntimeError with rebuild command; no build is run automatically. |

Java runtime identity covers compiled classes/resources/dependency bytes as recorded,
not merely source hashes. Added/removed source/runtime files change inventory.
Classpath parsing uses os.pathsep; entry keys use absolute paths and duplicates
collapse in the runtime dict. Empty classpath map is rejected, but a matching
recorded absent entry or empty directory is not independently prohibited. The
downstream JVM still determines whether an executable class is loadable.

The manifest is unkeyed JSON, not a signature or independent compiler attestation.
This validation does not probe the Java executable/version, and does not lock files
against changes after hashing. Executable/package checks and exact runtime matching
belong to the calling protocol. Sources outside these patterns require their own
identity controls. Tests use explicit fake class/JAR bytes, not real Java compilation.

## Daemon Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `java_daemon(directory, *, maximum_bytes=1 << 40, durability="DURABLE", jvm_options=())` | Context-manager generator resolving/creating store, obtaining verified classpath and launching Java with supplied JVM options, preview flag, training-cache main class, ephemeral port, capacity and durability. Uses hidden creation flag on Windows, stdout pipe, sibling overwritten stderr log and daemon reader thread. Waits up to 60 seconds for a valid decimal port, yields port/PID/command, drains/exports compaction on body exit, then always runs process/thread cleanup. |
| `java_daemon.read_port()` | Nested thread callback strips each stdout line into a queue and pushes None at EOF. Does not parse the port, report thread exceptions or bound queue growth. Daemon thread status permits interpreter exit; it is not proof the worker has terminated. |

Startup ignores non-port lines, rejects EOF before announcement and distinguishes
deadline expiry from queue timeout. Port must be decimal and in 1..65535. There is
no explicit health/RPC handshake before yielding; the caller's first connection and
workload validation test the service. The queue is unbounded and the reader continues
collecting stdout after startup. stderr is a file, not merged into the queue.

On leaving the yielded body, a diagnostics client asks for bounded background
compaction drain, writes a uniquely timestamped sibling compaction JSON, then
rejects undrained or failed background work. Connection/drain/export failures can
raise and replace the body's exception; termination still runs in the outer finally.
Drains reported by a workload are separate from this final cleanup drain. Callers
must record their timing boundary, rather than assume this exit is always excluded.

Cleanup checks process.poll: a live child receives terminate and a 15-second wait;
timeout triggers kill and another 15-second wait. A second timeout can still raise.
It joins the reader for at most five seconds and closes stdout, not an unconditional
thread-termination assertion. There is no process-group descendant cleanup, remote
host recovery, automatic retry or deletion of store/log data. Startup exceptions
also enter this outer cleanup after the reader starts.

## Verification Scope

[test_java_provenance.py](../../scripts/tests/test_java_provenance.py) checks matching
fixture inventories and source/class/JAR/classpath drift. The
[documentation contracts](../../scripts/tests/test_research_infrastructure_contracts.py)
exercise JSON publication, probe return semantics, descriptive environment checks,
runtime-record limits and modeled daemon port/drain/termination paths. These checks
use disposable files and process/client doubles, not a new real JVM/GPU experiment,
power-loss test or guarantee that all child descendants are gone. Qualified AST
coverage includes every explicit function in this module.
