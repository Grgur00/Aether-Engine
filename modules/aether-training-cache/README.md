# Aether Training Cache

The current implementation provides persistent inline and segmented values, deterministic
transformation keys, checksums, bounded eviction, single-flight computation, namespace lifecycle
operations, prefetch, three durability modes, a loopback daemon, and a Python client.

Run the Java tests:

```text
./gradlew :modules:aether-training-cache:test
```

Run tests and generate JSON results:

```text
./gradlew :modules:aether-training-cache:trainingCacheTestReport
```

The report is written to `modules/aether-training-cache/build/reports/training-cache-test.json`.

Run the cold/warm benchmark and write its JSON result:

```text
./gradlew :modules:aether-training-cache:trainingCacheBenchmark -Psamples=10000 -PpayloadBytes=256
```

Run the benchmark with Java Flight Recorder enabled:

```text
./gradlew :modules:aether-training-cache:trainingCacheBenchmark -Psamples=10000 -PpayloadBytes=matrix -PjfrFile=build/training-cache.jfr
```

Inspect the recording with the JDK Flight Recorder command-line tool:

```text
jfr summary build/training-cache.jfr
jfr view hot-methods build/training-cache.jfr
```

Open the `.jfr` file in JDK Mission Control for allocation, CPU, file-I/O, thread, and lock views.

Run every proposal payload size in one matrix:

```text
./gradlew :modules:aether-training-cache:trainingCacheBenchmark -Psamples=1000 -PpayloadBytes=matrix
```

Matrix sizes are 256 B, 1 KB, 4 KB, 16 KB, 64 KB, 256 KB, 1 MB, and 4 MB. The JSON output is
written to `modules/aether-training-cache/build/training-cache-benchmark.json`.
Each result also includes `mappedSamplesPerSecond` and `mappedNanos` for the read-only segment-view
path. Linux/macOS use `FileChannel.map`; Windows uses a read-only heap fallback because Windows
locks mapped files until the buffer is reclaimed.

Start the development daemon. It prints the selected port when ready:

```text
java -cp modules/aether-training-cache/build/classes/java/main;modules/aether-training-cache/build/resources/main io.aetherdb.training.cache.TrainingCacheDaemon C:\\tmp\\aether-cache 9484
```

Recommended Windows workflow for the Python pipeline uses two terminals. First populate matching
benchmark keys:

```text
./gradlew :modules:aether-training-cache:trainingCachePopulate -PcacheDir=C:\\tmp\\aether-cache -Psamples=128 -PpayloadBytes=1048576
```

Then keep the daemon running in a second terminal:

```text
./gradlew :modules:aether-training-cache:trainingCacheDaemon -PcacheDir=C:\\tmp\\aether-cache -Pport=9484
```

Finally run the Python benchmark from the repository root. The segment directory is inside the
same cache directory:

```text
python clients/python/benchmark_training_pipeline.py --port 9484 --segment-directory C:\\tmp\\aether-cache\\segments --samples 128 --payload-bytes 1048576 --output build/python-training-pipeline.json
```

Start the Unix-domain daemon on Linux or macOS:

```text
java -cp modules/aether-training-cache/build/classes/java/main:modules/aether-training-cache/build/resources/main io.aetherdb.training.cache.TrainingCacheUnixDaemon /var/tmp/aether-cache /var/tmp/aether-training-cache.sock
```

Use the Python client from `clients/python`:

```python
from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint

transform = TransformationFingerprint.from_mapping({"tokenizer": "demo-v1", "max_length": 512})
key = CacheKey("train", "sample-1", transform)
with AetherTrainingCache(port=9484) as cache:
    value = cache.get_or_compute(key, lambda: b"tokenized sample")
```

For a Unix socket, use `AetherTrainingCache(unix_socket="/var/tmp/aether-training-cache.sock")`.
For TLS, create an `ssl.SSLContext` with `load_verify_locations`, `load_cert_chain`, and (for
mutual authentication) a client certificate, then pass it as `ssl_context`.

Use the local AetherML prototype API when preprocessing outputs need deterministic artifact
identity, provenance, snapshots, and experiment records:

```python
import aetherml

store = aetherml.open("./experiments")

@store.cached_transform(version="1", parameters={"normalization": "z-score"})
def normalize_oct(image):
    return normalize(image)

normalized = normalize_oct(image)
artifact_id = normalize_oct.aether_artifact_id(image)
snapshot = store.create_snapshot("oct-train", [artifact_id], {"split": "train"})
```

Artifacts are serialized once, written through a temporary file, verified by SHA-256, and then
published with atomic metadata and cache-index updates under the selected store directory. Artifact
ids identify committed transformation results, while blob storage is deduplicated by content hash,
so no-op stages can still appear as distinct provenance nodes. The API also exposes `parents`,
`children`, `lineage`, `depends_on`, `restore_snapshot`, `compare_snapshots`, and
`experiments_using` for provenance and reproducibility workflows. Experiment lookup is lineage-aware,
so a query for an upstream raw-scan artifact returns experiments whose recorded dataset snapshots or
output artifacts depend on it.

Large array, volume, or checkpoint files can be committed without forcing them through Python
object deserialization:

```python
metadata = store.commit_file(
    "artifacts/oct-volume.npy",
    cache_key=store.transformation_key(
        input_hash="scan-001",
        transformation_name="oct_volume_export",
        transformation_version="1",
    ),
    transformation_name="oct_volume_export",
    transformation_version="1",
)
payload = store.load_artifact_bytes(metadata.artifact_id)
path = store.artifact_path(metadata.artifact_id)
```

Call `store.verify_snapshot(snapshot_id)` to prove that every artifact in a dataset snapshot still
has metadata, a present blob, and the expected checksum. `store.snapshot_manifest(snapshot_id)`
exports the snapshot and lineage artifact metadata needed to audit a previous experiment without
loading the full dataset.

After an interrupted preprocessing run, call `store.validate()` to inspect the visible artifact
graph. `store.recover()` removes abandoned temp files and stale cache-index entries; pass
`remove_orphan_artifacts=True` to also delete artifact blobs that are not referenced by metadata.
`store.operation_metrics()` returns per-operation latency distributions for cache lookup, artifact
commit, snapshot creation, metadata lookup, and artifact retrieval. Each distribution includes count,
mean, median, standard deviation, a 95% confidence interval, and p50/p95/p99 latency. Use
`store.reset_operation_metrics()` before an isolated measurement window.
`store.storage_metrics(raw_dataset_bytes=..., baseline_preprocessed_bytes=...)` reports raw dataset
size, baseline preprocessed size, cached artifact bytes, logical artifact bytes before content
deduplication, metadata and cache-index bytes, total store bytes, write amplification, cache
amplification, and metadata overhead. The local Python artifact-store prototype reports `walBytes`
and `compactionWriteBytes` as `0`; Java engine reports can fill those fields when measuring the
full embedded engine.
`aetherml.environment_report(dataset_version=..., code_commit=...)` captures controlled-run
metadata for reproducibility: CPU, GPU availability/devices when PyTorch exposes them, RAM, disk,
OS, Java version, Python version, PyTorch version, dataset version, and Git commit.

For large segmented values, request metadata and map the known local segment directory:

```python
from aether_training_cache import MappedSegmentRegistry, numpy_view, torch_view

reference = cache.get_ref(key)
with MappedSegmentRegistry(r"C:\\tmp\\aether-cache\\segments") as mapped_segments:
    view = mapped_segments.view(reference)
    array = numpy_view(view, dtype="int32")
    tensor = torch_view(view, dtype="int32")
```

`get_many_refs(keys)` performs one daemon request for a batch, and `get_view(key, mapped_segments)`
returns a validated memoryview without converting it to Python bytes. The same API is available
under the proposed import name `aether_cache`.

Benchmark the Python delivery pipeline after populating the daemon cache:

```text
python clients/python/benchmark_training_pipeline.py --port 9484 --segment-directory C:\\tmp\\aether-cache\\segments --samples 128 --payload-bytes 1048576 --output build/python-training-pipeline.json
```

This reports materialized bytes, one-round-trip references, mapped view creation, full-touch and
full-reduction consumption, NumPy views, and optional PyTorch CPU/GPU stages. Metadata-only stages
report `bytesPerSecond: null`. Install `numpy` and `torch` to enable those measurements.

The batch loader is available as `AetherDataLoader(cache, keys, registry, batch_size=32,
workers=2, prefetch_batches=2, pin_memory=False)`. Each yielded batch uses one reference request;
worker threads and prefetch depth are bounded by the constructor settings.

For PyTorch-style input pipelines, wrap an existing indexable dataset with AetherML cached
transforms:

```python
from aetherml import AetherDataLoader, AetherDataset

dataset = AetherDataset(
    source=oct_dataset,
    transforms=[
        store.cached_transform_function(resize_oct, version="2", parameters={"size": [512, 512]}),
        store.cached_transform_function(normalize_oct, version="1", parameters={"method": "zscore"}),
    ],
)

loader = AetherDataLoader(dataset, batch_size=16, num_workers=8)
```

When the dataset is built from `CachedTransform` instances, each stage is committed as an immutable
artifact and the next stage records the previous stage's artifact id as provenance. `lineage` can
therefore reconstruct the processed sample back through its preprocessing chain, while
`experiments_using` can answer which recorded training runs depended on an intermediate or raw
artifact. `AetherDataLoader` delegates to PyTorch's `DataLoader` when worker/process options are
used, and provides a dependency-free sequential fallback for tests and lightweight scripts.

Small token records can use the packed inline path:

```python
batch = cache.get_many_values(batch_keys)
value = batch.value(0)
```

The response contains one shared buffer plus ordered statuses, offsets, and lengths. This avoids
one socket response allocation per sample. The real-tokenizer comparison is available with:

```text
python clients/python/benchmark_real_tokenizer.py --samples 1000 --epochs 5 --encoding cl100k_base --max-length 512 --aether-port 9491 --output build/real-tokenizer-aether.json
```

Install optional adapters with `pip install numpy torch`. `get_ref` returns `None` for inline
values; use `get` when the payload is small or when a materialized byte string is required.

Run the deterministic preprocessing break-even sweep:

```text
python clients/python/benchmark_preprocessing_break_even.py --samples 128 --epochs 5 --output build/preprocessing-break-even.json
```

Run the real deterministic tokenizer comparison. This uses pinned `tiktoken` encoding metadata,
NFKC normalization, tail truncation, AETK binary token records, and static pretokenized mmap:

```text
python clients/python/benchmark_real_tokenizer.py --samples 1000 --epochs 5 --encoding cl100k_base --max-length 512 --output build/real-tokenizer.json
```

Run the statistically controlled comparison with independent processes, five warmup epochs, ten
measured epochs, randomized backend order, and 95% confidence intervals:

```text
python clients/python/benchmark_tokenizer_statistics.py --samples 128 --batch-size 128 --warmup-epochs 5 --epochs 10 --runs 10 --port 9501 --output build/tokenizer-statistics-10runs.json
```

For very short documents, classify the workload as `BYPASS` when measured cache reuse is slower
than tokenization. The report includes operation counts proving persistent connections and batched
population/retrieval.

The sweep covers 10 us through 100 ms per sample. Break-even requires the cached cumulative time
to beat recomputation by at least one percent, and every reuse checksum is compared with the
populate checksum. The default uses 1,000 samples. Use
`--targets-ms 0.01,0.025,0.05,0.1,0.25,0.5,1,2,5` to focus the larger experiment on the low-cost
crossover region.

Run worker and prefetch combinations after populating the daemon cache:

```text
python clients/python/benchmark_dataloader.py --port 9484 --segment-directory C:\\tmp\\aether-cache\\segments --samples 128 --payload-bytes 1048576 --batch-size 32 --output build/dataloader-sweep.json
```

Measure CPU training input wait and simulated model compute:

```text
python clients/python/benchmark_cpu_training.py --port 9484 --segment-directory C:\\tmp\\aether-cache\\segments --samples 128 --batch-size 32 --epochs 3 --compute-ms 5 --workers 2 --prefetch-batches 2 --output build/cpu-training.json
```

Run the local AetherML cold/warm/partial-cache benchmark:

```text
python clients/python/benchmark_aetherml.py --samples 128 --repeat 5 --changed-percent 30 --output build/aetherml-benchmark.json
```

The report includes filesystem recompute, manual-preprocessed, and
high-performance packed-dataset-equivalent baselines, AetherML cold, warm, and partial-cache
timings, latency percentiles, standard deviations, 95% confidence intervals, checksum sets,
snapshot restore timing, operation-level AetherML latency metrics, snapshot verification, a
reproducibility manifest, snapshot differences, and storage-overhead metrics for raw bytes, cached
bytes, metadata bytes, write amplification, cache amplification, and metadata overhead. The packed
baseline materializes transformed samples into one contiguous byte payload with offset indexing,
which provides a local LitData-style reference point without adding an external benchmark
dependency. Reports also embed environment metadata for CPU, GPU, RAM, disk, OS, Java, Python,
PyTorch, dataset version, and code commit. Use `--store <path> --reset-store` to run against a
persistent benchmark directory that should be replaced before measurement.

Run the packaged benchmark framework entry point:

```text
aether-bench --profile smoke --dataset oct --pipeline segmentation --workers 0 --output-dir build/aether-bench
```

For source-tree runs before installing the Python package, use:

```text
PYTHONPATH=clients/python python clients/python/aether_bench.py --profile smoke --dataset oct --pipeline segmentation --workers 0 --output-dir build/aether-bench
```

The command writes a single combined `aetherml-report.json` plus `summary.json` under the output
directory. Both include command status, a `keyStats` rollup for quick inspection, and `reportData`
with the full AetherML benchmark, OCT workload, and crash-recovery payloads. The individual child
JSON files are also retained for scripts that want to consume one experiment at a time.
Crash-recovery rollups include `trials`, `acknowledgedArtifacts`, `recoveredArtifacts`,
`lostAcknowledgedArtifacts`, and `successfulRecoveries`; the success criterion for acknowledged
cache work is `lostAcknowledgedArtifacts = 0`.
When the GPU profile produces backend measurements, `exports.json`, `tidy-gpu-backends.csv`,
`tidy-gpu-runs.csv`, and `tidy-gpu-steps.csv` are written beside the combined report for plotting
and statistical analysis.

For AMD ROCm validation on an RX 7900 XTX, use the gated 128-sample acceptance profile before the
full performance run:

```text
PYTHONPATH=clients/python python clients/python/aether_bench.py --profile gpu-acceptance --output-dir build/aether-bench-gpu-acceptance
PYTHONPATH=clients/python python clients/python/aether_bench.py --profile gpu-training --output-dir build/aether-bench-gpu
```

The acceptance profile defaults to 3 independent runs. The full training profile defaults to 10
independent runs. The same combined report contains `keyStats.gpuTraining` for quick inspection,
`reportData.benchmark_gpu_segmentation.runAggregate` for mean/median/stdev/p50/p95/95% confidence
interval rollups, and `reportData.benchmark_gpu_segmentation.runs` for raw per-run backend and
per-step records.

If PyTorch is not a ROCm build, `torch.cuda.is_available()` is false, or the device name does not
match the expected AMD GPU, this profile writes `status = SKIPPED_UNSUPPORTED_ACCELERATOR` in the
combined report and does not fall back to CPU. A valid GPU report includes four training backends:
`RAW_RECOMPUTE`, `AETHER_CACHE`, `STATIC_PREPROCESSED_MMAP`, and `RAM_READY`.
It records per-step raw timings, backend equivalence checks, deterministic cache invalidation
checks, Aether-vs-baseline comparisons, and `trainingBreakEvenEpoch` when Aether's lifecycle beats
raw recomputation by the configured margin.

Run the deterministic OCT segmentation-shaped workload:

```text
python clients/python/oct_segmentation_workload.py --samples 64 --epochs 3 --height 64 --width 64 --resize 32 --batch-size 8 --output build/oct-segmentation-workload.json
```

By default this generates synthetic OCT-like scans and masks. To run against downloaded public OCT
files or another local dataset, point the workload at a directory of raw grayscale samples or image
files:

```text
python clients/python/oct_segmentation_workload.py --input-dir datasets/oct --samples 64 --height 64 --width 64 --resize 32 --output build/oct-local-workload.json
```

Files with exactly `height * width` bytes are treated as raw grayscale OCT samples. Image files are
decoded with Pillow when available; otherwise the file contents are reduced into deterministic
fixed-size byte grids so ingestion remains dependency-free for reproducibility smoke tests. The
workload runs cached decode, resize, z-score normalization, mask preparation, deterministic
augmentation, and a conventional segmentation-style training loop. The output records cold and warm
training timings, samples/sec, batch latency percentiles, batch preparation latency,
time-to-next-batch, input-wait nanos, simulated GPU active/idle nanos, simulated GPU utilization,
dataset snapshot metadata and verification, experiment metadata, restored artifact checksums,
environment details, and artifact-store validation status. The simulated GPU fields are CPU-derived
proxies for data-pipeline starvation; real GPU device details are still recorded in the environment
block when PyTorch exposes them.

Run the Python artifact-store fault-injection campaign:

```text
python clients/python/aetherml_crash_recovery.py --trials 25 --output build/aetherml-crash-recovery.json
```

The campaign terminates worker processes after temp writes, orphan artifact writes, dangling cache
updates, and acknowledged commits. After every trial it runs recovery and verifies that visible
metadata is consistent and acknowledged committed artifacts remain readable.

Regenerate the prototype AetherML report with one command:

```text
python clients/python/reproduce_aetherml.py --samples 32 --trials 8 --output-dir build/aetherml-reproduce
```

This runs the local cache benchmark, the OCT segmentation-shaped workload, and the Python
artifact-store fault-injection campaign, then writes `aetherml-report.json` and `summary.json` with
pass/fail status, `keyStats`, and full `reportData` beside the individual child reports.

The daemon currently uses loopback TCP and is intended for trusted local development. TLS,
Unix-domain sockets, and forced-process recovery are available through the Java daemon classes.

Run the forced-process recovery campaign:

```text
./gradlew :modules:aether-training-cache:trainingCacheCrashCampaign -Ptrials=1000
```

For Unix-domain sockets, construct `TrainingCacheUnixDaemon` with a socket path on Linux or macOS.
For shared-node TLS, construct `TrainingCacheTlsDaemon` with an `SSLContext` backed by a server
key store and trusted client key store, and pass `true` for client-certificate authentication.
