# Java Benchmark Comparison and Gate Functions

[Function index](FUNCTION-INDEX.md) | [Execution](JAVA-BENCHMARK-RUNNER-FUNCTIONS.md) | [Evidence and baselines](JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md)

This reference covers **14 explicit declarations across eight complete files** in
[aether-benchmarks](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks).
ExternalEngine contributes an enum vocabulary, not explicit methods. Generated
record/enum members are excluded. Together with execution and evidence references,
these cover the module's production implementations, not its test functions.

## Comparison Architecture

```text
Full baseline result + candidate + policy -> RegressionComparator.compare
Repeated equivalent results -> medianByP50 -> one selected result

Compact baseline store + candidate -> BenchmarkGateEvaluator.evaluate
    -> find ID -> toResultLike(candidate) -> RegressionComparator.compare

Aether manifest/result + external manifest/result
    -> ExternalBenchmarkComparator.compare -> blockers + throughput ratio
```

These functions compare supplied records. They do not execute engines, inspect
hardware, recover acknowledged writes, load artifacts, estimate uncertainty or
enforce CI exit status. A passing gate is limited to the checks below. This is not
the Python H2 paired-block analysis or an external-engine adapter implementation.

## Regression Policies and Outcomes

Sources: [RegressionGatePolicy.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/RegressionGatePolicy.java),
[RegressionComparison.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/RegressionComparison.java).

| Function | Behavior | Validation and limits |
| --- | --- | --- |
| `RegressionGatePolicy.RegressionGatePolicy(...)` | Stores permitted throughput decrease, latency increase and acknowledgement-count loss flag. | Throughput fraction finite in [0,1]; latency fraction finite in [0,10]. Rejects invalid inputs with IllegalArgumentException. No sample-count or confidence requirement. |
| `RegressionComparison.RegressionComparison(passed, failures)` | Null failures becomes empty; otherwise immutable list copy. Requires passed exactly when failures is empty. | Null list elements fail during copy. Strings need not be nonblank, unique or recognized failure codes. No failure-count cap. |

END_TO_END uses throughput 0.15, latency 0.20 and acknowledgement-loss checking.
MICROBENCHMARK uses throughput 1.0, latency 0.15 and no acknowledgement-loss check.
The comparator applies the latency fraction to **all three percentiles** for both
policies, despite the microbenchmark constant's comment describing a median gate.
The end-to-end policy also checks p50, not just the p95/p99 named by its comment.

## Full-Result Regression Functions

Source: [RegressionComparator.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/RegressionComparator.java).

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `RegressionComparator.RegressionComparator()` | Private utility constructor. | No stored baseline or mutable instrumentation. |
| `RegressionComparator.compare(baseline, candidate, policy)` | Requires three nonnull inputs. Collects failures in fixed order: benchmark ID, workload map, Aether config map, throughput, p50, p95, p99, acknowledged count. Returns consistent RegressionComparison. | Semantic mismatches do not short-circuit numeric checks. Environment, storage, commit, dirty flag, artifact list, histogram count/max and non-acknowledgement counters are not compared. Map equality is exact. |
| `RegressionComparator.medianByP50(runs)` | Requires nonempty list; checks every run against first for ID/workload/config equality. Copies to array, sorts ascending p50 and returns element length/2. | Returns an existing complete result, not an aggregate. Even sample counts select upper middle. Stable object sort preserves input order for ties. No environment/commit/count equivalence, paired design or confidence interval. Null elements cause NullPointerException. |
| `RegressionComparator.regressedDown(baseline, candidate, fraction)` | Returns false for baseline <= 0; otherwise tests candidate < baseline * (1 - fraction). | Strict inequality: exact threshold passes. A 1.0 fraction disables meaningful throughput failure for nonnegative candidate throughput. Zero baseline provides no throughput gate. |
| `RegressionComparator.regressedUp(baseline, candidate, fraction)` | Returns false for baseline <= 0; otherwise tests candidate > ceil(baseline * (1 + fraction)). | Exact rounded threshold passes. Ceil allows integer-nanosecond rounding; double arithmetic can lose precision for very large long latencies. Zero baseline provides no latency gate. |

With baseline throughput 1000 and END_TO_END, 850 passes while anything below 850
fails. With baseline p95=101 ns, ceil(101 * 1.20)=122 ns, so 122 passes and 123 fails.
These are deterministic thresholds, not a significance test. Choosing the median
run by p50 does not compute median throughput or independently median p95/p99.

Acknowledgement checking means candidate acknowledged count < baseline count.
It does not measure recovered-versus-acknowledged records, inspect write results or
prove crash consistency. A caller must supply equal workload scale and genuine
recovery evidence before interpreting that count check as a durability result.

## Persisted Baseline Gate

Source: [BenchmarkGateEvaluator.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkGateEvaluator.java).

| Function | Behavior | Limits |
| --- | --- | --- |
| `BenchmarkGateEvaluator.BenchmarkGateEvaluator()` | Private utility constructor. | No baseline file IO. |
| `BenchmarkGateEvaluator.evaluate(baselines, candidate, policy)` | Rejects null inputs. Finds exact candidate ID in store. Present entry becomes toResultLike(candidate), then normal comparison; absent entry returns false with one missing-baseline message naming ID. | Does not retrieve original resultUri. Conversion or comparison exceptions propagate. Does not select repetitions or write a gate receipt. |

The compact store has no historical workload/config/environment maps. Its conversion
copies those maps from the candidate. Consequently ID/workload/config equality
checks in this path do not independently certify baseline semantics; stored metrics
are compared under synthetic current context. Use original evidence to establish
fairness. See the evidence reference for dropped counters and reconstructed latency
count/max.

## External Semantics Manifest

Sources: [BenchmarkSemanticsManifest.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkSemanticsManifest.java),
[ExternalEngine.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/ExternalEngine.java).

| Function | Behavior | Validation and ownership |
| --- | --- | --- |
| `BenchmarkSemanticsManifest.BenchmarkSemanticsManifest(...)` | Requires engine, nonblank benchmark/machine/durability strings, positive batch/threads, workloadShape. Strips three strings, uppercases durability using Locale.ROOT, copies map. | keyBytes 1..1 MiB; valueBytes 0..64 MiB; map <= 128 entries. Keys nonblank <=128 units, values <=4096. Map.copyOf precedes entry validation, so null keys/values throw NullPointerException. No string cap/control policy for identity fields, durability vocabulary, or upper bound on positive batch/threads. |
| `BenchmarkSemanticsManifest.sameSemanticsAs(other)` | False for null; otherwise exact equality of benchmark ID, machine ID, canonical durability, key/value sizes, batch, threads and workloadShape. | Deliberately ignores engine so cross-engine comparison is possible. Machine and durability strings are declarations, not verified host identity or actual fsync behavior. Map keys are not stripped; value controls are allowed. |

ExternalEngine values are AETHER, ROCKSDB, LEVELDB, LMDB and SQLITE. Enumeration does
not imply that this module supplies runnable adapters for these engines. Workload
shape is a free-form string map, not a checked schema. Equal empty maps are allowed.

## External Comparison Functions

Sources: [ExternalBenchmarkComparator.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/ExternalBenchmarkComparator.java),
[ExternalBenchmarkComparison.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/ExternalBenchmarkComparison.java).

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `ExternalBenchmarkComparator.ExternalBenchmarkComparator()` | Private utility constructor. | No engine launch or materialization. |
| `ExternalBenchmarkComparator.compare(aetherManifest, aetherResult, externalManifest, externalResult)` | Requires all inputs. Collects blockers for left not AETHER, right AETHER, manifest semantics mismatch, left result ID mismatch, right result ID mismatch. If unblocked and external throughput >0, computes Aether/external throughput; otherwise ratio=0. Returns comparison. | Checks each result's benchmark ID against its manifest, not result maps against manifest shape/host/durability. Does not compare latency, counters, artifacts or commit. External zero throughput yields comparable=true with ratio=0 when semantics match. Finite operands can divide to infinity, causing output constructor rejection. |
| `ExternalBenchmarkComparison.ExternalBenchmarkComparison(comparable, blockers, throughputRatio)` | Null blockers becomes empty, otherwise immutable copy. Requires comparable exactly when blockers empty, and finite nonnegative ratio. | Noncomparable results need not have ratio zero when constructed directly. Ratio zero alone cannot distinguish blocked comparison, zero numerator or zero denominator convention. Null blocker elements rejected by copy. |

For positive denominator and comparable inputs, ratio >1 means higher Aether
throughput, ratio <1 means lower throughput. It is not lifecycle time, speedup over
dataset revisions, break-even version or a statistically established advantage.
The result's comparable flag must accompany the number, and original runtime
evidence must corroborate the caller-provided semantics manifests.

## Tests and Verification Scope

[RegressionComparatorTest](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/RegressionComparatorTest.java)
has three cases: selected throughput/tail/acknowledgement failures, benchmark ID
mismatch, and odd-count median selection plus ID mismatch rejection.
[ExternalBenchmarkComparatorTest](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/ExternalBenchmarkComparatorTest.java)
has three cases: matching manifests and ratio 2, durability/shape mismatch, and
right-engine/result-ID blockers.
[Baseline store tests](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/BenchmarkBaselineStoreTest.java)
also exercise missing baseline and numeric gate delegation.

Existing tests do not exhaustively cover threshold equality/rounding, zero
baselines/denominators, even medians/ties, null inputs, all manifest mismatches,
ratio overflow, constructor edge cases or actual cross-engine semantic parity.
Declaration inventory checks documentation omissions, not runtime correctness.
