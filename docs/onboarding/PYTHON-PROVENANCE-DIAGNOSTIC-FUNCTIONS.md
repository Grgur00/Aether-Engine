# Filesystem Provenance Validation and Diagnostic Functions

[Function index](FUNCTION-INDEX.md) | [Store publication](PYTHON-PROVENANCE-STORE-FUNCTIONS.md) | [Datasets and lineage](PYTHON-PROVENANCE-WORKFLOW-FUNCTIONS.md)

Source: [ml.py](../../clients/python/aether_training_cache/ml.py). This page covers
19 explicit functions. They inspect the separate aetherml filesystem prototype,
not the Java engine, training-cache daemon or frozen confirmatory evidence.
Diagnostic field names are not guarantees beyond the implemented checks.

## Validation and Recovery Architecture

```text
validate (no store lock):
  metadata -> blobs/checksum/size -> selected parent references
  cache pointers -> known and valid artifact IDs
  snapshots -> node-metadata membership
  experiments -> snapshot-file existence
  artifacts/tmp -> orphan/temp inventory
  -> RecoveryReport or uncaught malformed-metadata/read error

recover: validate first -> instance lock -> delete reported tmp/bad pointers
         -> optional orphan deletion -> return ORIGINAL report
         -> caller revalidates for post-cleanup state
```

Recovery is a bounded cleanup helper, not transactional rollback or a repair
engine. It cannot regenerate blobs, recover corrupt metadata, reconstruct lost
snapshots, prove an acyclic lineage, restore experiment outputs or replay a WAL.
Validation occurs before its lock and other handles do not share that lock; do not
recover against concurrently mutating stores. Revalidate after cleanup, and use
trusted roots/metadata because path containment is not enforced here.

## Report and Recovery Functions

RecoveryReport has separate lists for temporary_files, dangling_cache_entries,
corrupt_cache_entries, missing_artifacts, checksum_mismatches,
missing_parent_artifacts, missing_snapshot_artifacts, missing_experiment_snapshots
and orphan_artifact_files. SnapshotVerificationReport stores snapshot_id,
verified_artifact_ids, missing_metadata, missing_artifacts and checksum_mismatches.
Their frozen dataclasses do not deep-freeze lists or implement additional parsers.

| Declaration | Behavior and boundary |
| --- | --- |
| `RecoveryReport.is_consistent()` | Property returning true only when every list in asdict(report) is empty, including temporary/orphan files. It does not run validation or independently inspect storage. Mutating a report's contained list changes the property. |
| `SnapshotVerificationReport.is_reproducible()` | Property checking that three issue lists are empty. Ignores verified count, ancestors, code, environment and training reproducibility; empty/direct-only snapshots can pass. |
| `AetherMLStore.validate()` | Scans metadata and checks blob existence/SHA/size, selected parent metadata existence, cache IDs, snapshot member metadata, experiment snapshot existence, unreferenced blob files and immediate temp files. Returns sorted issue lists. No lock, repair or blanket parser-error handling. Malformed artifact/snapshot/experiment JSON/type errors can raise before a report. |
| `AetherMLStore.recover(*, remove_orphan_artifacts=False)` | Calls validate first; under the instance lock unlinks reported temporary files and dangling/corrupt cache pointers, optionally orphan files. Returns the **pre-cleanup** report. Missing_ok permits already removed files, not arbitrary I/O failures. Leaves corrupt/missing artifacts, metadata, parents, snapshots and experiment records unresolved. |

### What Validation Actually Establishes

Parent checking occurs only after the node's blob exists; a missing child blob
causes an early continue, so its absent parents need not appear in that report.
The parent check needs a metadata path to exist, not a valid payload or readable
parent schema. Cache validation checks artifact_id against node/blob issues,
not embedded cache storage_location/checksum/size consistency. A tampered
self-contained cache entry can disagree with node metadata without being flagged.

Snapshot checks require member metadata IDs to be known; they do not mark an
otherwise known member's corrupt blob as a missing_snapshot_artifact. Those issues
appear in the store's global blob lists instead. Experiment checks require its
snapshot file to exist; they do not validate output artifact IDs, model, metrics,
timing or parent lineage. No cycle, cryptographic metadata authentication,
artifact-ID/content-hash recomputation or exhaustive schema verification occurs.

Temporary inventory covers immediate files in tmp_dir, not every nested directory.
Orphans are files under artifacts not referenced by metadata, independent of cache
pointers/snapshot reachability. A valid artifact no longer addressed by a cache
pointer is still referenced by its metadata and is not an orphan. Optional orphan
deletion is therefore not full reachability-based garbage collection.

## Operation and Storage Metrics

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore.reset_operation_metrics()` | Clears all existing observation lists in place, including dynamically added batch buckets. Keeps bucket names and store data. Does not synchronize with concurrent timer updates. |
| `AetherMLStore.operation_metrics()` | Returns sorted operation names mapped to latency summaries for their current lists. No remote polling, reset, rate calculation or experiment pairing. |
| `AetherMLStore.storage_metrics(*, raw_dataset_bytes=0, baseline_preprocessed_bytes=0)` | Scans root files, groups top-level bytes, sums logical sizes across nodes and unique resolved referenced blob sizes, and computes metadata/cache sizes and ratios. Counts tmp/orphan/unrelated root files in total storage. Reports WAL/compaction bytes as zero because this store implements neither. Caller supplies dataset/baseline byte denominators; no consistency lock or measured disk-write history. |
| `AetherMLStore._record_latency(operation, started_ns)` | Appends current monotonic nanoseconds minus caller start, creating a bucket if needed. Does not validate units/ranges, copy a trace or cap observation memory. Many callers run this in finally, so failures/misses are included. |
| `_directory_size(path)` | Zero if absent, otherwise sums stat sizes for recursively enumerated files. No block-allocation measure, snapshot isolation or error suppression during mutation. |
| `_ratio(numerator, denominator)` | Returns None for zero denominator, otherwise division. Does not reject negative inputs or infer missing values. |
| `_latency_distribution(values)` | For empty data returns zero-valued statistics. Otherwise calculates mean/median/sample standard deviation, normal 1.96*s/sqrt(n) interval, min/max and nearest-rank-style percentiles. A single observation has zero spread/margin. Input and outputs are nanoseconds; summary has no unit field. |
| `_percentile(values, value)` | Sorts values and selects the clamped implemented rank. No interpolation or percentile validation; empty input raises IndexError when called directly. The distribution wrapper handles empty values separately. |

Storage's writeAmplification is **current file bytes divided by supplied raw
dataset bytes**, not physical bytes written per logical write. cacheAmplification
uses unique referenced blob sizes. logicalArtifactBytes counts the same content
again for distinct provenance nodes; cachedArtifactBytes deduplicates resolved
paths and excludes missing blobs. metadataBytes includes cacheIndexBytes, so
those fields are not independent totals to add. Stored-byte ratios are not proof
of Java compaction efficiency or durability equivalence.

Nested timings overlap: artifact retrieval includes metadata lookup; wrapper
serialization/deserialization can sit outside the corresponding commit/retrieval
timer. Batch methods have their own bucket rather than recording each single
operation. Counts include failed attempts where finally records them. The normal
confidence interval over repeated operation latencies is not the H2 paired
confirmatory analysis, and can extend below zero for high spread.

## Environment Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `environment_report(*, dataset_version=None, code_commit=None, root=None)` | Combines CPU/platform/Python/executable fields with GPU, RAM, disk, Java, Torch and Git probes. Dataset defaults to unknown; commit precedence is supplied, AETHER_CODE_COMMIT, Git probe. Does not include package-lock/source inventories, GPU utilization, training seed or validated backend identity. Disk/probe failures not handled locally can propagate. |
| `_gpu_report()` | Imports Torch; missing import reports unavailable. If cuda.is_available is false, reports unavailable with torch backend. Otherwise lists all visible devices' names/total memory and labels rocm when torch.version.hip is set, cuda otherwise. No selection, workload execution, utilization or device-free-memory measurement. Non-import probe failures can propagate. |
| `_ram_report()` | On Windows tries GlobalMemoryStatusEx through a locally declared ctypes MemoryStatus layout, broadly catches that branch's exceptions, then tries available os.sysconf page-size/page-count fields. Supported sysconf errors fall through to None totals. Reports host physical totals/availability, not process RSS, GPU RAM or a container quota. |
| `_disk_report(root)` | shutil.disk_usage for supplied root or cwd plus resolved path. Reports volume capacity/used/free, not this store's bytes or write traffic. Does not create a missing root or catch disk_usage failures. |
| `_java_version()` | Runs java -version with captured output and a five-second timeout. Supported launch/subprocess failures return None; selects first stderr line or stdout fallback even without success return code. Does not validate JDK identity or launch Aether. |
| `_pytorch_version()` | Imports Torch and returns __version__, or None for ImportError. No CUDA compatibility or package-lock check. |
| `_git_commit(root)` | Runs git rev-parse HEAD, using git -C when root supplied, with a five-second timeout. Supported execution failures/nonzero/empty output return unknown. Does not check dirty worktree, hash source contents or pin a frozen archive. |

The environment report is descriptive, not a reproducibility gate. Probing can
import Torch, initialize device queries or spawn short Java/Git processes. Run it
outside measured work unless its cost belongs in the chosen timing boundary.
Store initialization's code_commit default is only supplied/env/unknown, unlike
environment_report's optional Git fallback; these fields need not match without
caller discipline. Missing measurements remain None/unknown, not fabricated data.

## Verification Scope

[test_aetherml.py](../../clients/python/tests/test_aetherml.py) covers cleanup,
corrupt artifacts/snapshots, latency/storage summaries and basic environment
fields. [Focused provenance contracts](../../scripts/tests/test_provenance_documented_contracts.py)
exercise report scope, pre-cleanup recovery results, stored-versus-written ratios,
failure timers, rank statistics and deterministic environment probe precedence.
They use local fixtures and probe doubles; they do not certify power loss,
host-environment identity, full malformed-store tolerance or confirmatory science.
