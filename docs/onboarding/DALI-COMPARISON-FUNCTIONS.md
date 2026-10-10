# DALI Workload and Comparison Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark training](BENCHMARK-TRAINING-FUNCTIONS.md) | [Research ownership](EXPERIMENT-OWNERSHIP-FUNCTIONS.md)

Sources: [dali_workload.py](../../clients/python/aether_training_cache/dali_workload.py),
[dali_comparison.py](../../scripts/dali_comparison.py),
[dali_analyze.py](../../scripts/dali_analyze.py),
[dali_smoke.py](../../scripts/dali_smoke.py).
This guide covers **26 explicit declarations across all four complete files**,
including the nested pipeline graph. Lambdas/comprehensions are explained at their
owning call sites. This is a secondary RGB workload, not the OCT/H2 experiment.

## Architecture and Timing Boundary

```text
real source manifest -> source hashes + pipeline descriptor -> artifact keys
canonical DALI preflight -> expected serialized payload hashes (excluded)
initial reusable prefix -> populate Java and mmap stores -> stop Java daemon
restart Java daemon -> shuffled raw / Aether / mmap paired training
    raw: scheduled asynchronous DALI -> GPU model input
    cache: byte lookup -> missing-only manual DALI -> durable publication
           -> validate payload -> CPU decode/stack -> GPU model input
all final model hashes equal -> report + byte-hash receipt
validated real reports -> secondary analysis/figures
generated fixture smoke -> correctness only; research loader rejects
```

Direct and cache-miss paths use the same DALI operators, not a presumed
bit-identical Pillow decoder. Cache paths serialize CPU-visible artifacts and
transfer decoded tensors back to CUDA; direct processing keeps the image on the
GPU. This difference belongs to the measured workflow. Source/canonical preflight
warms files; page cache is explicitly uncontrolled.

Reported throughput uses training-loop wall time. Model/pipeline/store setup and
initial population are recorded separately. `workflowCostMs` sums those three
parts but does not include source loading, canonical preflight, Java daemon
startup/stop, result archival or cleanup. It is not H2's cumulative V0-V4 endpoint.

## Canonical Identity and Payload Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `descriptor(args, dali_version)` | Returns an identity dictionary naming DALI version, dataset, RGB mixed decode, linear antialiased square resize, divide-by-255 normalization, CHW float16 output, float32 training, class count and uncompressed artifact encoding. | Describes this implementation's pipeline. Does not construct or inspect a running pipeline; excludes performance knobs such as prefetch depth. |
| `artifact_key(source, parameters)` | SHA-256 of sorted compact JSON containing sample ID, source hash and pipeline parameters. | Source content/version and descriptor changes invalidate reuse. Unkeyed identity digest; caller/source loader supplies trustworthy hashes. |
| `targets(sources, indices, classes, np)` | Creates a float32 zero matrix, setting each source's listed label columns to one. | Multi-hot shape `[batch, classes]`; no label-range/type guard beyond NumPy indexing. Used for both ImageNet and COCO here. |
| `payloads(images, sources, indices, classes, np)` | Builds targets, then maps each requested source index to existing benchmark `pack_payload` bytes containing sample ID, its row image and mask/labels. | Assumes rows align with indices; no explicit shape/length validation here. Imports shared packing lazily. Serialization and training decode remain separate helpers. |

## DALI Input and Pipeline Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `EncodedSource.__init__(sources, schedule)` | Stores the supplied source/schedule references for stateless scheduled input. | Does not copy or validate them. |
| `EncodedSource.__call__(iteration)` | Stops when iteration reaches/exceeds schedule length; otherwise reads each scheduled image file as a NumPy uint8 byte array. | No mutable cursor, so repeat/speculative calls at an index are repeatable. Negative iteration is not independently rejected. File failures propagate. |
| `DaliBatches.__init__(args, sources, schedule=None)` | Lazily imports CUDA/DALI, requires CUDA availability/version, stores arguments and chooses scheduled callback versus manual feed. Builds a device-zero pipeline with seed, batch size and threads. Scheduled mode enables async/pipelined/dynamic execution with requested prefetch; manual mode disables those and uses depth one. | No CPU timing fallback. Creation/build failures happen before callers' later loop-cleanup handlers in several paths. Runtime DALI availability is not tested by plan-only execution. |
| `DaliBatches.__init__.graph()` | Defines external uint8 encoded batches, mixed RGB decode, GPU linear antialiased resize and GPU crop/mirror-normalize to CHW float16 with mean zero/std 255. | Decorated pipeline graph; despite operator name, no random crop/mirror parameters are supplied. Square resize, not the Pillow workload's implicit equivalence. |
| `DaliBatches.batch(indices=None)` | Manual mode requires a nonempty index list no larger than declared batch size and feeds encoded files. Scheduled mode rejects explicit indices. Runs pipeline, converts the output tensor through DLPack and casts float16 output to CUDA float32. | Direct path has no host image round trip here. Producer/consumer synchronization is delegated to DLPack; missing-input and pipeline exceptions propagate. |
| `DaliBatches.close()` | Synchronizes CUDA and clears the pipeline reference. | No explicit context-manager protocol or idempotence state check. Does not close storage or the Java daemon. |

## Comparison Preparation Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `batches(count, size, epochs=1)` | Materializes ordered index lists, including a final partial batch, repeated for each epoch. | No shuffle. CLI checks positive batch size/epochs; direct calls do not validate count or size. The entire schedule is held in memory. |
| `model_hash(network)` | Hashes sorted state-dict names followed by contiguous CPU NumPy tensor bytes. | Captures final parameter/buffer bytes, not optimizer state, tensor shape/dtype metadata or loss trajectory. Moving tensors to CPU can synchronize; called after measured loop wall time is captured. |
| `validate_payloads(values, expected)` | Checks SHA-256 equality for every supplied index/payload against canonical expected hashes. | Does not itself prove all expected indices were supplied; missing coverage is checked elsewhere. Unknown indices can raise KeyError. |
| `canonical_hashes(args, sources, np)` | Runs one scheduled canonical DALI pass, moves each batch to CPU, serializes payloads and records hashes by source index. Closes pipeline in `finally`. | Excluded preflight, but warms source files and GPU pipeline. Construction happens before the protected loop. Does not publish artifacts. |
| `open_store(backend, root, port)` | For Aether, connects a `JavaArtifactStore` in namespace `dali-canonical`, requires actual `java-training-cache`/`DURABLE` engine info and attaches that info to the store; closes and rejects mismatches. Otherwise creates durable PersistentMmapStore under `root/mmap`. | Unknown non-Aether strings fall through to mmap; callers use fixed backend names. Probe/constructor exceptions are not universally cleanup-protected. |
| `publish(store, backend, values, keys)` | Aether sends one `commit_bytes_many` list of cache-key/data dictionaries; other backends use mmap `put_many` key/value tuples. | Delegates durability/publication to adapter. Does not inspect returned Aether commit values or batch results here. |
| `populate(args, sources, keys, expected, initial, backend, root, port, np)` | Times opening store, optional manual DALI construction, ordered prefix batches, canonical payload validation, publication and final closes; returns elapsed milliseconds. | Only `initial` prefix is prepared. With zero initial entries no pipeline is created. If pipeline close raises, the subsequent store close in that `finally` is skipped. |

## Training Lifecycle Function

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `train(args, sources, keys, expected, initial, backend, root, port, np, torch)` | Seeds a fresh CUDA model and AdamW optimizer, selects CrossEntropyLoss for ImageNet or BCEWithLogitsLoss otherwise, constructs schedule/pipeline/store, verifies exactly the initial reusable key prefix survived restart, resets metrics/peak allocation, and measures training over every batch/epoch. Returns throughput, wall/setup/input-wait times, final model hash/losses, cache counts, storage/engine metrics and peak CUDA allocation. | Setup checks precede the protected training loop. A setup/parity failure can leave an opened store/pipeline without the loop's cleanup. Pipeline close failure can prevent store close. No training CPU fallback, epoch checkpoint or same-daemon H2 lifecycle. |

Within the training loop:

1. Raw mode gets scheduled DALI images directly and creates CPU targets then
   transfers them to CUDA. It does not individually compare raw payload hashes
   during training; final model equality provides the paired end-state check.
2. Aether batch-loads key bytes. Mmap reads each key and removes its four-byte
   wrapper before shared payload decoding. Missing values are computed using
   manual DALI, serialized, hash-validated and published once.
3. All cached/generated payloads are hash-validated, decoded and stacked in batch
   index order on CPU, then transferred to CUDA. These steps count as input wait.
4. Only the consumer CUDA stream is synchronized at the input-wait boundary so
   scheduled DALI can prepare future batches on its streams. Optimizer zeroing,
   forward/loss/backward/update and `loss.item()` follow. Nonfinite loss rejects
   the block. `inputWaitPercent` is scoped to these measured input sections, not
   a generic GPU-stall profiler.
5. Final global synchronization ends the wall timer. Cached backends require
   `lookups=N*epochs`, `misses=N-initial`, and `published=misses`; reports record
   hits separately. Later epochs must therefore reuse first-epoch publications.
6. `gpuHoursDuringTraining` is wall seconds divided by 3600, not a utilization
   integral or cloud billing measurement. Peak allocation is PyTorch's allocated
   memory counter, not total device/driver/DALI reserved memory.

## Paired Block and Resume Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `run_block(args, point, index, output, protocol, env_id)` | Computes conservative payload floor and enters owned cache-workspace management, delegating measurement with that generated-store root. | Stores and capacity checks are separate from report output. Does not launch independent process isolation for each backend. |
| `measure_block(args, point, index, output, protocol, env_id, root)` | Merges args/condition, sets seed to base+block, loads sources, derives keys/canonical hashes, computes rounded reusable prefix, shuffles three backends using a private seeded RNG, populates cached stores inside one Java daemon context, then reopens another daemon over the same Java root for all three training runs. Requires identical final model hashes and writes report plus report-byte receipt. | Always uses separate population/training daemon lifetimes, not a persistent V0-V4 experiment. Python-rounded reuse count is the actual prefix, not exactly the requested percentage. Report write and receipt write are separate operations; partial evidence remains after failure. |
| `validated_report(path, protocol, env_id)` | Verifies report-byte receipt hash, report protocol/environment, PASSED/model parity, both cache invariants, scheduled condition/index/seed, exact backend set/order membership, complete canonical hash keys, finite positive matching throughput, expected cache lifecycle counts and Java DURABLE engine evidence. Returns the report. | Does not recompute canonical tensors, model hashes or measured durations. Does not rederive random backend order, validate loss list contents or workflow-cost sums. Receipt's own protocolHash field is not compared here; report protocol hash is. |

The block stores initial population time separately for Aether/mmap, per-backend
setup and loop times, canonical artifact hashes and a truthful page-cache note.
Throughput comparisons do not automatically establish total-workflow dominance.
The exact loss trajectories are retained, but equality of trajectories is not an
explicit guard; only final model byte hashes must match across all three backends.

## CLI, Campaign and Analysis Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `dali_comparison.main(argv=None)` | Parses dataset/config, repeats/epochs/batch/reuse, DALI thread/prefetch, model/seed, smoke/resume/scratch/output options. Requires positive matrix dimensions and reuse 0..100; supports only COCO/ImageNet. Builds secondary protocol and either writes plan-only JSON without CUDA/DALI or enters exclusive output ownership and executes. | Defaults: 12 repeats, five epochs, batch 16, reuse 66.6445%, four threads, prefetch two, small model. Dataset names are comma-split without stripping. Plan-only is not measured evidence and writes outside the exclusive execution lock. |
| `execute(args, points, plan)` | Sets default cuBLAS workspace config, requires CUDA and imports DALI, captures environment/accelerator identity, binds source/manifest hashes and pipeline descriptors into plan, freezes campaign metadata, then loads validated existing results for resume or measures new blocks. Archives ordinary failures with traceback/identity and rethrows; writes summary only after all loop blocks pass. | Resume requires unchanged frozen protocol/environment via shared metadata helper. No automatic retry or maximum-attempt policy like H2. Summary's role string is generic secondary DALI even for fixture mode; analysis admission uses protocol measurementRole and rejects fixture protocols. KeyboardInterrupt is outside `except Exception`. |
| `load_dali_blocks(root)` | Requires canonical-DALI protocol schema and real secondary role, discovers immediate subdirectory block JSONs excluding receipts, verifies each environment ID/file/source/CUDA identity and report, rejects duplicate condition-digest/block pairs, and adapts reports to shared analysis conditions/throughput fields. Requires at least one valid block. | Does not require every configured condition/repetition or one common environment ID itself. Multiple internally valid environment files can be read; execution metadata fencing is separate. Adapted fields omit workflow costs, model hashes and backend order. |
| `dali_analyze.main(argv=None)` | Requires input/output, loads accepted blocks, generates figures labeled `DALI direct`, calls shared `analyze_blocks`, adds secondary/non-Pillow/non-OCT scope and writes analysis JSON. | Delegates statistical/sample completeness to shared analysis, not H2's fixed 24-block inference. Figures are generated before final report writing; failures can leave partial output. Does not modify original source reports. |
| `run(output)` | Smoke helper creates a fresh output, seeded five RGB PNG/JPEG fixtures with two class folders and varied dimensions, prepares an ImageNet manifest/config with resize eight, then calls comparison for one repeat, two epochs, batch two, two threads and 40% reuse with fixture-smoke flag. Prints correctness-only success. | Requires actual CUDA/DALI despite generated input. Existing output is rejected. No cleanup rollback for failed fixture creation/run. Not a real dataset benchmark or research observation. |

The smoke script's module-level argparse block supplies output and calls `run`;
it is not a separately declared `main` function. Shared `analyze_blocks`/figure
implementation and source preparation are separate code ownership boundaries.

## Verification Scope

[test_dali_workload.py](../../scripts/tests/test_dali_workload.py) exercises descriptor
and content identity, payload parity/packing, stateless callback exhaustion,
partial batches and plan-only without DALI. Its orchestration case deliberately
uses CPU/DALI/Java/CUDA doubles plus a real mmap adapter; five samples, two epochs
and a two-entry prefix yield 10 lookups, seven hits, three misses/publications.
It also proves the fixture-role protocol is rejected by the research loader.

Those checks do not establish GPU decoder parity, DALI prefetch behavior, CUDA
model reproducibility, real Java transport durability or actual accelerator
performance. This documentation batch does not run the GPU smoke or a campaign.
The qualified AST gate checks all 26 named declarations against individual table
entries; it is not semantic correctness or experimental completion evidence.
