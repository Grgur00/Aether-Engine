# H2 Frozen-Storage Pilot

Storage implementation: tag aether-v0-streaming-v1, commit
00760e5f31fa31a17e69522539a3b60318ca9bf0. All existing storage implementation
files are unchanged. This experiment adds a separate same-JVM bootstrap entry point
and a distinct pilot configuration, not another V0 optimization.

Start one JVM before V0. Its loopback bootstrap listener stages batch-16 artifacts
through the frozen BulkArtifactWriter at 32 MiB target. A zero-length frame invokes
the single authoritative streaming verifier and durable manifest publication.
Only after closing the offline loader does that same JVM open TrainingCacheDaemon
in DURABLE mode, normal background compaction enabled, capacity 1 TiB.
The bootstrap response supplies the training port and same PID. No restart fallback.
Normal incremental admissions serve V1-V4. JVM options: --enable-preview, no heap/GC
tuning, no tracing or JFR. Existing block/artifact checksums and publication barriers
remain unchanged. Bootstrap transport is loopback, frame-limited to 64 MiB.
Ordinary engine-open recovery validation is retained and charged to V0 preparation.

Five fresh paired blocks, versions 1200/1260/1323/1389/1458, 20 training epochs
at every version including V0; batch 16, prefetch 0. Four backends: Aether,
incremental mmap, MONAI PersistentDataset, MONAI LMDBDataset. Existing deterministic
model/optimizer/transform/sample seeds and rotated backend order are retained.
The same Aether PID persists across all five versions, including interleaved baseline
jobs; memory/page-cache deployment differences remain part of interpretation.

One startup is charged to V0; bootstrap staging, commit and opening the normal
engine are charged to V0 preparation. One shutdown is charged to V4. Native cache
open/admission, model setup/warmup, 20 epochs, required drains and close are charged.
Common preflight, Python import/launch and validation remain separately recorded.
Linux write_bytes is a process-I/O observation including validation, not logical
payload volume; startup/final process-exit bytes are excluded and scope is reported.

The endpoint differs from the historical pilot, which skipped V0 training and used
ordinary V0 admissions. Do not pool campaigns or directly claim historical savings.
Report per-version preparation/training/lifecycle, cumulative Aether/baseline ratios,
first observed break-even (not necessarily sustained), reuse and bytes-written scope.
Complete-block resume only; partial blocks are preserved and require a new campaign.
Resume writes a newly named checkpoint ZIP, preserving the previous snapshot.
Separate CPU correctness and GPU smoke gates precede the five-block pilot.

Final V0 JFR (notebook 51) passed: one recording, about 249 MB weighted allocation
inside authoritative verification versus the prior approximately 1.37 GB; roughly
82% lower. No sampled Entry.value allocation stack; sampled absence alone is not
proof of zero execution. The streaming scanner has no value materialization API.
V0 optimization is closed. Freeze H2 confirmatory only after review of this pilot;
24-block confirmation is not automatically launched.
