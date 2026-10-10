# Cache Comparison and Systems Export Functions

[Function index](FUNCTION-INDEX.md) | [Systems drivers](SYSTEM-DRIVER-FUNCTIONS.md) | [Benchmark aggregation](BENCHMARK-AGGREGATION-FUNCTIONS.md)

Sources: [compare_cache_hotspots.py](../../scripts/compare_cache_hotspots.py) and
[systems_figures.py](../../scripts/systems_figures.py). This guide covers **all
9 explicit declarations**, including the nested request callback. Names are
file-qualified. These local diagnostics/exporters do not implement the H2
confirmatory endpoint.

## Architecture

```text
preexisting archived baseline + snapshot current optimized Java runtime
    -> verify runtime inventory -> three fixed synthetic request cases
    -> alternating paired order -> fresh daemon/store per observation
    -> warmup -> timed requests + Java process CPU delta -> rolling rows
    -> paired optimized/baseline wall and CPU ratios -> median summary

validated training blocks -> cache/process CSVs + environment JSON
validated process-crash trials -> recovery CSV + geometric-CI figure
validated concurrency reports -> grouped throughput CSV + per-worker figures
```

Hotspot comparison uses fixed request counts, durable storage and no JFR/GPU
training; OS page cache is uncontrolled. Exports retain the input measurement
roles rather than turning storage samples/s into model-training samples/s.

## Frozen Runtime Comparison

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `compare_cache_hotspots.snapshot(folder)` | Requires a new folder, splits current Java classpath, copies each entry under a numbered subdirectory, writes copied-entry runtime inventory and copies the original paper runtime build receipt. | Does not compile or create baseline. Absolute copied paths are recorded, so moving the snapshot breaks its path references. Copies/inventory/build receipt are separate writes; failure leaves partial output. Non-file/non-directory entries are not copied and delegated inventory handling determines failure. No concurrency lock. |
| `compare_cache_hotspots.verified_classpath(folder)` | Reads `runtime.json`, recomputes runtime records for every recorded entry, requires exact dictionary equality and returns entries joined by host path separator. | Integrity relative to recorded manifest, not signed provenance. Does not validate `original-build.json`, scan extra files or bind entries to the folder. Uses recorded paths; compatible relocation is not automatic. |
| `compare_cache_hotspots.process_cpu_seconds(pid)` | On non-Windows, reads `/proc/PID/stat`, splits after the final parenthesized command delimiter and converts user+system ticks using `SC_CLK_TCK`. On Windows, opens a query-only process handle, obtains kernel/user FILETIME values, converts 100-ns units to seconds and closes the handle in `finally`. | Process cumulative CPU, not wall time or whole-host utilization. Non-Windows branch assumes Linux `/proc`, not arbitrary POSIX support. Missing/terminated process, permission or API errors propagate. |
| `compare_cache_hotspots.run(folder, repeats, operations, warmup)` | Requires even repeats >=2 and positive operation/warmup counts, snapshots optimized runtime and verifies baseline/optimized. Runs warm-batch-4k (32 entries), publish-batch-4k (4 entries) and warm-single-1m cases. Alternates build order, rechecks runtime inventory, patches daemon classpath and starts fresh store/JVM with 128-MiB initial/512-MiB maximum heap. Warms up, measures fixed requests and Java CPU delta, writes rolling comparison rows, then summarizes paired optimized/baseline wall/CPU ratios with medians. | Default CLI 4 repeats, 3000 operations, 1000 warmups. Baseline must already exist. Optimized snapshot refusal prevents straightforward rerun in the same folder; no resume. Startup/warmup/store close excluded from request wall time. CPU sampling surrounds but is not exactly coextensive with wall timing. Ratios can fail on zero baseline CPU; no positivity/finite checks or statistical CI. No return report value. |
| `compare_cache_hotspots.run.invoke(index)` | Publish case constructs batch-specific keys and calls `put_many`; singleton warm case asserts `get` payload equality; batched warm case asserts exact returned dictionary equality. | Callback captures case/batch/client/payload. Negative warmup indices keep publications distinct from measured keys. Read correctness uses Python `assert`, disabled under optimized Python. Timed loop includes key construction and Python assertions, not just server work. Publish callback does not read back each request. |

Payload bytes come from a fresh seed-42 RNG per case; key transform descriptor is
`jfr-diagnostic-v1`. Warm cases prepopulate their keys before warmup. Publication
checks read back only the first and last measured batch after timing, not all
published entries. Java CPU metrics are user+kernel process seconds; per-request
CPU microseconds divide by request count, not entry count. The summary is a local
paired diagnostic, not confirmatory significance or allocation evidence.

Daemon/store contexts own cleanup; monkeypatching the classpath is restored when
its context exits. Data stores remain under `paired/`; there is no temporary
workspace cleanup. `comparison.json` is rewritten after each successful
observation; failure can leave a partial table and no summary. The module-level
CLI has no separately declared `main`.

## Systems Export Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `systems_figures.csv_table(path, rows)` | Requires nonempty rows, takes the first-seen ordered union of dictionary keys, writes CSV header and rows. | Parent must exist. Direct overwrite, not atomic. Missing values become empty cells; no schema, units or numeric validation. Header depends on input/key order. |
| `systems_figures.training_tables(root, output)` | Loads validated paper blocks, reads each `training.json` first run, exports Aether/mmap cache dynamics with identity columns and byte-scope labels. Exports available parent/Java/loader-worker usage rows and environment reports for represented environment IDs. | Aether bytes are logical artifact bytes; mmap bytes are framed payload bytes excluding metadata. Skips falsy process usage and omits resource CSV if no rows. Missing optional metrics remain empty. Does not aggregate, normalize process scopes or independently revalidate training JSON after delegated loading. Partial outputs possible. |
| `systems_figures.durability_figures(root, output)` | Loads validated fault trials, groups by point/mode, calculates geometric mean and 95% log-Student-t interval for recovery ms, sums corrupt/lost-acknowledged counts and records maximum orphan bytes. Writes CSV and horizontal error-bar figure through shared PDF/PNG saver. | Geometric CI requires at least two positive finite values per group. Validated successful trials require zero corruption/lost acknowledged writes. Does not require all planned trials or add a failure-rate confidence interval. Recovery time is process-crash recovery, not power-loss durability. |
| `systems_figures.concurrency_figures(root, output)` | Loads validated concurrency reports, additionally rejects duplicate identities/failed worker reports, groups throughput by backend/client/worker count, calculates geometric means/95% intervals, writes CSV and one clients-vs-throughput figure per worker count. | Groups backend observations independently; does not calculate paired backend ratio intervals or prove full matrix coverage. Requires two valid measurements per point via shared CI. Worker zero denotes the runner's one-process-per-client baseline, not zero processes. |

The shared `geometric_ci` logs values, uses a Student-t interval around mean log
measurement and exponentiates back; it assumes independent measurements, which
the exporter does not establish. The shared saver tightens layout and writes PDF
and 600-dpi PNG before closing the figure. Export functions do not create their
output directory; the module-level CLI does, selecting training/durability/
concurrency. Existing outputs may be overwritten and stale files are not removed.

## Verification

The AST inventory test matches all nine file-qualified declarations. Offline
contracts check frozen-entry drift and CSV header/empty-input behavior. They do
not run Java comparisons, establish process CPU accuracy, validate complete real
campaigns or generate inferential evidence. Browser checks establish reference
layout/readability, not measurement correctness. Read the systems drivers and
receipt references before interpreting an exported figure.
