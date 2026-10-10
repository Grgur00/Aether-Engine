# H2 Analysis and Figure Functions

[Function index](FUNCTION-INDEX.md) | [H2 protocol](H2-PROTOCOL-FUNCTIONS.md) | [H2 execution](H2-EXECUTION-FUNCTIONS.md)

Source: [h2_analysis.py](../../scripts/h2_analysis.py). This page covers all five
explicit functions, including the nested figure writer. It explains the implemented
predeclared calculation; it does not change the frozen statistical protocol or
claim that an experiment has finished. Synthetic test timings are not observations.

The [multi-session controller](H2-SESSION-FUNCTIONS.md) preserves original block
payloads but does not yet implement final multi-session aggregation. This page's
calculation is not proof that nested session archives or session effects have
been validated; the amendment must be disclosed separately in final reporting.

## Evidence-to-Inference Boundary

```text
analyze_output (runner): validate archived protocol/preflight/attempts/receipts
  -> 24 whole paired blocks, all backend permutations, no pilot/preflight timings
  -> analyze: endpoint consistency + baseline/Aether ratios at V0..V4
  -> primary: V4 log(mmap/Aether), one-sided paired t-test
  -> MONAI: descriptive secondary ratios
  -> raw-result-derived figures + limitations + completion inventory
```

analyze checks important invariants, but is not the entire receipt validator.
It does not itself run validate_stages, CUDA/device guards, runtime/source probes
or seals. Call the archived runner path for campaign evidence. Direct helper
calls on fabricated dicts can exercise arithmetic without certifying a workload.

## Function Reference

| Declaration | Behavior and boundary |
| --- | --- |
| `ratios(values)` | Converts to a NumPy float array; requires len=24 and every value positive/finite, then logs ratios, calculates mean log, sample standard error with n=24 and t(23) two-sided 95% radius. Returns geometric ratio/CI, reduction, raw/log ratios, wins/ties and log statistics. Assumes a one-dimensional paired vector; does not explicitly enforce ndim. No superiority p-value here. |
| `analyze(blocks)` | Requires exactly indices 0..23, all 24 unique backend permutations, confirmatory role, explicit correctness true/technicalFailure false and common source/protocol hashes. Recomputes cumulative phase endpoints, calculates three baselines' ratios at five versions and break-even/lost-advantage summaries. Adds mmap V4 primary t-test, sensitivity results, degeneracy flag and conclusion. No pilot pooling, performance-based exclusions or per-version significance selection. |
| `mean_ci(values)` | Computes axis-0 arithmetic means and t(23)*sample-standard-deviation/sqrt(24) bounds. Assumes exactly 24 observations; no independent size/finite/shape validation. Used only after plot/analyze's block-count guard for seconds and paired differences, not geometric ratios. |
| `plot(blocks, directory)` | Selects Matplotlib Agg, re-runs analyze, creates output directory, derives five figures directly from blocks/summary and writes PNG/PDF pairs. Uses arithmetic mean CIs for seconds/differences, geometric ratio CIs for relative performance and all 24 primary points for paired scatter. No synthetic smooth curves or omitted slow valid blocks. Existing figure names can be overwritten; save failure can leave partial outputs. |
| `plot.finish(fig, axis, name, title)` | Nested helper sets title/y-grid, tightens layout, writes 180-dpi PNG and PDF, then closes figure. No atomic two-file publication or finally close on a save error; caller's campaign completion is written later. |

## Primary Calculation and Direction

Each paired ratio is baseline cumulative time divided by Aether cumulative time.
A ratio above one favors Aether. The effect estimate is exp(mean(log ratios)),
not mean(baseline)/mean(Aether), an arithmetic mean of ratios or a median speedup.
Percent time reduction is `100 * (1 - 1 / geometricMeanRatio)`, relative to baseline
time; it is not `100 * (ratio - 1)`.

The primary checkpoint is **V4**. The implemented one-sided statistic is mean log
ratio divided by its sample standard error, with 23 degrees of freedom and the
upper-tail t probability. Superiority requires nondegenerate p<0.05 **and** a
geometric ratio >1. The interval is two-sided 95% on log scale, exponentiated back.
Other versions describe the recovery trajectory; they do not supply alternative
primary hypothesis tests when V4 is unfavorable.

If standard error is at most NumPy float epsilon, tStatistic and oneSidedP become
None and superiority is false. Even identical favorable ratios have no estimated
sample variance and are treated as inconclusive, not assigned an infinite t value
or fabricated significance. ratios itself can still report a zero-width CI; the
primary conclusion accounts for degeneracy separately.

Sensitivity output uses a one-sided sign test on nonzero logs, an approximate
one-sided Wilcoxon test, and Shapiro on nondegenerate logs. All ties yield sign/
Wilcoxon p=1; Shapiro is None for degenerate data. These are reported diagnostics
and never replace the primary method. MONAI comparisons are descriptive only.
The source implements these tests through SciPy, not hand-written approximations.

## Break-Even Semantics

Per-block firstVersion is the first ratio **>=1**, so a tie qualifies there.
Aggregate firstVersion is the first geometric mean ratio **>1**, so a tie does not
qualify. V0 is eligible; no crossing becomes None. advantageLostLater records any
later ratio <1 after qualifying advantage, rather than asserting permanent savings
from the first crossing. Aggregate lost advantage uses the aggregate strict >1
condition. These are observed version checkpoints, not interpolated dataset sizes,
predicted future epochs or a reuse-rate causal curve.

Win counts use ratio >1 and ties exact float equality to one. No tolerance-based
tie policy is applied. Valid elapsed times remain in the analysis even when slow.
No intermediate 12-block p-value, pilot pooling or optional stopping is implemented.

## Figure Outputs

| File stem | Meaning |
| --- | --- |
| 01-cumulative-lifecycle | Backend arithmetic mean cumulative seconds and 95% CI across V0-V4, with V4 marked |
| 02-relative-performance | Baseline/Aether geometric ratios and exponentiated log intervals at each version |
| 03-paired-blocks | Every paired block's V4 mmap/Aether ratio, labeled by block index+1 |
| 04-cost-breakdown | Mean lifecycle seconds split into V0 preparation, update preparation, training, model setup and startup/drain/close |
| 05-break-even | Mean paired Aether-minus-mmap cumulative seconds and CI; negative favors Aether |

Each stem has PNG and PDF. Seconds are converted from the archived millisecond
phases; relative ratios are dimensionless. The cost breakdown has mean components,
not independent component confidence intervals. Mean timing ribbons and geometric
ratio ribbons describe different quantities and should not be conflated.

## Verification Scope

[test_h2_confirmatory.py](../../scripts/tests/test_h2_confirmatory.py) compares
the primary arithmetic against SciPy's one-sided t-test, checks direction/reduction,
rejects incomplete/pooled/duplicate/unbalanced/failed/preflight observations, treats
equal samples as inconclusive, verifies five nonempty PNG/PDF pairs and validates
archived campaign gating. The [focused documentation contracts](../../scripts/tests/test_h2_documented_contracts.py) check direct-helper
limits and exact break-even/tie semantics with synthetic timings. These establish
calculation behavior, not real GPU superiority or reproducibility of arbitrary data.
