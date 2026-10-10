# Cache Request and JFR Diagnostic Functions

[Function index](FUNCTION-INDEX.md) | [Population diagnostics](POPULATION-PROFILE-FUNCTIONS.md) | [Research tooling](EXPERIMENTS-AND-PROFILING.md)

Sources: [profile_cache_requests.py](../../scripts/profile_cache_requests.py) and
[profile_cache_jfr.py](../../scripts/profile_cache_jfr.py). This reference covers
all **7 explicit declarations**, including nested key, invocation, command and
workload functions. The JFR module's CLI parser is module-level code, not an
additional main function.

## Two Diagnostic Architectures

```text
request profile: one daemon / one growing store
  -> prepublish warm keys
  -> randomized windows: hit / miss / publish x untraced / traced
  -> fresh client connection per arm; three excluded warmup calls
  -> wall and Python CPU timing; trace JSONL; paired window ratios

JFR profile: three sequential fresh-daemon cases
  -> build recording configuration
  -> warmup -> unrecorded baseline -> JFR.start -> workload -> JFR.stop
  -> recording hash / event export / partial runs inventory / environment
```

Neither script loads OCT5K tensors or trains a model. These synthetic request
diagnostics do not measure the longitudinal ML lifecycle or compare mmap/MONAI.
They do not independently certify frozen source identity or campaign completeness.
Request windows share a store and daemon; JFR cases use separate stores and
daemons. OS page-cache conditions remain uncontrolled in both.

## Request Profiler Functions

| Function | Behavior and boundaries |
| --- | --- |
| `profile(output, *, windows=30, operations=10, payload_bytes=4096, batch_size=1, seed=42, server_trace=False)` | Requires positive counts, batch <=4096 and batch_size*(payload_bytes+256) below 64 MiB. Creates a new output directory, deterministic payload and fingerprint, prepublishes warm keys, then executes six randomized arms per window. Records API/result-check/trace-collection wall time and Python process CPU time, writes traced records outside timing, and computes traced/untraced wall ratios paired by case/window. Saves profile.json with environment and scope caveats. Returns no report object. |
| `profile.keys(prefix)` | Builds batch_size CacheKeys in namespace request-profile using prefix-index and the shared request-profile-v1 transformation fingerprint. Warm/missing keys are reused; publication prefixes include window, tracing state and operation index to avoid deliberate overwrite. Does not encode payloads or check identities against source files. |
| `profile.invoke(index)` | Warm-hit calls get_many(warm); miss calls get_many(missing); publish constructs fresh keys and a payload pair list, then calls put_many. Captures the current arm/client/window. Warmup publication indices -3/-2/-1 differ from measured nonnegative indices, so warmup grows the store without consuming measured keys. |
| `main()` | Parses output/count/payload/batch/seed/server-trace arguments and forwards them to profile. CLI integers are validated by profile; it does not accept a supplied argv parameter. Server tracing is requested only on instrumented connections, requiring the appropriate protocol support. |

Payload generation and order randomization use separate Random(seed) instances:
payload generation does not advance the arm-order generator. Each client gets
three warmup invocations before timing. traces.clear removes their trace records,
but protocol_metrics still includes those three operations. Connection setup,
daemon startup, prepopulation, warmup and JSONL serialization are excluded from
window wall time. Warm read equality and empty miss results are checked inside
the measured loop; publication has no separate readback check.

The two clocks have different meanings: process_time_ns measures Python process
CPU, not Java CPU or wall time; perf_counter_ns includes API waits. The CPU clock
is stopped before the wall clock. requestMeanMs is window wall divided by API call
count, not a distribution of independently timed request latencies. Batch samples
are not the same unit as requests.

trace_errors rejects trace-sink failures, but this script does **not** require a
specific trace count, reject attempt_failed/retry events, reconcile completed
server trace IDs or enforce operation counts. Those stronger gates belong to the
population diagnostic. Embedded server stages remain inclusive correlations,
not resolved scheduler/transport attribution. stageAComplete stays false even
when server_trace is enabled.

PairedWindowWallRatios are descriptive overhead observations. Randomized arm order
does not make windows independent process repetitions: all six arms and all
windows share one daemon/store, and publish operations continually enlarge it.
No ratio-denominator guard is added. A failing arm stops execution; the context
managers close client/daemon/trace file, but output and store remain and no final
profile.json is guaranteed. Existing output paths are rejected, not resumed.

## JFR Profiler Functions

| Function | Behavior and boundaries |
| --- | --- |
| `run(output, seconds=30, baseline_seconds=15, warmup_seconds=5)` | Requires positive durations, creates a new directory, locates jcmd/jfr, and derives lib/jfr/profile.jfc from the resolved jfr executable. Configures sampling thresholds, runs three cases with fresh 128 MiB initial/512 MiB max-heap daemons, captures baseline then JFR workload, checks a nonempty recording and hashes it. After daemon shutdown exports summary/events and writes cumulative runs.json after each successful case; environment.json is written only after all cases. |
| `run.command(args, log)` | Runs a checked subprocess, captures stdout/stderr with UTF-8 replacement, writes their concatenation to the supplied log and returns stdout. No timeout. If the subprocess exits unsuccessfully, check=True raises before the log write, so that log is not guaranteed to preserve failure output. |
| `run.workload(duration)` | Repeatedly performs the current case until elapsed wall reaches duration. Publish allocates fresh sequence-based keys and submits a batch; warm-single uses get and validates bytes; warm-batch uses get_many and validates the mapping. Counts successful calls and derives samples/requests/payload-byte throughput from actual elapsed wall. sequence persists across warmup, baseline and recording, preventing publication-key reuse between phases. |

Cases are warm-batch-4k (32 x 4096-byte values), publish-batch-4k (4 x 4096-byte
values) and warm-single-1m (one 1 MiB value). Payloads come from seed 42 and keys use
the jfr-diagnostic-v1 fingerprint. Non-publish cases prepopulate before warmup.
The publication baseline and recorded phase see progressively larger stores;
baseline always precedes recording. Their throughput ratio is **not** a randomized
instrumentation-overhead estimate. No persistent cross-version service is used.

The timed loop includes public API calls, key/list construction for publish and
value checks for reads. A call can overrun the requested duration; wallSeconds
reports actual elapsed rather than the nominal duration. requests is API calls,
samples is calls*batch_size and payloadBytesPerSecond is logical value traffic,
not measured wire bandwidth, disk write amplification or total allocations.

Configuration requests 2 ms execution/native samples, allocation throttle 300/s,
0 ms file-force threshold, 1 ms monitor threshold and 5 ms park/socket thresholds.
It inherits other settings from the stock profile. Selected event export includes
CPU, allocation, GC/pause, file/force, monitor/park, socket and DataLoss events with
stack depth 64. Exporting DataLoss does not mean this runner rejects data loss or
validates statistical representativeness; inspect the recording/analyzer.

JFR.start occurs after baseline. Once start succeeds, stop runs in finally even
if workload fails; a stop error can replace the original workload error. Start
failure occurs before that finally. A nonempty file check does not validate event
coverage. Reports are appended before post-daemon export, but runs.json is updated
only after summary and event export succeed. Events export writes directly to its
destination, so failure can leave a partial JSON file. No resume, subprocess timeout
or cleanup of diagnostic stores is implemented.

## Coverage and Evidence

AST checks require every qualified declaration from both source files. Focused
contract tests exercise validation and full request-report generation with a fake
daemon/client, including excluded warmup trace records and included warmup protocol
counters. These doubles establish control flow and report boundaries, not real
Java timing, GPU performance or valid JFR recordings.

Combined file coverage is **7/7 declarations (100%; 0% remaining)**. Other profilers
and repository subsystems remain in the wider documentation goal.
