# H2 Training Workload And Frozen Pilot

[Onboarding index](README.md) | [Training cache and Python](TRAINING-CACHE-AND-PYTHON.md) | [Experiments and profiling](EXPERIMENTS-AND-PROFILING.md)

This guide describes the separately committed H2 harness, not a promise that the
active worktree has all of its files. The experiment asks whether reuse of prepared
artifacts recovers initial storage cost across repeated dataset revisions. It is
not a clinical model evaluation or a new storage micro-optimization campaign.

## Source Boundaries

| Identity | Frozen value |
| --- | --- |
| Storage tag | `aether-v0-streaming-v1` |
| Storage commit | `00760e5f31fa31a17e69522539a3b60318ca9bf0` |
| H2 harness commit | `5ad8e6f367227ed1a31c849325803d1f1c08b76d` |
| Protocol | `aether-h2-streaming-pilot-v1` |
| Configuration | `configs/paper/oct5k-h2-streaming-pilot.json` in the harness checkout |

The [frozen harness](https://github.com/Grgur00/Aether-Engine/tree/5ad8e6f367227ed1a31c849325803d1f1c08b76d)
adds an experimental bootstrap entry point and orchestration. Existing Java
storage implementation files are unchanged from the storage commit. In the local
research workspace, this checkout is `build/h2-streaming/source`; its launch and
freeze receipts are outside that checkout. Build directories are not durable
documentation dependencies: use the committed snapshot to reconstruct the harness.

Do not launch the H2 configuration with the older active-worktree runner. Use a
separate checkout of the exact harness commit and its
[protocol notes](https://github.com/Grgur00/Aether-Engine/blob/5ad8e6f367227ed1a31c849325803d1f1c08b76d/kaggle/H2-STREAMING-PILOT.md).
Changing source or runtime packages requires a new campaign identity; do not
modify receipts to resume a changed implementation.

## What The Model Does

The task is binary pixel segmentation of genuine OCT5K image/mask pairs. The
input is a grayscale `1 x 256 x 256` eye scan; the target is a mask with the same
shape. Every nonzero semantic annotation is foreground. This collapses label
classes, so it is not a multiclass anatomical segmentation task and does not
establish a disease diagnosis.

The function `create_model("small", torch)` names its class `SmallUNet`, but it is
**not a full U-Net**. There is no pooling, encoder-decoder, or skip connection:

```text
grayscale image
  -> 3x3 convolution: 1 -> 16 channels, ReLU
  -> 3x3 convolution: 16 -> 16 channels, ReLU
  -> 3x3 convolution: 16 -> 32 channels, ReLU
  -> 3x3 convolution: 32 -> 32 channels, ReLU
  -> 1x1 convolution: 32 -> 1 channel
  -> one foreground logit per pixel
```

Convolutions preserve spatial dimensions. `BCEWithLogitsLoss` compares logits
with the binary targets; AdamW updates weights with learning rate `0.001`.
Training uses FP32 tensors on CUDA, batch 16, and 20 epochs at every version.
The small network supplies repeatable GPU work rather than a state-of-the-art
accuracy claim. See the
[model and preprocessing implementation](../../clients/python/benchmark_gpu_segmentation.py).

## Cached Versus Fresh Work

The canonical transform loads image and semantic mask, resizes with bilinear
image/nearest-neighbor mask interpolation, blurs the image, clips and normalizes
its 1st/99th percentiles, and applies deterministic denoising (four preprocessing
passes in this workload). RGB mask visualizations are rejected. Cached tensors
are FP16 images and uint8 masks. Training stacks them as FP32 and applies seeded
light augmentation after lookup; augmentation is not baked into the cache.

Every backend receives the same prepared tensors, sample order, augmentation
conditions and matched model/optimizer seed. Tensor hashes and initial/final
model-state hashes validate paired consistency. Training-time preprocessing
misses fail the run rather than silently changing the measured workload.

**A fresh model and optimizer are created at every version**, followed by the
existing synthetic warmup updates and 20 real-data epochs. This is repeated
training over revised datasets, not continual training of one model checkpoint.
The Aether daemon and artifact store persist; model weights do not. Model setup
and warmup are charged separately within the lifecycle endpoint.

## Five-Version Lifecycle

| Version | Samples | New artifacts | Training sample requests |
| --- | ---: | ---: | ---: |
| V0 | 1200 | 1200 | 24000 |
| V1 | 1260 | 60 | 25200 |
| V2 | 1323 | 63 | 26460 |
| V3 | 1389 | 66 | 27780 |
| V4 | 1458 | 69 | 29160 |

Each complete backend/block trajectory has 132600 training sample requests.
Manifests are frozen nested prefixes of a seeded permutation, not independently
acquired clinical revisions. Prior artifacts retain source/content/transform
identity; reuse per update is approximately 95% (`previous samples / current samples`).

Five paired blocks compare Aether, incremental mmap, MONAI PersistentDataset and
MONAI LMDBDataset with rotated backend ordering. Aether starts once before V0
and stops once after V4. The same PID remains alive while baseline jobs execute;
baseline handles reopen per version. These residence and serialization/durability
differences belong in interpretation, not just throughput charts.

At V0, `H2BulkTrainingDaemon` stages batch-16 PUT_MANY bodies over a bounded
loopback bootstrap connection to the frozen offline bulk writer. Staged replies
are not durable. Explicit finish builds/forces SSTables at a 32 MiB target,
performs one authoritative streaming verification, and commits the manifest.
The offline loader closes before the **same JVM** opens the ordinary durable
training daemon. Existing engine-open recovery validation remains and is charged
to V0 preparation. V1-V4 use normal incremental admissions; no restart fallback
is allowed. Compaction stays enabled and version boundaries require IDLE/debt 0.

Checksums, artifact identity and durability barriers remain intact. Prefetch is
zero, tracing/JFR are off, and no new heap/GC tuning is applied during H2.

## Reading The Results

```text
stage = startup + scanAdmission + modelSetup + training + drain + close
cumulative(Vk) = sum of measured stages V0 through Vk
Aether/baseline cumulative ratio = Aether cumulative / baseline cumulative
```

One startup is charged to V0 and one shutdown to V4. A ratio below 1 favors
Aether. The analyzer also retains the opposite-direction baseline/Aether speedup;
always state which direction is plotted. Report V0 preparation, each update's
preparation and training, cumulative lifecycle, reuse, and first observed
break-even version. A first crossing is not necessarily a sustained advantage.

`scanAdmission` includes native cache opening, lookup and preparation, accommodating
MONAI LMDB's eager population. Shared preflight/reference work, worker imports,
correctness readback and evidence packaging are outside this endpoint. Linux
`write_bytes` is a scoped process-I/O observation including validation, excluding
service startup/final exit; it is not logical artifact volume. Unsupported counters
are null, not zero. A process restart does not imply a cold OS page cache.

The earlier persistent pilot skipped V0 training and used ordinary V0 admission.
Its cumulative time cannot be directly pooled with or subtracted from this H2
endpoint. The final streaming JFR was diagnostic evidence, not H2 training
evidence; it does not change the previously failed 750 ms performance gate.

## Developer Checks

From the frozen harness checkout, with the scientific Python environment installed:

```powershell
.\gradlew.bat --no-daemon :modules:aether-training-cache:test :modules:aether-training-cache:paperRuntimeClasspath
$env:PYTHONPATH = 'scripts;clients/python'
$env:AETHER_JAVA_TEST = '1'
python -m pytest scripts/tests/test_h2_bootstrap.py -q --basetemp build/h2-doc-fixture
```

The real fixture checks all four backends, all five versions, the same-PID handoff,
complete-block resume and disconnect-before-finish behavior on tiny CPU data.
Its timings are not performance evidence. `--basetemp` is disposable and can be
cleared on a later run. Incomplete persistent blocks cannot be resumed as an
uninterrupted service; preserve their evidence and use a new campaign output.

Remote correctness and GPU smoke gates precede the pilot. RUNNING only establishes
launch, not successful completion. Validate result checksums, source/protocol,
runtime identity, every completed block and failures before reporting findings.
This is exploratory; no 24-block H2 confirmatory campaign is automatically launched.
