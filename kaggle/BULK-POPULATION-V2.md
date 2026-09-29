# Bulk Population v2: Staged Diagnostics

Status: first-stage layout diagnostic only. Not a completed v2 optimization or a
longitudinal/confirmatory result. Previous v1 artifacts and defaults are preserved.

## First Measurement

Mode `population-layout` runs 32, 64 and 128 MiB bulk SSTable targets, three fresh
repetitions each, on the frozen 1,200-sample V0. Batch size remains 16. The order is
cyclic and balanced: 32/64/128, 64/128/32, 128/32/64. Each trial uses a fresh process
and store. A separate 65-sample smoke with three new samples gates the nine trials.

Only the bulk table target varies. The default remains 32 MiB pending evidence.
Threshold partitioning (including a possible small final table), serial verification,
SHA/envelope construction, payload ownership copies and Python framing remain v1.
Normal online write code and storage policies are unchanged.

Every trial checks all 1,200 entries, SHA admission count, zero artifact WAL bytes,
zero memtable inserts, and canonical tensor hashes after a genuine JVM restart.
V0 population includes final verification/publication, but not regression checks.

The regression phase uses the existing packed-byte hit-path API and its hit-only
invariant/summary helpers. It measures five passes after two warmups at batch 16,
prefetch zero, without tensor decode, a model, or training. There must be no missing
entries or flush/compaction activity. This is a layout-specific byte-lookup check,
not the separate full-input/GPU hit-path campaign. Paired throughput ratios versus
32 MiB are retained; investigate losses around 1-2%, not just population speed.

Then the ordinary durable adapter admits V1 (1,260 samples): exactly 60 new artifacts,
all 1,200 old entries reused. Admission and drain time, process disk I/O, disk sizes,
compaction counters and full tensor readback are reported separately. This single
small update is a regression screen, not evidence of V1-V4 lifecycle performance.
Its process I/O is not device-level write amplification.

Linux Python and Java RSS is sampled every 20 ms from writer readiness through exit;
this is an approximate peak and is explicitly unavailable on unsupported platforms.
The encoded-buffer high-water mark is separate from RSS and is not a heap limit.
This stage retains the bounded in-memory prototype; it is not a production streaming
API or a claim of dataset-size-independent memory usage.

## Actual Manifest Protocol

The engine appends one checksum-protected edit to an existing manifest and forces
the file before installing the in-memory version. It does not write a temporary
manifest or rename CURRENT during this edit. Bulk-only instrumentation records
candidate construction, inventory verification, record encoding, append, force,
and installation. Temp-write/rename timings are not applicable, not omitted work.
Existing table and WAL-header directory barriers remain separately timed.

Inspection found three existing validation passes: builder validation, post-rename
validation, and manifest inventory validation. They are all retained in this
first measurement, with the builder pass in `sstableFinishes.stagesNs`, the second
in `timingsNs.verificationNs`, and the third in `manifest.inventoryVerificationNs`.
Counters are elapsed work timings, not CPU time. Inclusive stages must not be summed.

Process-death tests cover completed table force, rename, before/after the loader's
verification, pre-manifest, post-append, post-force and post-publication. Corrupted
unpublished tables must prevent publication. A complete unforced manifest append
may survive a process death; recovery must be empty or fully committed, never partial.
No temporary-manifest/rename crash points are claimed where those operations do not
exist. These tests do not claim power-loss coverage or new pipeline fault coverage.

## Subsequent Gates

Inspect this sweep before changing partitioning. Then independently measure balanced
terminal partitions, bounded verification overlap, fused admission, optional two-worker
integrity, and gathered client framing. Do not bundle them into an attributed speedup.
Retain only worthwhile measured improvements with correctness and memory checks.

Targets of 15.5 seconds (stretch 15.0) are engineering goals, not promised outcomes.
After reaching the performance or diminishing-return gate, run a fresh five-block
persistent-service longitudinal pilot, charging the bulk-to-online transition and
using normal admission for V1-V4. The 24-block confirmation remains on hold.
