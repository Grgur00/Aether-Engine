# Java Benchmark Profiles and Execution Functions

[Function index](FUNCTION-INDEX.md) | [Engine](ENGINE-FUNCTIONS.md) | [Research workflows](EXPERIMENTS-AND-PROFILING.md) | [Observability](OBSERVABILITY-FUNCTIONS.md)

Source: [aether-benchmarks](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks).
This guide covers **41 explicit declarations in six complete files**: profile,
registry, plan, runner, scope enum and CvBenchmark (including private/nested helpers).
[Result formats, artifacts and baselines](JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md)
have a separate reference; [comparison/gating classes](JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md)
are documented separately too. Generated
record/enum members are excluded from the declaration count.

## Executable Architecture

```text
BenchmarkProfileRegistry metadata (24 profiles)
    -> BenchmarkProfileRunner.plan
    -> supported profile? CLI overrides -> CvBenchmark arguments
    -> fresh directory -> load -> close -> reopen/warmup -> measured point reads
    -> optional child-process durable-marker halts + reopen checks
    -> one local.cv.persistence JSON report
```

Only local.write.sequential.group_sync, local.read.point_warm and
local.recovery.wal_replay have argument mappings. The other 21 registered profiles
return nonexecutable plans. Neither registration nor DISTRIBUTED scope implies
an RPC/Raft workload exists. All three executable profiles run the same combined
load/read/recovery program, not isolated benchmark implementations. Output profile
is hardcoded local.cv.persistence, not the requested registry profile ID.

This is a local Java storage benchmark, not the Python ML lifecycle/H2 campaign,
not JMH, and not an external-engine comparison. HdrHistogram is the existing
latency engine; metrics are not sourced from the metered database decorator.
No repeated paired-block design, random backend ordering or statistical
uncertainty estimation occurs in this runner.

## Profile Metadata and Registry Functions

Sources: [BenchmarkProfile.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkProfile.java),
[BenchmarkProfileRegistry.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkProfileRegistry.java),
[BenchmarkProfileScope.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkProfileScope.java).

| Function | Behavior and boundary |
| --- | --- |
| `BenchmarkProfile.BenchmarkProfile(id, scope, description)` | Requires nonblank id<=128 chars matching [a-z0-9_.]+, nonnull scope, nonblank description<=512. Then strips strings. ID surrounding whitespace already fails regex; dots/underscores alone can pass if nonblank. Description limit is checked before strip. No executable-workload validation. |
| `BenchmarkProfileRegistry.BenchmarkProfileRegistry()` | Private utility constructor. |
| `BenchmarkProfileRegistry.requiredProfiles()` | Returns immutable static list in specification order: 15 LOCAL, nine DISTRIBUTED. |
| `BenchmarkProfileRegistry.find(id)` | Exact lookup into immutable index, Optional when absent. No case/whitespace normalization or executable check. |
| `BenchmarkProfileRegistry.localProfiles()` | Filters static list for LOCAL into new unmodifiable list in list order. |
| `BenchmarkProfileRegistry.distributedProfiles()` | Same for DISTRIBUTED. |
| `BenchmarkProfileRegistry.local(id, description)` | Constructs LOCAL metadata. |
| `BenchmarkProfileRegistry.distributed(id, description)` | Constructs DISTRIBUTED metadata. |
| `BenchmarkProfileRegistry.index(profiles)` | Builds LinkedHashMap; duplicate ID raises IllegalStateException during initialization; returns Map.copyOf. Result map ordering is not guaranteed. |

BenchmarkProfileScope LOCAL and DISTRIBUTED describe required environment, not
implemented behavior. Generated record accessors/equality expose immutable strings
and enum. Registry profile IDs:

| Group | IDs |
| --- | --- |
| Local writes | local.write.sequential.group_sync; local.write.random.group_sync; local.write.sync_per_write; local.write.async_wal; local.write.batch_matrix |
| Local reads/mixed | local.read.point_warm; local.read.point_cold_open; local.read.range_scan; local.mixed.read_write |
| Local maintenance | local.compaction_active; local.recovery.wal_replay; local.open.close; local.cache.hit_miss; local.checkpoint; local.restore_verify |
| RPC | rpc.echo; rpc.write.leader |
| Raft | raft.write.replicated_3; raft.write.replicated_5; raft.read.linearizable; raft.read_follower_stale_if_enabled; raft.leader_failover; raft.snapshot_transfer; raft.membership_change |

## Plan and CLI Functions

Sources: [BenchmarkProfileRunPlan.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkProfileRunPlan.java),
[BenchmarkProfileRunner.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkProfileRunner.java).

| Function | Behavior and boundary |
| --- | --- |
| `BenchmarkProfileRunPlan.BenchmarkProfileRunPlan(profile, directory, output, cvArguments)` | Requires fields, List.copyOf owns immutable arguments. Null elements fail copying. Does not verify path safety/existence, argument syntax or profile/argument consistency. |
| `BenchmarkProfileRunPlan.executable()` | True iff argument list nonempty, not a runtime readiness/disk-space check. |
| `BenchmarkProfileRunner.BenchmarkProfileRunner()` | Private utility constructor. |
| `BenchmarkProfileRunner.main(args)` | Calls runCli; System.exit only for nonzero result. Exceptions propagate from execution. |
| `BenchmarkProfileRunner.runCli(args)` | Exactly one --list prints all profiles as tab-separated metadata and returns 0. Otherwise catches IllegalArgumentException from planning and returns 2; unsupported plan prints message and returns 3. Supported plan calls CvBenchmark.main, returns 0 on success; execution errors are not converted to these plan exit codes. |
| `BenchmarkProfileRunner.plan(args)` | Parses option/value pairs; requires profile and directory. Defaults output to normalized absolute sibling named directory-file-name-results.json; exact registry lookup. Defaults records/reads=100000, crashes=0, batch=1000, value=256, GROUP_SYNC, REOPENED_WARMUP. Duplicate options overwrite earlier values; unknown/missing options or numeric parse fail. Does not validate numeric ranges or enum membership before building plan. |
| `BenchmarkProfileRunner.cvArguments(profileId, directory, output, records, reads, crashPoints, batchSize, valueBytes, durability, cacheMode)` | Unsupported IDs return empty list. Sequential-write profile clamps reads to 1..1000, crashes=0, durability=GROUP_SYNC. Warm-read sets crashes=0/cache=REOPENED_WARMUP. Recovery sets crashes>=1. Emits immutable option/value list; all use combined CvBenchmark workflow. |
| `BenchmarkProfileRunner.add(values, option, value)` | Adds two list entries, no validation. |
| `BenchmarkProfileRunner.normalizeEnum(value)` | Locale.ROOT uppercase, hyphens replaced by underscores; does not trim or validate enum. |

An executable plan can still contain invalid counts/enums that CvBenchmark.parse
rejects later. Path selection is not a sandbox; fresh-directory check prevents
reuse of a nonempty database but output may overwrite an existing report.

## Coordinator and Reporting Functions

Source: [CvBenchmark.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/CvBenchmark.java).

| Function | Behavior and boundary |
| --- | --- |
| `CvBenchmark.CvBenchmark()` | Private utility constructor. |
| `CvBenchmark.main(arguments)` | First token crash-worker selects internal worker, directly indexing/parsing four following values without normal CLI validation. Otherwise parses Config and runs coordinator. Exceptions propagate. |
| `CvBenchmark.run(config)` | Requires fresh database directory, loads deterministic batches using requested durability, closes database, sizes files; warmups/reopens and validates measured random point reads; optionally launches halted child markers and reopens to check markers/base read/new write. Writes report only after all stages succeed. Owns normal databases/batches with try-with-resources, but halted child intentionally skips close. Leaves database/output artifacts in place; no cleanup/deletion. |
| `CvBenchmark.jsonReport(config, writes, writeElapsedNanos, databaseBytesAfterLoad, reads, readElapsedNanos, readDatabaseOpenNanos, hits, recoveries, finalDatabaseBytes)` | Builds environment/config/workload/storage maps, obtains git metadata, constructs BenchmarkResultV1 and encodes JSON. Top-level throughput/latency are measured reads; write throughput/percentiles are workload metadata. Profile fixed local.cv.persistence. Uses exact arithmetic for logical bytes and selected counter totals, so overflow can abort reporting. |
| `CvBenchmark.decimal(value)` | Locale.ROOT three decimal places for metadata strings. Rounds displayed values. |
| `CvBenchmark.seconds(nanos)` | max(1,nanos)/1e9, minimum one nanosecond denominator. |
| `CvBenchmark.histogram()` | HdrHistogram with highest trackable latency one minute, three significant digits. |
| `CvBenchmark.record(histogram, nanos)` | Clamps latency into 0..one minute before recording; does not preserve values above cap. |

Write wall time begins before Aether.open and ends after close, including batch
construction, key/value generation, progress output and close. Write latency
samples cover database.write only, one sample per batch; returned WriteResult
is ignored. Record throughput is not batch throughput. No storage-policy override
is applied; pressure/admission errors can stop a run.

Read-open time is reported separately. Warmup count is
min(reads,max(1000,reads/10)); random generator has fixed seed 0xC0FFEE42 and its
state advances during warmup. Measured wall time includes key selection/generation,
get, expected-value construction and validation. Each latency sample starts before
key(record) is evaluated and ends after get: key allocation is included, expected
value validation excluded. Read close is outside measured read wall time. Every
measured read must match expected bytes, otherwise no successful report is emitted.

REOPENED_WARMUP uses the measured database for warmup; OS page cache is uncontrolled.
COLD_OPEN first warmups in a separate database, closes it, then attempts macOS cache
purge before timed open. This is not an out-of-core random-read experiment.

The report's fixed storagePath and COLD_OPEN description claim checkpoint
materialization/heap-resident point reads. Treat these as report text, not proof
of the current engine path: [PersistentAetherDatabase](../../modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentAetherDatabase.java)
now owns SSTable readers and searches table lookups. These strings alone cannot
establish which reads hit native memtables, block cache or mapped SSTable data.

Counters aggregate base records, measured reads and crash markers, not all
invocations. Warmups, recovery validation reads and post-recovery writes are
excluded. submitted=records+reads+crashPoints; acknowledged=records+hits+successful
recoveries. Timed-out/rejected/error values are zero on the success-only report
path, not comprehensive instrumentation. acknowledgedWritesLost is literal "0"
after selected markers validate; walBytesReplayed is literal "unavailable".
RecoveryTrial validation duration is collected but not serialized per trial here.
Git command failure yields unknown commit; failed git status becomes empty and can
appear clean. No source-tree digest or frozen harness identity is recorded here.

## Crash, Payload and Resource Helpers

| Function | Behavior and boundary |
| --- | --- |
| `CvBenchmark.crashWorker(directory, markerKey, point, valueBytes)` | Starts java.home/bin/java with --enable-preview, current classpath and crash-worker arguments; inherits output/input. Does not inherit coordinator JVM argument list. No wait timeout/kill/retry policy; coordinator waits indefinitely for exit. |
| `CvBenchmark.runCrashWorker(directory, markerKey, point, valueBytes)` | Opens database, puts one marker using ordinary put defaults (currently GROUP_SYNC), prints/flushes stdout, Runtime.halt(91). No try-with-resources/close before halt. Not a crash injected inside commit/publication. |
| `CvBenchmark.key(record)` | Allocates 16-byte default big-endian buffer, writes two mixed long values. Sequential numeric record generation does not produce lexicographically sequential storage keys. |
| `CvBenchmark.value(record, bytes)` | Allocates requested byte count, fills deterministically by mixing record-derived state and taking eight-byte groups. Not external/compressibility-controlled data. Zero size allowed. |
| `CvBenchmark.mix64(value)` | Shift/XOR/multiply mixer with wrapping long arithmetic; deterministic payload/key utility, not cryptographic identity. |
| `CvBenchmark.requireValue(result, expected)` | Requires found lookup and Arrays.equals bytes, otherwise IllegalStateException. Null result fails naturally; validation has no sampling of byte equality. |
| `CvBenchmark.directoryBytes(directory)` | Files.walk with resource close; sums Files.size of regular files, wrapping size failures in IllegalStateException. Logical file sizes, not cumulative bytes written or device allocation. No symlink-follow traversal option. |
| `CvBenchmark.median(values)` | Empty=>0, otherwise sorts input in place and returns element length/2 (upper middle for even count, not mean of middles). |
| `CvBenchmark.cpuDescription()` | Tries sysctl CPU brand; falls back to PROCESSOR_IDENTIFIER, then os.arch. Fallback is not exact CPU model. |
| `CvBenchmark.purgeMacFileSystemCache()` | Requires exact os.name Mac OS X; launches osascript administrator-privilege purge dialog, inherits IO, waits and requires exit=0. No timeout. Rejects other platforms instead of claiming a cold cache. |
| `CvBenchmark.command(command)` | Starts merged stdout/stderr subprocess, reads all output and waits, returns stripped output only for exit=0. Any Exception returns empty; interrupts not restored, no timeout/bounded output. Runs in inherited working directory. |
| `CvBenchmark.requireFreshDirectory(directory)` | Creates directory hierarchy then rejects any existing entry. Does not erase files; failure can leave newly created directory. No explicit path ownership/alias policy. |

Recovery loop requires child exit 91. For trial point P it verifies all P marker
keys, one base key, then performs/reads a new negative-record write before closing.
Open timer excludes validation/close; validation timer includes close. Trials
accumulate markers in the same database. This checks selected acknowledged-marker
survival after process halt, not power loss, arbitrary interrupted write points,
partial bulk publication, distributed failover or exhaustive database integrity.

## Nested Configuration Functions

| Function | Behavior and boundary |
| --- | --- |
| `CvBenchmark.CacheMode.CacheMode(description)` | Stores descriptive text for REOPENED_WARMUP and COLD_OPEN. |
| `CvBenchmark.CacheMode.description()` | Returns stored literal; does not inspect actual cache state. |
| `CvBenchmark.Config.parse(arguments)` | Parses option/value pairs with defaults records/reads=100000, crashes=4, batch=1000, value=256, GROUP_SYNC, REOPENED_WARMUP. Requires directory; default sibling output. Requires records/reads>=1, crashes>=0, batch 1..WriteBatch.MAX_OPERATIONS, value>=0. Enum.valueOf validates normalized strings. No upper record/read/value cap or batch byte-budget preflight. |

Config and RecoveryTrial use generated immutable record accessors. Config direct
implicit constructor has no validation; parse is the validated entry point.
Runner's default crash count is zero, direct CvBenchmark default four. Report
writing creates output parent then Files.writeString overwrites existing report;
no atomic temporary/rename/force receipt publication. A failed run can leave
database data without a final report; a prior output may remain unchanged.

## Build and Verification Scope

[build.gradle.kts](../../modules/aether-benchmarks/build.gradle.kts) uses application
mainClass BenchmarkProfileRunner, dependencies API/engine and HdrHistogram 2.2.2.
Only AETHER_JFR exactly "true" adds Gradle run recording at
build/jfr/aether-benchmark.jfr with profile settings, disk=true, dumponexit=true,
maxsize=2g, and aether.benchmark.jfr.path property. Child crash workers do not
automatically receive these JVM recording arguments.

[BenchmarkProfileRunnerTest.java](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/BenchmarkProfileRunnerTest.java)
has three plan tests: canonical sequential arguments, unsupported range plan,
unknown profile. [BenchmarkProfileRegistryTest.java](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/BenchmarkProfileRegistryTest.java)
has two registry-order/lookup tests. Neither executes CvBenchmark, crash recovery,
cache purge, payload validation, report provenance or subprocess cleanup.

```powershell
.\gradlew.bat --no-daemon :modules:aether-benchmarks:test --tests '*BenchmarkProfileRunnerTest' --tests '*BenchmarkProfileRegistryTest' --rerun
.venv\Scripts\python.exe -m pytest scripts/tests/test_contributor_docs.py -q
```

These lightweight tests do not establish performance, durability or executable
distributed profiles. Before a real benchmark, use a disposable empty directory
and inspect current engine defaults, disk pressure and output ownership separately.
