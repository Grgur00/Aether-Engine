# V0 Population Diagnostic

Exploratory only. The 24-block longitudinal confirmation is on hold. No bulk-load
optimization is implemented in this experiment.

## Workload

- Frozen longitudinal V0: 1,200 genuine OCT5K samples, same manifest and order.
- Empty cache, populate, quiesce, correctness readback outside timing, close.
- No model, optimizer, augmentation, training, or dataset evolution.
- Existing deterministic preprocessing, codecs, durability and storage policies.
- Three fresh repetitions of each of eleven arms, seeded randomized order.
- All arms run in fresh Python processes and use fresh isolated stores.
- A separate 65-sample smoke exercises all arms and partial batches first.

## Arms

1. Aether publication sizes 1, 4, 8, 16, 32, 64, with detailed tracing. The adapter
   fetch/admission group stays at 64; only the publication is split. Expected write
   RPC counts are 1200, 300, 150, 75, 38, 19. Lookup RPC count stays at 19.
2. Existing pilot admission path: 16-sample lookup and publication, traced.
3. Same pilot path without client/server tracing, as an instrumentation control.
4. Incremental mmap, MONAI PersistentDataset, MONAI LMDBDataset, using the unchanged
   pilot helpers and 16-sample fetch groups. LMDB still eagerly populates in its
   constructor. No new serialization format or parallel workers.

The old pilot already uses `put_many` on 16 artifacts, not 1,200 single writes.
The two lookup-group sizes are explicitly separate: compare sweep arms to each
other and compare the pilot16 controls to the native baselines.

## Evidence

Each arm records startup, population, quiescence and close separately, with decoded
tensor equality, exactly 1,200 preprocessing calls/artifacts, protocol operation
counts, process I/O, sampled disk high-water marks, engine information and full
client/server traces. Common manifest integrity/reference work and post-population
readback are outside the endpoint. No trace records are silently dropped and no
automatic RPC retries are accepted as valid diagnostic evidence.

Client timings include existing source-load/preprocessing counters, TensorDictCodec
encoding/decoding, production `put_many` request-body construction, frame construction,
socket send, response wait/read, and total publication latency. MONAI native
serialization-only time is unavailable and is not invented.

Opt-in Java probes report input ownership copies/deduplication, artifact envelope
encoding, SHA-256 and CRC32C admission work, WriteBatch construction, database write
and sync, write admission, WAL logical/fragment encoding, WAL append, WAL force and
force count, memtable insertion, and WAL bytes. Existing request traces retain
flush causes/stages, SSTable creation and manifest-publication timings. Existing
background diagnostics retain compaction events and timing; quiescence requires
IDLE, zero debt and no failures. A shared commit-group force is attributed once to
its first request; this diagnostic uses one serial writer.

Nested durations are inclusive. Do not add database write, WAL, flush and compaction
time as if they were disjoint. Likewise socket wait contains server work, and
`sendall` duration is not a pure network-transfer measurement. Async compaction may
overlap foreground work. Disk sampling is a lower bound, not an exact peak.
Source readback warms OS caches; no privileged page-cache clearing is performed.

## Copy/Integrity Hypotheses

The source audit identifies copies, not their measured cost:

- Python `put_many` repeatedly concatenates immutable request-body bytes, followed
  by framing. Larger batches may trade fewer round trips for more construction work.
- Java request parsing materializes payload arrays. `CacheEntry` clones at
  construction and on `value()` access. Publication records the ownership-copy stage.
- Artifact envelope encoding, WriteBatch, WAL encoding and memtable insertion add
  further representation/copy work, measured at their actual API boundaries.
- Admission SHA-256/CRC32C and existing read-integrity counters remain enabled.
  Their distinct correctness purposes must be considered before removing any work.

No zero-copy, deferred fsync, checksum removal, preprocessing tuning, or bulk loader
is introduced here. Trace overhead means these are diagnostic measurements, not a
new confirmatory performance claim. A changed ingestion implementation needs a new
longitudinal pilot before any confirmatory campaign.

## Kaggle

Prepare from a clean committed snapshot with `scripts/kaggle_remote.py prepare
--mode population --dataset-source grgur321/aether-oct5k-pilot`. Upload that exact
source, then run. The existing instance type is retained for environmental continuity;
no model or GPU training is executed. Results are under `population-smoke` and
`population-diagnostic` in the results-only ZIP. Stop monitoring at RUNNING.

The prior persistent-service pilot is preserved at commit
`fb93f4aa5f32c3f27b7c56ec9d8332d9231354d8`, on GitHub branch
`research/persistent-service-pilot-v2`.
