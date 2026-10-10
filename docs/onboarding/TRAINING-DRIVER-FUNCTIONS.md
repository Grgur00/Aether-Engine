# Java Training Diagnostic Driver Functions

[Function index](FUNCTION-INDEX.md) | [Cache operations](TRAINING-CACHE-FUNCTIONS.md) | [Hit-path orchestration](HIT-PATH-FUNCTIONS.md) | [Root build tasks](ROOT-BUILD-ARCHITECTURE.md)

This reference covers all **24 explicit declarations** in
[HitPathBenchmark.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/HitPathBenchmark.java),
[TrainingCacheBenchmark.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheBenchmark.java)
and [TrainingCacheCrashCampaign.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheCrashCampaign.java).
These are standalone Java diagnostics, not model-training implementations or the
paired persistent-daemon H2 experiment. Their argument, process and timing
boundaries differ; results are not interchangeable.

## Ownership and Measurement Boundaries

| Driver | Process/store ownership | Measured work and limits |
| --- | --- | --- |
| HitPathBenchmark | One isolated JVM opens an existing durable cache with a 1 TiB logical capacity, drains compaction and closes it normally. | Times raw database get loops and defensive value copies. No RPC, artifact decoding, tensor assembly or training; segment values return metadata, not segment payload reads. |
| TrainingCacheBenchmark | Deletes its run directory, then opens/closes three caches sequentially over that path. | Cold, warm and mapped timings include open and close. Synthetic payload generation is outside timings. Does not preserve one daemon or prove ML lifecycle superiority. |
| TrainingCacheCrashCampaign | Parent deletes each trial directory, starts a child, waits, then reopens the child's store. | Child halts after put returns, without orderly close. Parent accepts absence or the expected length, not exact payload equality. Not a power-loss or arbitrary crash-point campaign. |

The benchmark and crash drivers recursively delete supplied run paths without
workspace containment checks. Use only disposable fixture directories, never
an existing dataset/cache or shared results directory. This documentation does
not execute them. The hit-path driver also opens and closes a real cache; its
preparation mode deliberately captures close/seal activity outside timing.

## Hit-Path Functions

| Declaration | Behavior and failure boundary |
| --- | --- |
| `HitPathBenchmark.HitPathBenchmark()` | Private empty constructor prevents normal external instantiation. |
| `HitPathBenchmark.main(args)` | Requires five or six arguments: dbPath, workloadPath, outputPath, requestSize, epochs, optional warmupEpochs (default 1). Request size must be 1..4096; epochs/warmups nonnegative. Requires DB-IDENTITY at cache root, parses keys, checks cache cardinality against unique keys, verifies presence, identifies segment-backed entries and drains compaction. For positive epochs runs warmups, captures idle baseline, then times each batch under a ReadDiagnostics collector restored in finally. Adds metadata/activity/trace evidence, rejects misses or changed activity, writes JSON status and rethrows captured Exception. Zero epochs skips warmups and timings, captures before/after close diagnostics as preparation. |
| `HitPathBenchmark.lookupBatch(database, keys, offset, requestSize)` | Allocates an outer byte array capped by remaining keys; performs one get per key and invokes value only when found, storing null on misses. Preserves input order and duplicate keys. No RPC, cache-envelope decoding or segment mapping. Caller supplies valid offset/request size; helper has no independent argument validation. |
| `HitPathBenchmark.readWorkload(path)` | Reads big-endian count, then namespace/sample length-prefixed strings and exactly 32 fingerprint bytes per key. Count must be positive and at most (file size minus four)/40, a minimum-size plausibility bound, not full validation. Builds CacheKey storage keys and rejects trailing bytes; truncated fields propagate I/O errors. Duplicates are allowed; unique cardinality is checked separately by main. |
| `HitPathBenchmark.readString(input)` | Accepts lengths 0..64 MiB, allocates/readFully then decodes UTF-8 with malformed/unmappable input reporting. Rejects negative/oversized lengths; permits empty strings at this layer, with identity validation delegated to CacheKey. |
| `HitPathBenchmark.requireIdle(activity)` | Requires state IDLE, numeric debtBytes equal to zero and empty lastFailure. Throws on mismatch. Assumes expected fields/types; malformed diagnostics may instead fail with null/type errors. |
| `HitPathBenchmark.requireUnchanged(before, after)` | Requires after idle, then equality of flushesStarted, flushesCompleted, backgroundCompactionsStarted, completed and failed counters against the original baseline. Does not compare every metric or prove absence of unrelated system activity. |
| `HitPathBenchmark.summarize(batches)` | Sums samples, misses, durations, database bytes, declared artifact bytes and segmented values. Sorts batch durations; emits per-batch mean/median/p95/p99 plus samples per summed timed second. Empty input yields zero statistics. Includes batches already appended before a later failure, not necessarily all attempted work. |
| `HitPathBenchmark.percentile(sorted, fraction)` | Returns zero for empty input, otherwise nearest-rank element at ceil(length * fraction) minus one. Assumes sorted array and valid fraction; main summary uses 0.95 and 0.99. No interpolation. |

### Timing and Report Semantics

Presence scans and warmups precede the activity baseline. Each duration includes
outer-array allocation, database lookups and returned-value copies; collector
attachment, activity snapshots, metadata validation and JSON encoding are outside
that duration. Read instrumentation inside the lookup remains included. A short
final batch has fewer samples; duration percentiles are **batch**, not per-sample,
statistics. Repeated epochs repeatedly count the same bytes and samples.

The metadata check after timing requires at least 12 bytes, recognized inline or
segment magic, version 1 and nonnegative declared length. It is not full envelope
checksum validation or segment payload verification. artifactBytes sums declared
lengths; databaseValueBytes sums actual encoded values returned. The two are not
equivalent I/O measurements.

Argument parsing/validation occurs before the report try/catch. Caught Exceptions
inside the workload emit FAILED with a string and partial evidence, but Errors
are not caught. JSON summary/directory creation/write happen afterward and can
fail, preventing a receipt or obscuring the original exception. Output writes are
not atomic or forced. PASSED therefore means this diagnostic's checks completed,
not certification of every storage invariant.

## Synthetic Benchmark Functions

| Declaration | Behavior and failure boundary |
| --- | --- |
| `TrainingCacheBenchmark.TrainingCacheBenchmark()` | Private empty utility constructor. |
| `TrainingCacheBenchmark.main(arguments)` | Requires directory, samples, payloadBytes and output. Samples positive; payload nonnegative integer or case-insensitive matrix. Matrix uses 256, 1024, 4096, 16384, 65536, 262144, 1048576 and 4194304 bytes in payload-specific subdirectories. Prints each report; writes one object for a single size or an array for matrix. Report output occurs only after all runs finish; exceptions propagate without FAILED receipt. |
| `TrainingCacheBenchmark.runBenchmark(directory, samples, payloadBytes)` | Deletes run directory; preallocates all keys/payloads with fixed benchmark-v1 transform identity. Times cold getOrCompute population, reopened get reuse and reopened map traversal independently, including each open/close. Samples cold/warm metrics before close; mapped phase sums remaining buffer lengths and rejects missing mappings. Formats timings, rates, warm hits, mapped bytes and cold/warm metrics. Warm get loop does not reject misses or validate returned payloads. |
| `TrainingCacheBenchmark.payload(seed, length)` | Allocates length bytes, filling byte(seed + index), wrapping modulo 256. Deterministic synthetic pattern, not an OCT/image decoding or augmentation workload. |
| `TrainingCacheBenchmark.seconds(nanos)` | Converts max(1, nanos) to seconds, avoiding zero/negative division in rates. Does not validate timer quality. |
| `TrainingCacheBenchmark.formatBytes(bytes)` | Labels exact multiples of MiB as MB, otherwise exact multiples of KiB as KB, else B. These display labels use binary factors, not decimal SI. |
| `TrainingCacheBenchmark.metricsJson(metrics)` | Locale.ROOT formatted JSON for hit/miss ratio, served/written bytes, evictions, corrupt entries, disk accounting and get/put p50/p95/p99. Six-decimal ratio is rounded. No mapped-phase metrics or device-level write-byte evidence. |
| `TrainingCacheBenchmark.ratio(hits, misses)` | Returns zero for zero total, otherwise hits divided by hits plus misses. Assumes nonnegative realistic counters; no overflow validation. |
| `TrainingCacheBenchmark.delete(directory)` | No-op when nonexistent; otherwise walks path, reverse-sorts descendants and deletes each. Wraps per-entry I/O failure in UncheckedIOException; walk failures propagate. No containment check, rollback or guaranteed all-or-nothing deletion. |

Synthetic payload arrays stay live across phases; their memory footprint scales
with samples times payload size. The mapped phase counts buffer lengths without
reading every byte, so mappedSamplesPerSecond is not proven full-payload throughput.
Default cache policy governs evictions; requested cardinality alone does not
guarantee all warm hits. Compare warmHits and missing-map failure rather than
assuming reuse. Each phase's cache close can flush/seal, and metrics captured
before close do not account for every close-time action.

## Crash Campaign Functions

| Declaration | Behavior and failure boundary |
| --- | --- |
| `TrainingCacheCrashCampaign.TrainingCacheCrashCampaign()` | Private empty utility constructor. |
| `TrainingCacheCrashCampaign.main(arguments)` | Exact two-argument --worker dispatch runs child logic. Otherwise accepts root and optional trials (default 1000). For each trial deletes trial-N, launches current java/classpath with --enable-preview and inherited I/O, waits without timeout, requires exit 91, then opens cache and permits null or 512 KiB recovered value. Prints completed count only after loop. Trials are not validated as positive; zero/negative values run no trials. |
| `TrainingCacheCrashCampaign.runWorker(directory)` | Constructs fixed crash/sample/campaign-v1 identity and zero-filled 512 KiB value; opens cache, puts it and calls Runtime.halt(91). Successful halt bypasses try-with-resources close/shutdown cleanup. No randomized timing, marker handshake or crash during append/install. |
| `TrainingCacheCrashCampaign.javaBinary()` | Resolves java.home/bin/java, adding .exe on Windows. Assumes the executable exists; ProcessBuilder failure propagates. |
| `TrainingCacheCrashCampaign.isWindows()` | Lowercases os.name using default locale and checks substring win. Platform heuristic, not an independent capability check. |
| `TrainingCacheCrashCampaign.delete(path)` | Skips nonexistent path; reverse-sorted Files.walk deletion, wrapping per-item I/O exceptions. No safe-root check or cleanup rollback. |

The parent allows loss of the published value and tests only length when found.
It does not compare zero bytes, require acknowledged durability, inspect WAL or
manifest state, vary artifact identity, or establish recovery after host/power
failure. A hung worker can hang the parent indefinitely; unexpected exits stop
the campaign with an exception and leave trial evidence on disk. This narrow
smoke campaign is separate from fault-injection and H3 consistency protocols.

## Verification Scope

Compiler-tree checks match all 24 declaration rows, including the three private
constructors and both delete helpers. That inventory prevents silent documentation
omissions; it does not run these drivers or prove timing, corruption, power-loss,
GPU performance or recovery correctness. Read the Python hit-path orchestration
and cache/diagnostic contracts alongside these local implementation limits.
