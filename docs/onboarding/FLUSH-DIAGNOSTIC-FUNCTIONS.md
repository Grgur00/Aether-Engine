# Flush and Compaction Diagnostic Functions

[Persistent internals](PERSISTENT-INTERNALS.md) | [Observability](OBSERVABILITY-FUNCTIONS.md) | [Training protocol traces](TRAINING-PROTOCOL-FUNCTIONS.md)

Source: [FlushDiagnostics.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/FlushDiagnostics.java).
All 46 explicit declarations are covered, including nested collectors/events and
both Collector constructor overloads. This is opt-in request-owned evidence,
not a change in storage policy, a durable journal or a global metrics registry.

## Ownership and Call Flow

```text
request creates collector
  -> attaches to current thread
  -> queued write retains collector
  -> leader attaches for processing
  -> records stages, flushes, files
  -> restores previous attachment
background job creates own collector
  -> completion evidence owns timings
```

`ACTIVE` is ThreadLocal, not inheritable context. A collector can be explicitly
carried to a group-commit leader, but does not automatically follow arbitrary
async work. Collections/fields are unsynchronized and nonvolatile; attach does
not make simultaneous mutation/read safe. Callers must maintain exclusive access
and proper publication when moving evidence between threads. List snapshots are
shallow: contained Event/Compaction/TableFinish objects can still change.

## Static Context and Write Measurements

| Function | Behavior and Boundary |
| --- | --- |
| `FlushDiagnostics.FlushDiagnostics()` | Private empty constructor; static utility only. |
| `FlushDiagnostics.current()` | Returns current thread's attached collector, or null. |
| `FlushDiagnostics.attach(collector)` | Returns previous attachment, sets new collector or removes ThreadLocal on null. Does not validate identity, reset data or scope restoration automatically. |
| `FlushDiagnostics.writeStart()` | Returns nanoTime when a collector is active, otherwise zero. It does not retain the collector identity that started measurement. |
| `FlushDiagnostics.writeEnd(stage, started)` | If currently attached, sums nanoTime minus started into writeStages under stage; inactive call is no-op. Assumes attachment is consistent with writeStart; passing zero/foreign start can produce meaningless duration. |
| `FlushDiagnostics.writeCount(counter, value)` | If active, sums value under counter; no sign/range validation, saturation or synchronization. Inactive call is no-op. |
| `FlushDiagnostics.begin(cause, counters)` | Returns null if inactive; otherwise constructs Event with copied counters, appends it and returns it. No storage operation itself. |
| `FlushDiagnostics.beginCompaction(flush)` | Returns null if inactive; otherwise creates/appends Compaction with flush's index, or -1 for absent/unfound flush. No selection or compaction execution. |
| `FlushDiagnostics.beginTableFinish(fileNumber, flush, compaction)` | Returns null if inactive; otherwise creates SSTableFinishTrace and appends TableFinish linking file/flush/compaction indexes. Uses compaction's flush index when supplied; absent/unfound links are -1. Returns timing for builder to update. |

`indexOf` searches the active collector's lists; these objects have ordinary
identity equality. An event from a different collector can yield -1. These links
are positions in evidence arrays, not persistent table identifiers or validated
foreign keys. Callers generally restore attach's returned value in finally to
avoid carrying one request's context into another on a reused thread.

## Collector Functions

| Function | Behavior and Boundary |
| --- | --- |
| `FlushDiagnostics.Collector.Collector()` | Delegates to trace-ID constructor with empty string. |
| `FlushDiagnostics.Collector.Collector(traceId)` | Stores trace ID without validation or normalization; initializes empty evidence collections. Null is not rejected here. |
| `FlushDiagnostics.Collector.traceId()` | Returns supplied ID; not generated or globally unique by this class. |
| `FlushDiagnostics.Collector.compactionScheduled()` | Returns scheduling flag, initially false; not job completion or current worker liveness. |
| `FlushDiagnostics.Collector.compactionDebtBytes()` | Returns last debt recorded by scheduled, initially zero. Not a live recomputation. |
| `FlushDiagnostics.Collector.scheduled(debt)` | Sets scheduling flag true and replaces debt value. Does not enqueue a job or increment scheduling count. |
| `FlushDiagnostics.Collector.writeDiagnostics()` | Returns immutable map with copied `stagesNs` and `counters` maps. Not an atomic snapshot against concurrent mutation. |
| `FlushDiagnostics.Collector.events()` | Returns immutable shallow copy of event list. Events themselves remain mutable through internal methods. |
| `FlushDiagnostics.Collector.compactions()` | Returns immutable shallow copy of compaction list. |
| `FlushDiagnostics.Collector.tableFinishes()` | Returns immutable shallow copy of table-finish list; referenced timing objects can still change. |
| `FlushDiagnostics.Collector.backgroundTimings()` | Builds compaction and SSTable-finish summaries from current objects, copying stage maps through accessors. Does not include every link, counter or trace field. |

backgroundTimings returns `compactions` records with completed, selectionCount,
totalNs and stagesNs; `sstableFinishes` records contain fileNumber, completed,
entryCount, fileBytes, totalNs and stagesNs. It omits flushIndex, compactionIndex,
write diagnostics, scheduling debt and trace ID. Consumers requiring those fields
must use the corresponding accessors/request serializer, not assume all forms
of diagnostic export share one schema.

Map.copyOf rejects null entries; invalid caller stages/counters or constructor
counters can therefore fail later snapshot creation. No defensive limits constrain
the number of events or distinct stage/counter names. Collectors are diagnostic
objects whose lifetime should match their owner, not unbounded daemon histories.

## Flush Event Functions

| Function | Behavior and Boundary |
| --- | --- |
| `FlushDiagnostics.Event.Event(cause, counters)` | Captures initial nanoTime and copies counters; stores cause as supplied. Constructor is private. |
| `FlushDiagnostics.Event.cause()` | Returns cause string; no enum validation. |
| `FlushDiagnostics.Event.counters()` | Returns immutable counter map captured at construction, not live engine counters. |
| `FlushDiagnostics.Event.stagesNs()` | Copies accumulated stage map. |
| `FlushDiagnostics.Event.totalNs()` | Returns recorded total, initially zero before finish. Not continuously elapsed time. |
| `FlushDiagnostics.Event.completed()` | Returns finish's success flag, initially false; false alone does not distinguish unfinished from failed. |
| `FlushDiagnostics.Event.stage(name)` | Charges time since previous boundary to the supplied name, sums repeated names and advances previous timestamp. Label describes preceding interval. |
| `FlushDiagnostics.Event.finish(success)` | Charges last interval as other, stores total elapsed and success flag. No single-finish guard; repeated calls accumulate additional time. |

## Compaction Functions

| Function | Behavior and Boundary |
| --- | --- |
| `FlushDiagnostics.Compaction.Compaction(flushIndex)` | Stores request-array link and initializes active stage to selection with starting timestamp. No index validation. |
| `FlushDiagnostics.Compaction.flushIndex()` | Returns linked flush index; -1 also represents write-admission compaction. |
| `FlushDiagnostics.Compaction.stagesNs()` | Copies accumulated exclusive stage map. |
| `FlushDiagnostics.Compaction.totalNs()` | Returns stored total, initially zero. |
| `FlushDiagnostics.Compaction.completed()` | Returns supplied finish success flag, not inferred manifest durability. |
| `FlushDiagnostics.Compaction.selectionCount()` | Returns incremented selection count. |
| `FlushDiagnostics.Compaction.selected()` | Increments count; does not select files or validate a plan. |
| `FlushDiagnostics.Compaction.enter(nextStage)` | Charges preceding interval to old activeStage, advances timestamp and changes active label. Repeated labels accumulate. |
| `FlushDiagnostics.Compaction.finish(success)` | Calls enter(other) to charge the last active stage, then stores total and success. No freeze/idempotence guard. |

Unlike Event.stage, Compaction.enter names the **next** interval. Reading these
two mechanisms as identical can misattribute elapsed time. Within one compaction
call, stages partition measured intervals across selections/output files, but
its total overlaps child table-finish measurements and possibly parent request
time. Do not add all totals and stages to estimate lifecycle wall time.

## Table Finish Functions

| Function | Behavior and Boundary |
| --- | --- |
| `FlushDiagnostics.TableFinish.TableFinish(fileNumber, flushIndex, compactionIndex, timing)` | Stores file and request-array links plus shared SSTableFinishTrace. Does not copy trace, validate indexes or check file existence. |
| `FlushDiagnostics.TableFinish.fileNumber()` | Returns recorded output file number. |
| `FlushDiagnostics.TableFinish.flushIndex()` | Returns linked request flush position or -1. |
| `FlushDiagnostics.TableFinish.compactionIndex()` | Returns linked request compaction position or -1. |
| `FlushDiagnostics.TableFinish.completed()` | Delegates to timing.completed. |
| `FlushDiagnostics.TableFinish.entryCount()` | Delegates to timing.entryCount. |
| `FlushDiagnostics.TableFinish.fileBytes()` | Delegates to timing.fileBytes. |
| `FlushDiagnostics.TableFinish.totalNs()` | Delegates to timing.totalNs. |
| `FlushDiagnostics.TableFinish.stagesNs()` | Delegates to timing.stagesNs; trace defines copy/aggregation behavior. |

SSTable completion is evidence of the traced builder call, not by itself proof
of manifest publication or recovery visibility. Follow SSTable construction and
persistent install references for those distinct durability steps.

## Runtime Integration and Evidence Limits

PersistentAetherDatabase records foreground admission, WAL logical/fragment
encoding, append, memtable apply and force under explicitly attached collectors.
Queued write requests capture current collector; leader processing restores its
prior context. A group force is attributed under the first prepared request's
collector rather than duplicating the entire force duration to every participant.
Background compaction uses its own collector for traced requests and exports
completion timings; scheduling a job is distinct from completing it.

TrainingCacheRequestTrace owns a collector, attaches/restores it and serializes
write stages plus flush/compaction/file arrays. Training-cache hashing also adds
admission SHA-256 counters when enabled. These layers can add diagnostic overhead
and overlap timing categories; disabled collectors avoid timer/counter collection
through the no-op paths, not all unrelated work in the engine.

FlushDiagnosticsTest exercises memtable-capacity and WAL-rollover flush causes,
pre-flush capacity counters, nonnegative stage durations, stage-total equality,
payload reads before and after reopening, and collector restoration. The test
also observes the final OTHER flush on close. It does not establish thread safety
or cover all compaction and table-finish links. These source assertions were read
for this reference; the engine tests were not executed in this documentation batch.

Compiler-tree documentation checks match all 46 declarations including overload
multiplicity. They do not verify cross-thread races, repeated finish calls,
foreign-context indexes or every actual export. Read implementation/tests before
making timing, storage policy, GPU or durability claims from this diagnostic data.
