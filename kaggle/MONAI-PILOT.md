# OCT5K MONAI Comparative Pilot

This is a new exploratory campaign, not additional confirmatory evidence for the
previous mmap comparison. No engine tuning is included. A larger real dataset is
not currently available; larger-scale and repeated-update studies remain pending.

## Frozen Pilot Settings

- OCT5K: 1,430 -> 1,505 samples, 75 appended, 1,430 unchanged.
- Five fresh paired repetitions, randomized backend order, 20 epochs, batch 16.
- Backends: AetherPersistentDataset, incremental mmap, MONAI PersistentDataset,
  MONAI LMDBDataset. MONAI 1.6.0 and lmdb 2.1.1 are pinned.
- Identical deterministic OCT transforms: 256x256, four preprocessing passes,
  float16 image and uint8 mask cache artifacts. Existing light random augmentation
  runs after retrieval, identically seeded for every backend.
- Small segmentation model, AdamW, BCEWithLogitsLoss, 12 zero-input warmup steps.
- Prefetch 0, workers 0, server tracing off, Aether asynchronous compaction on.
- Pilot main endpoint: V2 cache-open, admission scan, and 20-epoch training time,
  including completed background compaction. Checkpoints 1, 5, 10, 20 include
  admission cost; all individual epoch timings are retained.

Each V1 cache is populated once, closed, and reopened for V2 without deleting
reusable entries. All backends receive a complete V2 admission scan. MONAI LMDB's
eager constructor is inside the timer. Exactly 1,430 initial and 75 update
preprocessing calls are required, with none during subsequent training. Tensor
checksums and final paired model hashes must match; a mismatch aborts the run.
Content and transform identity are included in every cache key, including LMDB.

## Timing And Limitations

V1 population, V2 preparation, epoch timings, total update-and-retrain time, and
full lifecycle (initial population + update/training + service startup) are
reported separately. Source hashing, reference validation, model setup/warmup,
readback checks, and dataset close are excluded. Java service startup is excluded
from the update endpoint but separately recorded and included in full lifecycle.
OS page caches are uncontrolled. This is a warm V1-cache experiment, not a
machine-reboot/cold-storage experiment.

Aether and mmap use TensorDictCodec; MONAI uses its native torch serialization.
Tensor values/dtypes/shapes are identical, but serialization, indexing, and
batching are intentionally native and must be reported as implementation
differences. Aether uses DURABLE admission, mmap uses fsync, and LMDB uses its
default synchronous transactions; PersistentDataset has no equivalent fsync
guarantee. This is not a durability-equivalence experiment.
The LMDB initial map capacity is twice the uncompressed payload size with a
16 MiB minimum; native automatic growth remains enabled.

Reports include logical/allocated disk bytes, Linux per-process I/O counters,
preprocessing counts, derived training hits, input-wait time, and sampled GPU
utilization. Input wait is a synchronized host-side proxy, not a direct hardware
GPU-idle measurement. Unsupported counters are null, never silently zero.

The fixed sample count is five blocks. Descriptive paired log-ratio CIs are
exploratory; do not select or publish a confirmatory hypothesis after seeing
these results. Freeze a separate protocol and new sample size for confirmation.

## Run

Prepare and upload a clean, committed source snapshot with the normal private
Kaggle workflow:

```powershell
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py prepare --mode monai --epochs 20 --prefetch-depth 0 --pilot-repeats 5 --no-server-trace --dataset-config configs/paper/oct5k-pilot-20ep.json --dataset-source grgur321/aether-oct5k-pilot
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py upload-source --update
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py run
```

Inside Kaggle, the runner installs the pinned MONAI dependencies without changing
the base PyTorch installation and invokes `scripts/monai_comparison.py`. Results
are bundled in the normal checksummed results ZIP. Check launch status once;
there is no need to poll the running campaign.

The reusable adapter is in `clients/python/aether_ml/monai/dataset.py`. It requires
explicit content identity and a deterministic transform identity; random
augmentation must stay outside that cache boundary. See the package README for
cache invalidation and integration guidance.
