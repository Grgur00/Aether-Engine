# Benchmark Utilization Sampling and Process Metric Functions

[Function index](FUNCTION-INDEX.md) | [Model and training](BENCHMARK-TRAINING-FUNCTIONS.md) | [Backend ownership](BENCHMARK-BACKEND-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This reference covers all seven GpuUtilizationSampler methods and eight process/
parsing helpers: **15 explicit declarations**. It is not a complete benchmark
reference. Report aggregation and validity policy remain separate functions.

## Observation Architecture

```text
run_backend -> sampler.start -> synchronous command query (before training wall)
                         -> daemon thread: command query -> append -> interval wait
training window ends -> iterator/resource cleanup -> sampler.stop -> bounded join
                    -> sample report -> backend summary

process snapshot before/after -> process CPU time + lifetime peak RSS + /proc I/O
                             -> clamped deltas + host-core-normalized CPU percent
```

The sampler tries NVIDIA, then AMD, then ROCm command-line tools. It does not
connect to the training model, CUDA events or a per-process accelerator counter.
Queries include devices visible to the command without filtering to gpu_count or
the model's selected device IDs. Sample averages across devices are not proof of
the selected devices' utilization or of training saturation.

start captures a synchronous sample before the backend training timer, and the
thread can sample immediately afterward. stop occurs during backend cleanup.
Thus samples do not precisely partition the measured training interval. The
configured interval is a wait after query completion, not a fixed-rate deadline;
query time, fallback attempts and scheduling add to the effective period.

## Sampler Methods

| Function | Behavior and boundaries |
| --- | --- |
| `GpuUtilizationSampler.__init__(self, interval_ms)` | Converts milliseconds to seconds, initializes an empty sample list, source/error fields, stop Event and absent thread. Does not validate finite values, probe a tool or start work. |
| `GpuUtilizationSampler.start(self)` | Nonpositive interval records disabled and returns. Otherwise probes synchronously, appends a non-null sample, and returns without a thread if no sample/source was found. If a source is known, starts a named daemon thread. Does not clear prior samples/error/stop state or prevent repeated starts; treat the instance as a one-shot lifecycle. |
| `GpuUtilizationSampler.stop(self)` | Sets stop Event and joins the thread for max(1 second,4*interval), if present. Returns source, error, interval, the original sample-list object and SAMPLED when any sample exists, otherwise UNAVAILABLE. Does not verify the thread has terminated after timeout, take a deep snapshot, add a final sample or report disabled as a distinct status. A still-running query may later append to the returned list. |
| `GpuUtilizationSampler._run(self)` | Until stopped, queries once and appends a non-null result. No result with source None stops the loop; a previously known source allows retrying after failures. Waits on the stop Event between queries. Unexpected exceptions outside _sample_once handling can end the daemon thread without a structured report. |
| `GpuUtilizationSampler._sample_once(self)` | Sequentially runs nvidia-smi utilization CSV, amd-smi usage JSON, then rocm-smi usage JSON with captured text and a two-second timeout per command. Missing executables are skipped; other command errors/exit failures record an error and try fallback. NVIDIA requires some parsed numeric data. AMD/ROCm require valid JSON but may yield a non-null sample with both metrics None. Sets source on success and returns sample or None after all failures. Does not clear an earlier error or reset source when later probes fail, and retries NVIDIA first on every call. |
| `GpuUtilizationSampler._sample_from_payload(self, payload)` | Flattens nested JSON into lowercase numeric-key pairs; selects GPU/memory values with substring heuristics and averages each group. Always returns a timestamped mapping, even when neither group has values. Does not validate percent ranges/units, distinguish allocated bytes from utilization, choose one device or prevent unrelated keys from matching. |
| `GpuUtilizationSampler._sample_from_nvidia_smi(self, text)` | Splits lines by commas, needs at least two fields, independently parses GPU/memory values and averages all usable values in each column. Ignores extra columns and returns None only when both groups are empty. Timestamps after parsing. Partial rows may contribute to only one metric; group counts can differ. No range, device-ID or finite-value check. |

SAMPLED means a sample mapping was appended, not that GPU and memory percentages
are both valid. A successful fallback may retain a prior failure string. If tool
availability changes, source can change while earlier samples remain; the final
source label is not necessarily the source of every sample.

Memory utilization from the NVIDIA command is the tool's memory-utilization
metric, not automatically fraction of VRAM allocated. The generic JSON matcher
also accepts keys containing both vram and allocated: values can be bytes or
another unit even though the output key ends in Percent. Inspect tool output and
source before interpreting this field as a percentage. No unit conversion occurs.

## Parsing Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `flatten_json(value, prefix="")` | Recursively traverses dict/list containers, joining dictionary names with dots and list indices in brackets, and yields lowercase leaf paths plus numeric_value output. Non-numeric leaves still yield None. List/device indices are flattened, not preserved as separate metric records; no schema/depth/cycle guard. |
| `numeric_value(value)` | Numeric int/float values convert directly to float, including bool and nonfinite numbers. Strings return the first regex match of optional minus sign, digits and optional decimal part. Thus units are ignored, scientific notation is not interpreted and .5 becomes 5. Other inputs/no match return None. This is permissive extraction, not a strict numeric/percent parser. |
| `is_gpu_utilization_key(key)` | Removes spaces, underscores and hyphens and matches gpuuse, gpuusage, gpuutilization, gfxactivity or gpubusypercent as substrings. Does not lowercase itself; normal flattened caller provides lowercase keys. No unit or semantic schema validation. |
| `is_memory_utilization_key(key)` | Uses similar normalization for memoryuse, memoryusage, memoryutilization or vramuse, or accepts any key containing both vram and allocated. Does not lowercase itself or distinguish counters/capacity/allocation from activity. |

## Process Metric Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `process_metrics_snapshot()` | Captures time.process_time, rss_peak_bytes, proc_self_io and os.cpu_count (fallback one). CPU time is for this process, not the Java daemon or loader children. RSS and I/O use helper availability rules. Snapshot fields are gathered sequentially, not atomically. |
| `process_metrics_delta(start, end, wall_ms)` | Clamps CPU-time delta to nonnegative and wall seconds to at least 1e-9; divides CPU time by wall and ending host CPU count to report utilization. Reports max non-null start/end RSS peaks, not a memory delta. If both I/O mappings are truthy, computes clamped read_bytes/write_bytes/cancelled_write_bytes deltas, treating absent keys as zero; otherwise these fields are None. Chooses a descriptive source label. No cap at 100%, process identity check, reset detection or independent wall-window validation. |
| `rss_peak_bytes()` | Imports resource and reads self ru_maxrss; ordinary import/query failures return None. Darwin is assumed bytes, other platforms multiply by 1024 (Linux KiB). Reports process-lifetime high water, not interval current RSS or sum of parent/child/Java memory. Other platforms' unit conventions are not separately detected. |
| `proc_self_io()` | Reads /proc/self/io if the path exists, parses each colon-separated value as int and returns a mapping. Missing path or ordinary read/parse errors return None for the whole snapshot. No per-field fallback. Path.exists is outside the parsing try. Linux process counters are not logical artifact bytes, mmap-read call counts or device-wide disk traffic. |

Host-core-normalized CPU utilization differs from the common one-core-equals-100%
convention and can be low on a many-core host despite one busy thread. Cancelled
write counters can decrease; clamping hides that change. Nonempty I/O snapshots
with missing read/write fields report zero, not unavailable. Lifetime peak RSS
includes earlier workload/setup history even when the current backend allocates
little memory. Separate resource helpers used by run_backend have their own
parent/worker/Java scopes; these process metrics are not a substitute.

## Verification and Remaining Coverage

Qualified AST checks cover the seven sampler methods and eight helpers. Local
tests inject command outputs, missing/failing tools, partial CSV/JSON, stop/join
behavior, proc fixtures and platform resource values. No vendor command, GPU
job or performance experiment is required to verify these parsing/lifecycle
contracts. No implementation or frozen experiment is changed.

The function index links all partitions, jointly covering **138 of 138 explicit
benchmark declarations**. These observation entries do not by themselves cover
all benchmark behavior or prove repository completion.
