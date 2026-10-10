# Benchmark Lifecycle, Cache Accounting, and Comparison Functions

[Function index](FUNCTION-INDEX.md) | [Training](BENCHMARK-TRAINING-FUNCTIONS.md) | [Backend ownership](BENCHMARK-BACKEND-FUNCTIONS.md) | [Aggregation](BENCHMARK-AGGREGATION-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This page covers **18 explicit declarations** for per-run summaries, cache counts,
comparisons and projected break-even. It is not a complete benchmark reference by
itself; the function index links the other partitions.

## Accounting Architecture

```text
step records + backend wall + epoch walls + recorded population
  -> summarize_backend -> lifecycle / steady-state / phase means / observations
cache logical counters + configured schedule -> cache_dynamics -> invariants
raw + Aether + mmap + optional RAM reports -> pair comparisons / outcome flags
mean epoch times + Aether population -> projected crossover grid / formula
per-run results -> aggregation (separate reference)
```

These helpers do not rerun training, force the WAL, validate a frozen protocol or
measure a longitudinal dataset revision. They consume caller-provided records.
Many reported totals omit setup, validation, background drain and other work as
described in the runner/training/backend references. A meaningful interpretation
requires those boundaries, not just a favorable ratio.

## Summary and Checksum Functions

| Function | Behavior and boundaries |
| --- | --- |
| `summarize_backend(backend, steps, epoch_walls, training_ms, context, gpu_samples, process_metrics, cold_start_gate=None, segmentation_metrics=None, *, device=None)` | Sums actual batch sizes, nominal resize-squared pixels, step wall and input wait. Builds lifecycle from training wall plus context.populate_ms, cumulative epoch totals, throughput from summed step wall and effective throughput from backend wall, per-phase means, layouts, checksums, GPU samples/summary and process fields. Includes step records and caller observations without independently verifying them. Denominators clamp to 1e-9. Population getter is called repeatedly, so assumes a stable value. Nominal pixel counts are not FLOPs or classification output elements. |
| `summarize_steps(steps)` | Returns distribution for eight step fields: input/preparation/prefetch/transfer/forward/backward/optimizer/step wall. Requires fields in each supplied step. Empty input produces zero-count distributions. Does not summarize every timing field, check phase sums or separate correlated observations. |
| `cumulative_lifecycle_by_epoch(epoch_walls, populate_ms)` | Adds each supplied epoch wall to initial population, emitting one-based epoch/totalMs records. No correction for backend-wall differences, incomplete epochs, excluded first-batch waiting or background drain. Empty epochs returns an empty list even with nonzero population. |
| `summarize_tensor_layout(steps)` | Empty steps returns empty mapping. Otherwise preserves first image/mask metadata, tests all contiguity flags and reports distinct stride/pinning/pointer-modulo values across steps. Does not validate shape/dtype equivalence across steps, memory ownership or alignment. Mixed missing and typed values in sorted state sets can raise TypeError; well-formed train_step records avoid this. |
| `batch_checksums(batch)` | Hashes image/mask tobytes independently and combines the ASCII concatenated hex digests. Does not include shape, dtype, sample IDs or tensor-layout metadata; those need separate checks. No cache lookup or mutation. |
| `epoch_checksums(steps)` | Groups sorted distinct epoch labels and hashes each epoch's combined batch-checksum hex strings in original step order. Does not sort steps within epochs, check missing/duplicate batches, include epoch labels in the digest or verify equal model state. Empty steps returns empty list. |
| `elapsed_ms(start)` | Returns (time.perf_counter()-start)*1000. Caller must supply a start from the same clock; no clamping, synchronization or timing-window verification. |

samplesPerSecond uses total samples divided by summed stepWallMs. The effective
version uses the whole training wall. Prefetched consumer wait before a yielded
step can therefore affect the latter but not the former. inputWaitPercent divides
reported wait by summed step wall and is not capped at 100%. Phase timing fields
are means per step, not total costs or batch-size-weighted means.

cumulativeByEpochMs need not end at lifecycle.totalMs because supplied epoch walls
are not an exact partition of the backend wall. Total includes recorded population,
not every initialization/seed/discovery cost. Background drain is a separate field
added by the controller and is not folded into these totals.

## Cache Count Functions

| Function | Behavior and boundaries |
| --- | --- |
| `cache_dynamics(protocol, args)` | Converts logical hit/miss counters into lookup count, ratio fractions, recomputedSamples=misses, publishedSamples=sum(write-batch sizes), nanosecond costs to milliseconds and byte/batch summaries. Carries configured/target ratios and initial index list, then attaches cache_invariants. Missing/falsey counters default zero. Ratios are fractions here, while configured hit ratios are percentages. No direct storage/transport/physical I/O inspection. |
| `cache_invariants(dynamics, args)` | Derives expected schedule counts from the set of initial indices and requires lookup/hit/miss/published counts plus prepopulated count equality. Returns detailed expected fields and passed. Does not check exact identity/bytes/durability, publication batch count or timing. Invalid initial indices are not rejected separately; normal callers capture valid indices. |
| `expected_cache_counts(args, initial_present_indices)` | Simulates scheduled_batches for effective_measured_steps, counting every requested index and one miss the first time an initially absent index occurs. Marks it present thereafter. Does not execute preprocessing/publication or model eviction, corruption, failures or concurrent changes. Counts may cover a truncated epoch rather than args.epochs complete passes. |

Recomputed and published samples are logical inferences, not independent probes.
The invariant assumes a successful miss is published once and survives subsequent
lookups. A population-only counter window is not automatically the same as the
configured training schedule. The controller separately checks exact initial
index-set parity and final model hashes.

## Comparisons and Outcome Functions

| Function | Behavior and boundaries |
| --- | --- |
| `compare_backends(results)` | Returns primary Aether/raw, secondary Aether/mmap and secondary Aether/RAM comparisons via compare_pair. Missing optional backends yield empty comparison mappings. Does not choose a winner, validate pairing or include MONAI in this original controller. |
| `compare_pair(aether, baseline)` | Missing/falsey report returns empty mapping. Otherwise reports Aether-minus-baseline input-wait percentage-point and training/total wall deltas, plus Aether/baseline step and effective throughput ratios. Negative time/wait deltas and throughput ratios above one favor Aether; ratio direction differs from time-cost ratios elsewhere. Uses per-run scalar records, not already aggregated distribution mappings. |
| `training_outcome(results)` | Missing raw/Aether yields MISSING_BASELINE. Computes strict-improvement flags for wait, throughput, epoch/training/total wall and mean preprocessing. Classifies throughput+total improvement first, then any throughput/training/epoch signal, then wait/preprocess-only signal, otherwise no signal. Ties do not count as improvement. Descriptive threshold-zero flags are not significance tests and are only against raw recompute, not every baseline. |
| `outcome_interpretation(status)` | Maps known outcome labels to explanatory text and unknown labels to Unknown outcome. Does not recompute data or upgrade a descriptive signal into statistical/research evidence. |

## Projected Break-Even Functions

| Function | Behavior and boundaries |
| --- | --- |
| `training_break_even_epoch(results)` | Requires raw/Aether, projects Aether population + Aether mean epoch*N against raw mean epoch*N with strict 1% improvement. Checks fixed grid 1,2,3,4,5,10,15,18,20,25,30,50 and returns first qualifying projection or None. Does not use recorded cumulative epoch walls, interpolate a minimum integer, subtract raw population or observe future training. |
| `admission_model(results)` | Requires raw/Aether, computes continuous zero/1%-margin projected roots via predicted_break_even_epoch, obtains fixed-grid crossover and reports their difference as predictionErrorEpochs. The observedSampledCrossoverEpoch name refers to that projected grid, not an observed longitudinal experiment. Uses mean epoch as aetherWarmEpochMs even though mean can include cold first-epoch work; no separate warm-only fit. |
| `predicted_break_even_epoch(raw_epoch_ms, aether_epoch_ms, aether_populate_ms, margin)` | Uses savings=(1-margin)*raw_epoch-aether_epoch. Nonpositive savings returns None; otherwise nonpositive population returns 0.0 and positive population returns continuous population/savings. No integer rounding, finite/range validation, confidence interval or future workload measurement. Formula boundary is equality while the fixed-grid helper requires strict inequality. |
| `ratio(left, right)` | Returns None only when right equals zero, otherwise left/right. Does not normalize percentages, reverse cost ratios, validate finite/positive numbers or handle None operands. |

The formula ignores baseline population and assumes constant mean epoch costs.
The fixed grid can skip the earliest qualifying integer and can project beyond
actually measured epochs. None means no qualifying grid/root under these inputs,
not proof that reuse can never recover cost under another workload. These are
not H2's cumulative lifecycle/break-even-version estimators.

## Verification Scope

AST inventory and local arithmetic fixtures cover these 18 entries. Tests check
denominators, checksum omissions/order, truncated schedules, counter invariants,
strict ties, fixed-grid projection and formula limits. They do not establish a
paper result or performance gain. The complete benchmark function-entry inventory
is checked jointly with its other references; the repository-wide goal remains.
