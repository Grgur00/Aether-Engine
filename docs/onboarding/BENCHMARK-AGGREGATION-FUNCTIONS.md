# Benchmark Run Aggregation and Statistical Helper Functions

[Function index](FUNCTION-INDEX.md) | [Lifecycle and comparisons](BENCHMARK-ACCOUNTING-FUNCTIONS.md) | [Run controller](BENCHMARK-RUNNER-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This page covers **12 explicit declarations**. It is not a complete benchmark
reference by itself. These helpers summarize already produced per-run reports;
they do not freeze a protocol, launch experiments or independently establish
pairing, independence, significance or reproducibility.

## Aggregation Architecture

```text
per-run backends -> first report copied + selected fields replaced by distributions
per-run counters -> selected sums and concatenated batch sizes
per-run comparisons/models/outcomes -> distributions + descriptive status counts
values -> distribution -> mean/median/sample SD/normal half-width/order-statistic percentiles
```

Aggregation mixes selected pooled counters, distributions of run summaries and
first-run representative fields. A distribution of run p95 values is not a pooled
request/step p95. A mean of run utilization means is not time/sample-count weighted.
Inspect per-run records and field scope rather than treating the whole aggregate
as one interchangeable experiment record.

## Run and Backend Aggregators

| Function | Behavior and boundaries |
| --- | --- |
| `aggregate_runs(runs)` | Iterates known BACKENDS, gathers runs containing each backend and calls aggregate_backend_runs when present. Aggregates protocol, operation metrics, cache dynamics, comparisons, crossover, admission models and outcomes. Returns input run count even when some backends/fields are absent. Does not reject mismatched configurations, verify parity/validity, preserve original run IDs in every derived field or aggregate every top-level field. |
| `aggregate_backend_runs(backend, backend_runs)` | Deep-copies first report, adds count, merges available prefetch metrics when all present ones advertise availability, and replaces lifecycle/steady-state/timing/GPU/process fields with selected aggregate forms. Timing fields missing in another run contribute zero. GPU summaries average available run means/percentiles, take maximum peak and collect sampling statuses/sources/errors/counts. Process numeric fields use distributions of non-null values; runSummaries enumerate the gathered subset, not original IDs. Other copied fields such as steps/layout/checksums/resources remain first-run representatives. Requires nonempty compatible input. |

aggregate_backend_runs replaces lifecycle with only four distribution-valued fields;
first-run cumulativeByEpochMs and later background-drain fields are not retained
there. It does not sum the whole lifecycle across runs. The backend parameter is
not used to validate report names. Prefetch perRun contains original metric
mappings, and other first-run fields are not evidence of all-run equality.

## Counter and Metric Aggregators

| Function | Behavior and boundaries |
| --- | --- |
| `aggregate_protocol(protocols)` | Sums a fixed list of logical counts/bytes/nanosecond times, concatenates write-batch sizes, derives mean/aliases, and makes walForceCount None if any input has null/missing value, otherwise sums forces. Does not aggregate transport metrics, initial indices, target hit ratios, extra scope labels or arbitrary fields. Empty input yields zero totals/mean and zero WAL forces. |
| `aggregate_cache_dynamics(dynamics_by_run)` | Returns distributions for a fixed list of reuse/count/ratio/cost/byte/batch fields, omitting null/missing values per field. Does not carry invariant passed flags, index sets or all configuration fields. Empty values become zero-count distributions, not verified zero activity. |
| `aggregate_operation_metrics(metrics_by_run)` | Uses union of operation names, sums counts, takes maximum reported max for positive-count runs, and distributes their mean/p95 values equally by run. Does not pool raw requests or weight by operation counts; count-zero summaries do not contribute mean/p95/max. Assumes valid numeric reported fields. |
| `aggregate_comparisons(comparisons)` | Unions comparison names/fields and distributes non-null values from runs that have them. Missing values are omitted, so sample counts can differ by baseline/metric. Does not preserve paired observations for inferential resampling or validate ratio direction. |

## Crossover, Admission, and Outcome Aggregators

| Function | Behavior and boundaries |
| --- | --- |
| `aggregate_break_even(values)` | Takes non-null epoch fields from truthy crossover mappings; returns None if none qualify, otherwise an epoch distribution plus original rawRuns list. Distribution is conditional on crossings; no-crossing runs are retained only in rawRuns, not counted as infinity/censored data. |
| `aggregate_admission_models(models)` | Drops falsey model mappings, returns None if none remain, and distributes seven non-null formula/grid/error/cost fields. Reports count of present models, fixed 1% margin, first model equation and retained raw mappings. Per-field counts can differ and zero-margin fields still coexist with fixed report margin. No model fitting or censoring analysis. |
| `aggregate_outcomes(outcomes)` | Counts present status strings and distributes six non-null deltas/ratios. Reports runs=len(all outcomes); flag pass rates count truthy flags over all inputs, so missing flags dilute the rate rather than reducing denominator. Does not choose one aggregate winner, test significance or inspect omitted baselines. |

## Statistical Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `distribution(values)` | Empty sequence returns count zero and zeros for every statistic. Otherwise computes mean, median, sample standard deviation (zero for singleton), confidence95=1.96*SD/sqrt(n), p50/p95 order statistics and min/max. confidence95 is a normal-approximation half-width, not a confidence interval pair, Student-t/bootstrap or paired-block result. Does not filter None/nonfinite values, test independence or validate workload homogeneity. |
| `percentile(values, pct)` | Sorts values and selects clamped index int(pct/100*n+.999)-1. It is a discrete nearest-rank-like rule, not interpolation or necessarily NumPy's percentile convention. Empty input raises IndexError; out-of-range pct selects an endpoint. Does not filter/validate inputs. |
| `mean(values)` | Returns statistics.mean for truthy input, otherwise zero. Empty zero is an API fallback, not evidence of a measured zero. Expects a sequence with sensible truthiness; arbitrary generators and arrays can behave differently. |

For two samples, p50 is the lower order statistic while median averages the two.
One sample has zero reported SD/half-width without establishing certainty. Empty
distributions also report zero mean/half-width; always inspect count. Repeated
steps inside one run are correlated, and these summaries are descriptive rather
than a substitute for H2's paired-block confirmatory analysis.

## Verification Scope

AST checks cover all 12 entries and jointly audit the full benchmark declaration
inventory against all partitions. Arithmetic fixtures check representative-field
retention, omitted/missing values, unweighted summary behavior, conditional
crossover aggregation, flag denominators, percentile boundaries and half-widths.
No performance claim, scientific result, runtime implementation or frozen campaign
is changed. Complete function entries for this module do not complete the broader
repository documentation goal.
