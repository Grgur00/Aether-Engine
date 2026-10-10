# Longitudinal Stage Worker Functions

[Function index](FUNCTION-INDEX.md) | [Runner and analysis](LONGITUDINAL-RUNNER-FUNCTIONS.md) | [Comparison adapters](RESEARCH-ADAPTER-FUNCTIONS.md)

Source: [longitudinal_worker.py](../../scripts/longitudinal_worker.py). All eight
explicit functions are covered. This is the active **older** longitudinal worker,
not a substitute for the preserved streaming H2 candidate. It trains V1-V4 only;
V0 prepares and validates storage, with zero epochs and null model hashes.
Changing that boundary changes the experiment.

## Architecture and Phase Boundaries

```text
request -> parse fixed workload options -> load manifest sources
        -> CPU fixture or mandatory CUDA -> samplers/process snapshots
        -> own daemon or use caller's persistent daemon
        -> engine policy and prior-entry check
        -> scan/open/admission -> strict drain
        -> V1-V4 fresh model setup + warmup -> epochs -> strict drain
        -> excluded tensor/model/count validation -> close -> report
```

Each stage opens a fresh dataset/client even when the external Aether JVM stays
alive. Restart-per-version owns a daemon context; persistent-per-block must receive
an external service, and refuses to silently launch a replacement. External
startup is zero here; PersistentService charges the single startup to V0 and
shutdown to V4. This worker alone does not establish all five stages share a PID.

## Function Reference

| Function | Behavior and boundaries |
| --- | --- |
| `DiskSampler.__init__(directory)` | Retains the path, allocates a stop Event and mutable sample/error lists, and constructs an unstarted daemon thread targeting run. Does not create the directory or validate ownership. |
| `DiskSampler.sample()` | Appends disk_usage output. Ignores FileNotFoundError to tolerate disappearing compaction files; stores strings for other OSError values. Other exception types propagate, or terminate the sampling thread when raised there. |
| `DiskSampler.run()` | Waits one second on the stop Event before every sample; exits when signaled. No independent failure/retry budget, synchronization lock around lists or exact peak guarantee. |
| `DiskSampler.start()` | Takes an immediate sample and starts the thread. Intended once; repeated thread start raises. Directory absence does not prevent starting because sample ignores FileNotFoundError. |
| `DiskSampler.stop()` | Signals stop, joins without timeout, takes another sample and returns maxima, sample count, error strings and a lower-bound scope. Allocated peak ignores None samples; logical peak defaults to zero. Repeated stop adds another final sample. Stop before start can raise while joining; a blocked sample can block join. |
| `model_hash(model)` | Sorts state_dict names, hashes each name and detached CPU-contiguous NumPy bytes. Includes buffers as well as parameters. Does not explicitly encode dtype, shape, separators or architecture; equal raw bytes/names can hash equally for differently shaped tensors. Transfers and conversion are validation work, not a training metric. |
| `strict_drain(port)` | Delegates adapter drain. A truthy report additionally requires state IDLE, debtBytes exactly zero and no failure count; otherwise raises. None/falsey reports pass through. Does not impose its own timeout or retries. |
| `execute(request)` | Owns one version's workload, dataset and optional daemon context. Validates persistent-service use and engine policy/prior count, admits exactly count-minus-previous artifacts, runs fresh model training only after V0, requires no training-time preprocessing, validates ordered tensors/reference/counts, and returns phase/resource/service metrics. Finally stops samplers, closes remaining dataset and exits an entered manager. Setup before the try is not protected by that finally, and cleanup errors can mask original failures. |

## What the Model Does

The imported [create_model](../../clients/python/benchmark_gpu_segmentation.py)
constructs the small tier with 16 then 32 channels. Each of two blocks has two
3x3 padded convolutions and in-place ReLU; a 1x1 head produces one logit per pixel.
Input/output spatial dimensions stay unchanged. Despite the class name SmallUNet,
there is no downsampling, upsampling, encoder-decoder path or skip connection.
The task is binary image segmentation: compare each output logit to the mask with
BCEWithLogitsLoss, optimizing with AdamW at learning rate 0.001.

Each trained version seeds and builds a new model and optimizer. Zero-input
warmup runs actual backward/optimizer steps, so initialModelSha256 is a
**post-warmup** hash, not pristine random initialization. These are independent
per-version training jobs, not continual fine-tuning of one model across revisions.
The model supplies repeatable ML work for lifecycle measurements; this worker
does not report held-out Dice/IoU, clinical validity or generalization quality.

Cached deterministic tensors are float16 image/uint8 mask; the training batch
stacks/casts both to float32, applies uncached augmentation and transfers them to
the device. Per-batch synchronization makes epochs synchronous. Final model/hash
equality is a paired correctness check, not evidence of model quality.

## Timing and Metrics

The endpoint is the sum of startup, modelSetup, scanAdmission, training, drain
and close. Source loading and transform/batch construction precede sampling and
timing. Engine-info checks and lease writes are not separately measured endpoint
phases (lease writing falls inside the startup timer); engine checks fall outside.
Both required strict drains are charged. Model setup includes seeding, creation,
transfer, optimizer/loss construction and warmup; initial hash is excluded.
Validation includes readback/final model/count/engine checks and is separate.

Epoch inputWaitMs includes fetch, stack/casts and augmentation. It excludes device
transfer and optimizer work; it is not hardware GPU idle. Full training wall time
includes transfer, synchronized optimizer work, losses and loop bookkeeping.
Request counts must equal sample count times epochs on trained versions.

Disk peaks sample only the live store, not checkpoint copies. Sampling continues
through validation and closure, and is a lower bound. Process counters include
validation/instrumentation; Java counters exclude daemon startup/exit. Final
disk_usage runs after close and can itself raise. The success path stops both
samplers, then finally stops them again; repeated disk stop can append another
sample, but the returned peak dictionary was computed before that final call.

The reported zero trainingCacheMisses derives from enforcing no additional
transform calls, not from a universal native cache-miss counter. Unique artifacts
use engine cacheEntries, mmap index size, MONAI .pt file count or pinned LMDB reader
entry count, depending on backend. Unsupported/unexpected framework layouts can
raise. A pre-existing extra artifact fails exact-count validation.

## Command and Failure Ownership

The module-level command requires request/output JSON paths, optionally writes a
lease with its PID, calls execute, then writes output. It removes the lease in a
finally; a killed process cannot do so. The worker updates the lease with a daemon
PID when available. A request/lease initialization failure before the command's
try is not handled by its final cleanup. Output failure can happen after cache
work completed; the runner decides whether the stage evidence is committed.

Sampler starts and initial parent snapshot occur before execute's try. Once inside,
cleanup exits managers with None exception arguments, even on body failure; it
does not pass the active exception to the context owner. Dataset close failure
can prevent later manager cleanup in that same finally. Read this alongside
[process ownership](RESEARCH-PROCESS-FUNCTIONS.md), not as an unconditional
resource-cleanup guarantee.

## Verification Scope

[Documentation contracts](../../scripts/tests/test_longitudinal_documented_contracts.py)
cover disk sampling errors, strict drain states, model-hash limitations and a tiny
real CPU/mmap V0/V1 path. The fixture confirms V0 has no training and V1 does;
it is not a benchmark. Java service persistence and CUDA evidence require separate
integration runs. AST coverage binds all eight explicit function entries.
