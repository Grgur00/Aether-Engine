# Benchmark Model, Training, and Device Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark data](BENCHMARK-DATA-FUNCTIONS.md) | [Loading and prefetch](PYTHON-PIPELINE-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This reference covers 25 explicit declarations, including four nested model
methods. It is not a complete benchmark reference. Backend storage ownership,
utilization sampler implementation, orchestration and result aggregation have
separate references linked from the function index.

## Execution Architecture

```text
validated arguments + accelerator report -> select device and workload
source/reference preparation -> matched backend initial state
backend preparation -> zero-input optimizer warmup (excluded)
scheduled batches -> inline/queued/worker preparation -> random augmentation
                 -> checksums -> contiguous CPU tensors -> blocking transfer
                 -> forward -> loss -> backward -> optimizer (synchronized)
                 -> measured backend wall stops and resource sampler closes
                 -> reference sanity check -> final model hash -> report
```

The original run_training_once creates a fresh model and AdamW optimizer for
each backend, restores a shared pre-warmup model state, and resets the run seed.
ImageNet uses CrossEntropyLoss; other workloads use BCEWithLogitsLoss. This is
not continual fine-tuning of one model across dataset revisions. The H2 worker
uses a separately frozen orchestration boundary; do not infer its timing protocol
from every caller of these shared functions.

For OCT/synthetic segmentation, the model predicts a binary logit at each pixel
from a one-channel image. The masks come from the preparation path. This is a
controlled training workload for backend comparison, not evidence of clinical
accuracy, a validated diagnostic model, or a held-out generalization experiment.

## Availability and Device Functions

| Function | Behavior and boundaries |
| --- | --- |
| `import_numpy_status()` | Imports NumPy and returns availability/version. Catches ordinary import exceptions and returns their string as an error. Does not perform an array operation, inspect ABI compatibility of other packages or install dependencies. |
| `accelerator_report()` | Imports torch, catching import exceptions into an unavailable report. Uses torch.cuda availability and device zero properties even for ROCm, whose PyTorch API uses cuda names. Reports build CUDA/HIP versions, name, count, VRAM and gcnArchName or CUDA major capability. After import, device-query errors propagate. Reports the first device, not a proof that all requested GPUs work; CUDA major alone is not the complete capability pair. |
| `unsupported_reason(numpy_status, accelerator, accelerator_backend, expected_gpu)` | Returns the first rejection string in order: NumPy unavailable, device unavailable, backend mismatch, name mismatch; otherwise None. Expects an available field in the NumPy mapping. This precedence can hide later problems and it does not run a kernel. |
| `accelerator_backend_matches(accelerator, accelerator_backend)` | Rejects unavailable devices. Auto accepts a CUDA or HIP build version, rocm requires HIP, cuda requires CUDA without HIP, and unknown requests return False. Checks supplied report fields, not runtime kernel execution or the reported backend label alone. |
| `gpu_name_matches(name, expected_gpu)` | Empty expected name accepts any device. Otherwise compares case-insensitive substring, with an AMD Radeon RX 7900 family exception that ignores suffix differences. Does not require an exact SKU, count or VRAM; callers normalize missing actual names. |
| `synchronize_device(torch, device)` | Calls torch.cuda.synchronize(device) only when device.type is cuda. CPU and other device types do nothing. ROCm devices also use cuda here. Errors propagate; the call waits for device work, not specifically one model operation. |
| `gpu_smoke_test(torch, device)` | Allocates a random 1024x1024 tensor, multiplies it by itself, synchronizes and rejects any nonfinite result. Returns True on success. Uses the supplied device, so a CPU call is possible despite its name. Changes RNG state and allocates temporary memory; this is not a full training, storage or multi-GPU health test. |
| `set_seed(torch, seed)` | Seeds Python random and torch CPU/all CUDA RNGs, enables deterministic algorithms and disables cuDNN benchmark selection. Does not seed NumPy's global generator, restore earlier global settings or make unsupported deterministic kernels work. Augmentation separately derives a NumPy generator seed. |

## Model and Warmup Functions

| Function | Behavior and boundaries |
| --- | --- |
| `create_workload_model(args, torch)` | Routes coco/imagenet to the imported vision model factory, otherwise to create_model(args.model_tier, torch). Returns a model without moving it to the device, wrapping DataParallel or choosing a loss. Imported classification architecture is not the segmentation model below. |
| `create_model(tier, torch)` | Looks up base channels/depth: small 16/2, medium 32/3, large 64/4. Creates nested Block and SmallUNet classes and returns the latter on its default device. Invalid tier raises KeyError. No pretrained weights, checkpoint loading or model-state reset. |
| `create_model.Block.__init__(self, source, target)` | Initializes nn.Module and builds Conv2d(source,target,3,padding=1), in-place ReLU, Conv2d(target,target,3,padding=1), in-place ReLU. Default biases are present; no normalization, dropout, downsampling or residual branch. |
| `create_model.Block.forward(self, value)` | Applies the sequential two-convolution block and returns the activation. Requires compatible channels; no separate shape validation, loss or caching. |
| `create_model.SmallUNet.__init__(self)` | Builds depth blocks with target channels doubling at each level from one input channel, then a 1x1 convolution to one output channel. Small uses 16 then 32 channels. Despite the class name, there is no encoder/decoder, pooling, upsampling or skip connection. |
| `create_model.SmallUNet.forward(self, value)` | Applies all blocks in order, then the head. Padded convolutions preserve spatial dimensions; returns unbounded logits, not sigmoid probabilities or binary masks. |
| `model_metadata(model, args, torch)` | Counts parameter elements, reports workload name, tier, fixed fp32 label, first parameter's device, timing mode and validate_device_events result. May synchronize device events as a side effect. The precision label is descriptive, not a dtype inspection or enforcement. A parameterless model raises StopIteration. |
| `validate_device_events(torch)` | Records two CUDA timing events on the current stream, synchronizes and calls elapsed_time. Ordinary exceptions return False. Even True only establishes that event timing calls succeeded; measured steps still use wall time plus synchronization, not these events. |
| `run_model_warmup(torch, model, loss_function, optimizer, device, args)` | Creates a full zero batch with 3 channels/classification targets or one-channel spatial targets. ImageNet makes target class zero one-hot. Performs warmup_steps zero_grad, forward/loss, backward and optimizer.step calls, then synchronizes even when no steps run. Updates model parameters and optimizer state, so warmup is not merely a dry inference probe. Does not reset state afterward or switch train/eval mode. |

## Training and Scheduling Functions

| Function | Behavior and boundaries |
| --- | --- |
| `run_backend(torch, model, optimizer, loss_function, device, backend, context, measured_steps)` | For Aether, captures its cold-start gate and resets operation metrics. Starts GPU sampling, snapshots parent/optional Java resources and resets peak memory for requested CUDA devices. Times iteration through prepared_batches/scheduled_batches and train_step, retaining every step. Closes the iterator and stops sampling in nested finally blocks on iteration failure. On success, performs excluded reference sanity checks, summarizes, hashes sorted final state names/raw CPU bytes, records losses, prefetch and resource scopes, and returns a report. Exceptions before the iteration try, including sampler/resource setup, do not share that cleanup guarantee. No rollback of model/cache updates and no report on training failure. |
| `scheduled_batches(args, total_steps)` | Repeats batch_plan's contiguous order, yielding (indices, zero-based epoch) until exactly total_steps. Can truncate the last epoch; does not shuffle or use args.epochs as its own stopping rule. Nonpositive total yields nothing. With positive total and no batches it loops without yielding, so validated positive sample/batch counts are required. |
| `segmentation_sanity_metrics(torch, model, loss_function, device, context)` | Empty reference returns None. Stacks the first min(batch_size, reference length) unaugmented samples, normalizes tensors, transfers and runs under no_grad. Classification returns loss/target encoding/logit hash; segmentation thresholds sigmoid and masks at 0.5 and computes pooled intersection Dice and IoU. Empty prediction/target sets score zero via clamped denominators, not one. It does not call eval, restore mode, use held-out samples or compute per-class/per-image mean IoU despite the meanIoU key. Runs after measured training. |
| `train_step(torch, model, optimizer, loss_function, device, backend, context, batch_indices, step, epoch, *, prepared=None, prefetch_wait_ms=0.0)` | Inline path obtains context.batch and measures input preparation/wait; prepared path uses its supplied batch/counters/preparation time and reports supplied consumer wait. Augments, hashes training batch, normalizes CPU tensors, then measures blocking transfer, forward, loss, backward and optimizer separately with synchronization. Calls zero_grad before forward and returns counters, layouts, checksum, scalar loss and wall timings. It does not select a loss/optimizer, validate backend equivalence, reset parameters or retain the computational graph in the report. Errors propagate after any already-applied state changes. |

The backend wall includes iterator consumption and final synchronization but
excludes sampler startup, initial resource snapshots, post-training sanity checks,
model hashing and report construction. Epoch wall starts after the first yielded
batch; a later epoch transition is observed after its first batch has been
prepared. Epoch sums therefore are not a partition of all input waiting in the
backend wall, especially with prefetch/worker preparation.

The step wall begins after a prefetched item has been yielded, so consumer waiting
before yield is reported separately, not automatically included in stepWallMs.
Checksum and CPU normalization overhead contribute to step wall but lack their
own full timing fields. batchPrepareMs describes preparation effort; inputWaitMs
describes consumer wait when prefetched. Do not add those as disjoint costs.

Synchronized blocking transfers and phase boundaries deliberately constrain GPU
overlap. The utilization sampler and selected-device-count times wall GPU-hours
are measurements/descriptive accounting, not billing or proof of saturation.
Loader resource windows exclude startup, IPC and idle time; Java resources include
background engine work during the backend window. Final model hashes compare
model state, not optimizer state, clinical quality or future convergence.

## Augmentation and Tensor Layout Functions

| Function | Behavior and boundaries |
| --- | --- |
| `normalize_cpu_batch(torch, batch)` | Wraps NumPy images/masks with torch.from_numpy and makes default contiguous tensors. Rejects noncontiguity or strides unequal to expected_contiguous_stride, then returns tensors plus layout metadata. Does not convert dtype, pin memory, transfer devices or promise a copy: already contiguous arrays may share storage. Negative-stride NumPy arrays can fail before contiguous is called. Its canonical-stride check is stricter than is_contiguous alone for singleton dimensions. |
| `augment_batch(batch, context, batch_indices, step, epoch)` | None mode returns the same batch object. Otherwise seeds a new NumPy generator from SHA-256 of sorted JSON containing run_seed (or args.seed), epoch, step, ordered indices and mode. Copies arrays, optionally flips both horizontally, applies random scale/offset/noise to images, clips images to [-8,8], and returns float32 masks. Does not mutate cached originals or cache random augmentation. Any non-none string follows this implementation when called directly; CLI validation owns allowed modes. Assumes spatial four-dimensional masks, so classification shape compatibility belongs to the caller's chosen protocol. |
| `expected_contiguous_stride(shape)` | Computes reverse products of integer dimensions, returning canonical row-major strides. Empty shape returns an empty tuple. Does not validate sizes; zero and singleton dimensions can differ from other valid contiguous layouts. |
| `tensor_layout_metadata(torch, tensor)` | Reports shape, dtype, actual/expected strides, contiguity, channels-last contiguity for rank four, pinning, storage offset and pointer modulo 64/256. Pinning/channels-last probe exceptions become False; other query errors propagate. These are observations, not alignment guarantees, ownership metadata or a memory-lifetime check. Degenerate shapes can be both contiguous and channels-last; channels-last takes label precedence. |

## Verification and Remaining Coverage

Qualified AST checks cover these 25 declarations, not imported vision factories
or the rest of the 138-declaration module. Local tests exercise actual CPU torch
model construction, optimizer warmup/steps, tensor layout, augmentation and sanity
metrics, plus injected device checks and backend cleanup boundaries. They do not
certify CUDA/ROCm performance, multi-GPU behavior or the frozen H2 campaign.

The function index links the other partitions, jointly covering all 138 explicit
benchmark declarations. These training entries are one partition, not evidence
that the broader repository documentation goal is complete.
