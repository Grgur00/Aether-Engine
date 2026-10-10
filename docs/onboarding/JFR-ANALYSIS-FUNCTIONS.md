# Cache and Bulk JFR Analysis Functions

[Function index](FUNCTION-INDEX.md) | [Cache recording drivers](CACHE-PROFILE-FUNCTIONS.md) | [Research tooling](EXPERIMENTS-AND-PROFILING.md)

Sources: [analyze_cache_jfr.py](../../scripts/analyze_cache_jfr.py) and
[bulk_jfr_analyze.py](../../scripts/bulk_jfr_analyze.py).
All **6 explicit declarations** are covered here. These functions consume exported
JFR JSON and receipts; they do not run a workload or perform an optimization.

## Evidence and Attribution Architecture

```text
cache: runs.json -> verify recording SHA-256 -> adjacent events.json
  -> whole-export event counts / Java and native leaf samples
  -> raw allocation weights + sensitivity view excluding first sample per thread
  -> per-recording analysis-summary.json

bulk: exported events + one candidate or three control/profile/control receipts
  -> unique successful population marker and sample-count agreement
  -> interval-overlap events / leaf stacks / weighted classes / phase association
  -> candidate-only: unique verifier marker, verifier-thread attribution
  -> jfr-analysis.json + jfr-summary.md
```

The cache analyzer hashes the recording, but does not hash or regenerate its
adjacent events.json to establish that the export came from that recording.
The bulk analyzer does not verify recording/export hashes itself. Their evidence
must be bound to actual recordings and frozen source by the caller/campaign.
Neither analyzer rejects a recording merely because DataLoss events exist.

## Cache Analyzer Functions

| Function | Behavior and boundaries |
| --- | --- |
| `summarize(folder, case)` | Resolves case.recording under folder, checks its bytes against case.sha256, and parses adjacent events.json. Counts every exported event. Execution samples count leaf and nearest io/aetherdb/ frame; native samples count leaf separately. Allocation weights are attributed raw to nearest Aether frame, then each thread's first sample is excluded from the sensitivity view of leaf/frame/class weights. Sums supported event durations, carries baseline/recorded reports unchanged, writes analysis-summary.json and prints verification/sample count. Returns None. |
| `main(folder)` | Reads runs.json and calls summarize for every cases entry in order. Does not validate schema, required case count, event-export provenance, homogeneous configuration or timing ratios. Failure stops later cases, leaving earlier summaries intact. CLI parser is module-level. |

Method names retain JVM slash-style class names, assembled as type.name + '.' +
method.name. The first frame is the leaf; nearest Aether attribution is the first
matching frame from leaf upward, not every Aether frame in the stack. Missing
stacks use <none>. Top lists retain at most 12 entries; totals cannot be recovered
by summing truncated lists alone.

First-allocation tracking keys by javaThreadId, falling back to osThreadId then
the shared string unknown. It uses eventThread, not sampledThread. Input order
defines first; events are not sorted by timestamp. Threads with no identifying
field collapse into one bucket. The excluded sample's weight/time/leaf/thread
name are retained, and raw frame weights still include it. Exclusion is a startup
sensitivity analysis, **not** a correction that proves exact steady-state bytes.

All events in the export are processed: no client-workload interval or thread
filter is applied. The JFR recording includes start/stop margins. Durations must
have PT...S form and a float-parsable interior; hours/minutes are not supported by
this parser. Counter fields can overlap across event kinds and threads. Native
samples can include blocked threads and are not automatically CPU observations.
Malformed fields or unsupported durations raise before the summary write.
The recording is read entirely for hashing and the export is loaded into memory.

## Bulk Analyzer Functions

| Function | Behavior and boundaries |
| --- | --- |
| `seconds(value)` | Converts numeric inputs to float; otherwise parses unsigned optional hours/minutes/seconds components in PT notation and returns seconds. Falsey strings/None default to PT0S; bare PT also matches as zero. Unsupported forms raise ValueError. Numeric booleans follow Python's int behavior; negative numeric values are not rejected here. |
| `instant(value)` | Replaces Z with +00:00, parses datetime.fromisoformat and returns timestamp. Explicit offsets identify absolute instants; naive timestamps depend on host timezone. Does not require UTC or enforce timestamp ordering. |
| `verification_detail(events)` | Requires exactly one SSTABLE_VERIFY BulkPhase across the entire export, not just within the population marker. Builds its time interval and eventThread javaThreadId. Includes overlapping events on that thread (sampledThread preferred to eventThread), plus JVM-wide GC/pause/DataLoss events regardless of thread. Counts inclusive unique method names per execution/native sample, weighted allocation classes/full stacks, clipped overlap duration and full recorded file-byte fields. Returns detail with limitations; does not write files. |
| `analyze(events_file, output, reports)` | Loads all exported events, requires exactly one successful BulkPopulation and one or three reports, then compares its artifactCount with candidate/profiled receipt samples. Clips event durations to the population interval, counts Java/native leaf samples and first 16-frame stacks together, accumulates allocation classes, phase durations, GC observations and recorded file bytes. Three reports use middle profile versus mean control population timing; one report adds verification_detail. Writes JSON/Markdown and returns details. |

Overlap tests include events exactly touching either boundary; their clipped
duration may be zero while count/weight/recorded bytes remain included. File bytes
and allocation weights are not prorated to the overlap fraction. Negative or
reversed intervals are not separately validated. Each event's startTime is parsed
before filtering, so a malformed timestamp outside the intended interval can
still fail analysis.

verification_detail updates a set of all stack names for each sample: recursion
does not count the same method twice within one sample, but method counts overlap
across methods. Execution and native samples are pooled here. Allocation stacks
retain 40 top entries, with 15 printed in Markdown. JVM-wide GC overlap is context,
not proof that the verifier caused GC. Other-thread allocations are excluded;
worker-thread activity outside the marker thread is not attributed to verification.

## Population Phases and Ratios

Whole-population analysis is **not thread-filtered**. Leaf method counts pool
execution/native samples with stacks; percentages use that pool as denominator,
not all exported events or exact CPU time. Allocations count every overlapping
allocation event's class weight, without first-sample exclusion. Top methods,
classes and stacks are truncated independently.

For phase-associated method samples, sampledThread selects same-thread markers
active at the sample instant. The shortest-duration active marker wins as an
innermost heuristic; eventThread is not a fallback on this path. Phase elapsed
durations remain inclusive and can nest. The marker list spans the full export.
Sorting/partition traversal, integrity/admission and builder phases have different
semantic boundaries; they are not interchangeable with SHA-only time.

The three-report ratio divides the profiled population milliseconds by the mean
of the two controls. Only the selected profile's sample count is compared against
the marker; control identity, sample count and correctness are not independently
checked here. There is no zero-control-time guard. Fixed control/profile/control
ordering is descriptive and does not remove machine drift. Candidate-only mode
has no overhead ratio; its generated text retains the historical failed 750 ms
gate statement, not a freshly computed decision about current performance.

Estimated allocation/payload ratio uses sum of weighted class bytes divided by
max(payloadBytes,1). GC heap high-water is max of observed GCHeapSummary values,
not continuously sampled RSS/heap peak. GC pause overlap sum may overlap other
events. Missing thresholded file/socket/lock events do not establish zero cost.
Offline transport is a pipe; input reads can reflect waiting on preprocessing.

The output directory must already exist. JSON is written before Markdown, directly
to final paths with no atomic pair publication: a later write failure can leave
one updated output. Existing outputs are overwritten. Candidate verifier failure
occurs before either write. The analyzer records DataLoss counts but does not
fail the result, select an optimization or establish recoverable wall-time gains.

## Coverage and Evidence

AST tests check all six qualified declarations. Existing bulk JFR tests check
duration parsing, unique successful population and verifier-thread allocation
filtering. Focused contracts additionally cover boundary-touching events, JVM-wide
GC inclusion, first-sample sensitivity and recording-hash failure. These use
synthetic exports; opt-in live JFR tests are separate evidence.

These two files are **6/6 declarations covered (100%; 0% remaining)**. Recording
drivers, other profilers and wider repository references have separate coverage.
