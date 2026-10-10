# Observability and Database Metrics Functions

The [flush diagnostic reference](FLUSH-DIAGNOSTIC-FUNCTIONS.md) covers request-owned
write, flush, compaction and SSTable-finish timing, distinct from this registry/export layer.

[Function index](FUNCTION-INDEX.md) | [Engine entry points](ENGINE-FUNCTIONS.md) | [Operations](OPERATIONS-AND-DEBUGGING.md) | [Benchmark metrics](BENCHMARK-METRICS-FUNCTIONS.md)

Sources: [observability API](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api)
and six engine telemetry files linked below. Covers **50 explicit declarations
across 14 files**: 17 declarations in eight API files, 33 in six engine files.
Implicit record accessors/constructors and enum helpers are outside the count;
their semantics are explained. Factories are covered by the engine reference.

## Measurement Architecture

```text
Aether.instrument / openWithMetrics / openInMemoryWithMetrics
    -> owning DefaultMeteredAetherDatabase decorator
    -> cumulative operation counters + rolling latency rings
    -> DatabaseMetrics snapshot
    -> explicit DatabaseMetricExporter.export call
    -> InMemoryMetricRegistry observation history
```

Instrumentation is opt-in; ordinary opens are not decorated. The wrapper owns
delegate close. These files do not supply a scheduler, monitoring HTTP endpoint,
health probe or automatic export loop. Health values describe permissions, not
an access-enforcement mechanism or running health-state machine.

Telemetry is not durability evidence. A returned WriteResult still needs outcome
interpretation; a returned cursor is not a completed scan. Registry observations
are not aggregated counters or histogram buckets. Registry synchronization does
not make engine snapshots transactionally consistent under concurrent mutation.

## Names and Label Functions

Sources: [ObservabilityNames.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/ObservabilityNames.java),
[MetricDescriptor.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/MetricDescriptor.java),
[MetricLabels.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/MetricLabels.java),
[MetricKind.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/MetricKind.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ObservabilityNames.ObservabilityNames()` | Private empty utility constructor. |
| `ObservabilityNames.requireValidMetricName(name)` | Requires nonnull full regex match: aether_ prefix, lowercase letter, lowercase alphanumeric groups separated by single underscores, optional total/seconds/bytes/ratio suffix. Returns original string. No normalization, length cap or name/unit/kind compatibility check. Invalid input raises IllegalArgumentException. |
| `ObservabilityNames.requireValidLabelName(name)` | Requires [a-z][a-z0-9_]* and rejects exact forbidden names. Returns original string. Consecutive/trailing underscores allowed. Does not enforce actual cardinality or redact values. |
| `MetricDescriptor.MetricDescriptor(name, kind, unit, labels)` | Validates name, requires nonnull kind/list, converts null unit to empty string. List.copyOf owns ordered labels; validates each name. Duplicates allowed, units otherwise unchecked. Null elements fail during copying. Record equality includes unit and label order. |
| `MetricLabels.MetricLabels(values)` | Requires map, validates keys, rejects null/blank values or String.length()>128. Copies through TreeMap into Map.copyOf. Final map iteration order is not guaranteed. No label-count or distinct-value cardinality cap. |
| `MetricLabels.of(key, value)` | Singleton Map.of then constructor. Nulls can fail in Map.of before custom validation. |

MetricKind COUNTER, GAUGE and HISTOGRAM are descriptive; they do not enforce
monotonicity, nonnegative values or buckets. MetricLabels.EMPTY is immutable.
String lengths are UTF-16 code units, not encoded byte lengths. Conventional
metric suffixes are not mandatory: the main regex already accepts those words.

Forbidden exact label names: key, value, collection_name, principal_id, token,
certificate_subject, ip_address, request_id, trace_id, schema_fingerprint,
exception_message, file_path. Equivalent sensitive data under another allowed
name is not detected; this is not a general privacy/cardinality enforcement layer.

## Observation Registry Functions

Sources: [MetricObservation.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/MetricObservation.java),
[InMemoryMetricRegistry.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/InMemoryMetricRegistry.java).

| Function | Behavior and boundaries |
| --- | --- |
| `MetricObservation.MetricObservation(descriptor, labels, value, observedAt)` | Requires nonnull descriptor/labels/time and finite double. Supplied labels must be declared; missing declared labels allowed. Negative values, counter decreases and arbitrary timestamps accepted. No instrument/unit behavior enforcement. |
| `InMemoryMetricRegistry.InMemoryMetricRegistry(clock)` | Requires Clock; initializes descriptor map and history list. No exporter or scheduler. |
| `InMemoryMetricRegistry.register(descriptor)` | Synchronized putIfAbsent by name. Equal descriptor registration is idempotent; conflicting kind/unit/ordered label list raises IllegalArgumentException and preserves prior descriptor. Null descriptor fails naturally. |
| `InMemoryMetricRegistry.observe(descriptor, labels, value)` | Synchronized, reentrantly registers, reads clock.instant(), constructs observation and appends. Descriptor registration remains if clock/validation later fails. No rollback, aggregation, deduplication or retention cap. |
| `InMemoryMetricRegistry.snapshot()` | Synchronized immutable copy of whole insertion-ordered history. Every snapshot copies history; no clear/drain API. |
| `InMemoryMetricRegistry.descriptors()` | Synchronized immutable map copy; LinkedHashMap insertion order is not guaranteed by returned Map.copyOf. |

Record accessors expose owned immutable values/collections; equality is content
based. Registry operations serialize on one monitor, including Clock callbacks.
Memory grows with observations and descriptors. Repeated exports of cumulative
counts must not be summed as interval deltas.

## Health Functions

Sources: [HealthState.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/HealthState.java),
[HealthStatus.java](../../modules/aether-observability-api/src/main/java/io/aetherdb/observability/api/HealthStatus.java).

| Function | Behavior and boundaries |
| --- | --- |
| `HealthState.HealthState(readsAllowed, writesAllowed)` | Stores permission booleans; does not enforce access or state transitions. |
| `HealthState.readsAllowed()` | Returns stored read permission. |
| `HealthState.writesAllowed()` | Returns stored write permission. |
| `HealthStatus.HealthStatus(state, reasonCode, observedAt, details)` | Requires nonnull state/time/details and nonblank reasonCode; Map.copyOf owns details. Validates keys with label rules, values length<=256. Blank/empty detail values allowed. Null entries fail during copying. No detail-count cap, reason vocabulary/length limit or state/reason relationship check. |
| `HealthStatus.of(state, reasonCode)` | Uses Instant.now and empty details, not registry Clock. |

| State | Reads | Writes |
| --- | --- | --- |
| STARTING, RECOVERING, UNHEALTHY | No | No |
| READ_ONLY_DEGRADED, FOLLOWER_SERVING, DRAINING | Yes | No |
| SERVING, LEADER_SERVING | Yes | Yes |

HealthStatus is a value, not an authoritative readiness check. No deployment
probe or reason-code catalog is supplied. Allowed detail values are not sanitized.

## Metered Database Functions

Sources: [MeteredAetherDatabase.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/MeteredAetherDatabase.java),
[DefaultMeteredAetherDatabase.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/DefaultMeteredAetherDatabase.java),
[DatabaseOperation.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/DatabaseOperation.java).

| Function | Behavior and measured boundary |
| --- | --- |
| `MeteredAetherDatabase.metrics()` | Abstract snapshot contract. Concrete sampling consistency is described below. |
| `MeteredAetherDatabase.resetMetrics()` | Abstract fresh-interval contract, without data mutation. |
| `DefaultMeteredAetherDatabase.DefaultMeteredAetherDatabase(delegate)` | Delegates using System.nanoTime. |
| `DefaultMeteredAetherDatabase.DefaultMeteredAetherDatabase(delegate, nanoTime)` | Requires delegate/supplier; reads clock once for initial Collector in AtomicReference. Does not open/close delegate; throwing supplier prevents construction. |
| `DefaultMeteredAetherDatabase.put(key, value)` | Times delegate.put as PUT, without extra byte copying/validation. |
| `DefaultMeteredAetherDatabase.delete(key)` | Times delegate.delete as DELETE. |
| `DefaultMeteredAetherDatabase.get(key)` | Times GET and returns same LookupResult. Missing key is not a thrown error. |
| `DefaultMeteredAetherDatabase.get(key, snapshot)` | Times snapshot lookup in same GET recorder. |
| `DefaultMeteredAetherDatabase.newSnapshot()` | Times SNAPSHOT handle creation, not lifetime/release. |
| `DefaultMeteredAetherDatabase.scan(start, end)` | Times SCAN cursor creation; cursor itself is not wrapped. |
| `DefaultMeteredAetherDatabase.scan(start, end, snapshot)` | Same SCAN recorder for snapshot cursor creation. |
| `DefaultMeteredAetherDatabase.scanAll()` | Times full-range cursor creation, not iteration. |
| `DefaultMeteredAetherDatabase.scanAll(snapshot)` | Same SCAN recorder for full-range snapshot cursor creation. |
| `DefaultMeteredAetherDatabase.write(batch)` | Times one WRITE invocation, not a count per batch mutation. |
| `DefaultMeteredAetherDatabase.write(batch, options)` | Times submission, returns unchanged WriteResult. Nonthrowing rejection/failure outcomes do not increment errors. |
| `DefaultMeteredAetherDatabase.isClosed()` | Unmetered delegate status call. |
| `DefaultMeteredAetherDatabase.close()` | Unmetered delegate close; no collector reset/close metric. |
| `DefaultMeteredAetherDatabase.metrics()` | Reads nanoTime before collector reference, snapshots six operations sequentially using elapsed since start; emits Instant.now and nonnegative Duration. No global counter lock. Clock/reset races can produce negative raw elapsed, clamped by interval/throughput calculations. |
| `DefaultMeteredAetherDatabase.resetMetrics()` | Allocates new Collector with current clock and atomically replaces reference. In-flight actions keep captured old collector and can complete into discarded interval. No wait/drain; data unchanged. |
| `DefaultMeteredAetherDatabase.measure(operation, Runnable action)` | Adapts action to Supplier returning null, delegates. |
| `DefaultMeteredAetherDatabase.measure(operation, Supplier action)` | Captures collector/start time, calls action; caught RuntimeException/Error marks failed and rethrows. Finally records max(0,end-start), including failures. Initial clock call is outside try; clock/recording exceptions in finally can replace delegate outcome. Does not track asynchronous completion. |

DatabaseOperation values PUT, DELETE, GET, SCAN, SNAPSHOT and WRITE count completed
wrapper invocations, including thrown failures. Raw-delegate calls bypass metrics.
Background flush/compaction, cursor traversal/close, and snapshot release are not
timed. Factories transfer delegate close ownership to returned decorator.

## Collector and Latency Sample Functions

Source: nested classes in [DefaultMeteredAetherDatabase.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/DefaultMeteredAetherDatabase.java).

| Function | Behavior and boundaries |
| --- | --- |
| `DefaultMeteredAetherDatabase.Collector.Collector(startedNanos)` | Creates recorder for all six operations. Each has 16,384-slot AtomicLongArray: 98,304 long slots regardless of activity. Stores interval start. |
| `DefaultMeteredAetherDatabase.Collector.operation(operation)` | Retrieves recorder from stable EnumMap, no lazy allocation/validation. |
| `DefaultMeteredAetherDatabase.OperationRecorder.record(latencyNanos, failed)` | Reserves atomic cursor position, overwrites slot position&16383, updates total/min/max, increments errors if failed, then count. Independent updates, not one observation transaction; no explicit long-overflow protection. |
| `DefaultMeteredAetherDatabase.OperationRecorder.snapshot(elapsedNanos)` | Reads count, copies min(count,16384) slots starting at zero, sorts. Throughput=count/(max(1,elapsedNanos)/1e9); average/extrema cover complete interval, percentiles bounded sample. Empty count gives zero mean/extrema/percentiles. Allocates/sorts sample on every call. |
| `DefaultMeteredAetherDatabase.OperationRecorder.percentile(sorted, quantile)` | Empty returns zero; nearest-rank ceil(quantile*length)-1, lower-clamped only. Internal .50/.95/.99 valid; arbitrary oversized quantile can index past end. |

The cyclic ring is not a random reservoir or histogram bucket distribution.
Sequentially it retains latest 16,384 latencies; count/errors/mean/extrema cover
whole interval. Concurrent reservations, slot writes and completion increments
interleave: counters/sample are approximate, slots may change during copy, and
delayed writers can overwrite newer ring-cycle values. The snapshot is not
globally atomic or guaranteed exact recent-completion ordering. Sequential
operation snapshots also span time.

## Snapshot Value Functions

Sources: [DatabaseMetrics.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/DatabaseMetrics.java),
[OperationMetrics.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/OperationMetrics.java).

| Function | Behavior and boundaries |
| --- | --- |
| `DatabaseMetrics.DatabaseMetrics(collectedAt, collectionInterval, operations)` | Requires nonnull inputs; copies into EnumMap and wraps unmodifiable. Negative duration and null metric values not rejected. Empty ordinary map cannot infer enum key type and can raise IllegalArgumentException; empty EnumMap retains type. |
| `DatabaseMetrics.operation(operation)` | Requires enum; returns mapping or newly allocated zero metrics when absent. Present null mapping returns null, not default. |
| `OperationMetrics.errorRate()` | Zero count returns 0.0, otherwise double errors/count. No range validation. |
| `OperationMetrics.p99LatencyMillis()` | Divides nanoseconds by 1e6, without clamping/validation. |

OperationMetrics has an implicit constructor without validation: caller-supplied
negative counts, nonfinite rates or unordered percentiles are accepted. Generated
accessors expose immutable values; DatabaseMetrics.operations is unmodifiable.
Record equality compares contents. Collector behavior is narrower than record
acceptance; the records alone do not enforce telemetry invariants.

## Exporter Functions

Source: [DatabaseMetricExporter.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/DatabaseMetricExporter.java).

| Function | Behavior and boundaries |
| --- | --- |
| `DatabaseMetricExporter.DatabaseMetricExporter()` | Private empty utility constructor. |
| `DatabaseMetricExporter.export(metrics, registry)` | Requires inputs; for each operation emits count, errors and p50/p95/p99 seconds, using Locale.ROOT lowercase operation label. Missing metrics yield zero. Observe calls are independently synchronized; export failure leaves earlier observations/descriptors. Uses registry clock, not metrics.collectedAt. |
| `DatabaseMetricExporter.observeLatency(registry, operation, quantile, nanos)` | Emits duration with nanos/1e9 double conversion. No separate negative-latency guard. |

| Descriptor | Kind/unit/labels | Actual values |
| --- | --- | --- |
| OPERATIONS_TOTAL: aether_db_operations_total | COUNTER / 1 / operation,result | Cumulative completed count, result="completed" includes thrown failures. |
| OPERATION_ERRORS_TOTAL: aether_db_operation_errors_total | COUNTER / 1 / operation | Cumulative thrown-error count, not all unsuccessful WriteResult outcomes. |
| OPERATION_DURATION_SECONDS: aether_db_operation_duration_seconds | HISTOGRAM / s / operation,quantile | Three precomputed percentiles p50/p95/p99, not buckets/raw durations. |

Complete export appends 30 observations: five per operation. No average/extrema,
throughput, interval or collection timestamp export. Reset can decrease later
counters; registry does not enforce monotonicity/compute deltas. Long-to-double
counts lose integer precision beyond 2^53. Downstream adapters must preserve
percentile semantics rather than assume HISTOGRAM means bucket counts.

## Verification Scope

[ObservabilityApiTest.java](../../modules/aether-observability-api/src/test/java/io/aetherdb/observability/api/ObservabilityApiTest.java)
has six tests: selected health permissions, name/label rejection, valid details,
fixed-clock observation and undeclared-label rejection.
[MeteredAetherDatabaseTest.java](../../modules/aether-engine/src/test/java/io/aetherdb/engine/MeteredAetherDatabaseTest.java)
has three: counts/errors/basic timing, reset without data loss, persistent reopen.
[DatabaseMetricExporterTest.java](../../modules/aether-engine/src/test/java/io/aetherdb/engine/DatabaseMetricExporterTest.java)
has one: names, selected labels and GET count export.

These do not cover ring wrap/concurrency, reset races, nonthrowing write outcomes,
clock-failure masking, registry conflicts/partial mutation/retention, malformed
external snapshots or every exported conversion.

Fresh selected run on 2026-10-09: six observability API tests passed; engine
exporter and two in-memory metering tests passed. Persistent close/reopen test
failed at its initial put with RESOURCE_EXHAUSTED / DISK_SPACE admission pressure,
before close/reopen assertions. That run does not verify persistent reopen.
No disk-pressure policy override or runtime change was added for documentation.

```powershell
.\gradlew.bat --no-daemon :modules:aether-observability-api:test --rerun
.\gradlew.bat --no-daemon :modules:aether-engine:test --tests '*MeteredAetherDatabaseTest' --tests '*DatabaseMetricExporterTest' --rerun
.venv\Scripts\python.exe -m pytest scripts/tests/test_contributor_docs.py -q
```

Compiler inventories guard declaration omissions, not semantic correctness or
operational monitoring deployment. Keep training-cache/JFR and research GPU/process
measurements distinct from these local byte-database metrics.
