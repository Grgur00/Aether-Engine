# H4 CPU Preflight: Failed Runtime Gates

## Verified Evidence

On 2026-10-11, private Kaggle notebook `grgur321/aether-h4-cpu-preflight`,
version 3, completed its CPU-only diagnostic. It used private source dataset
`grgur321/aether-h4-cpu-preflight-source/3`, not the PCam benchmark input.

- Transport SHA256: `3488c44729b081061e3e4d92fa66bfbd8956940be3a6660aed3ff92b3f552521`.
- Downloaded result ZIP SHA256: `0ad3192b0c1576454bbd2a657253665df8a8253de9af5a9e8f5b72f6ab82655d`.
- The result inventory and source inventory match the prepared upload.
- Notebook completion means the diagnostic finished, not that its gates passed.

Local preserved evidence is under
`build/h4-kaggle-cpu-v3/results-v3/verified/`. The downloaded ZIP remains beside
that directory. The raw evidence is excluded from source control by the existing
build-output policy; preserve or download notebook version 3 when auditing.

## Outcomes

| Check | Outcome | Authoritative Report |
| --- | --- | --- |
| Java runtime build | Passed | `paper-runtime-build.json`, `execution.log` |
| 32-artifact online admission, close/reopen, full hashes | Passed | `online-smoke-32/preflight.json` |
| Delegated 2 GiB cgroup memory control | Failed | `resources/memory_control_report.json` |
| 1 GiB synthetic online admission | Failed | `online-1gib/preflight.json`, `online-1gib/java-exceptions.log` |
| Official PCam / beyond-RAM retrieval | Not attempted | `run-status.json` |

The parent cgroup reports a 30 GiB memory limit and does not delegate the memory
controller to children. The probe left the parent unchanged. A 512 MiB Java heap
does not constrain Python and charged file cache to the required 2 GiB budget.
No primary out-of-core qualification is possible from this probe.

The 1 GiB attempt requested 9,710 seeded synthetic artifacts, totaling
1,073,848,320 payload bytes, through durable online `putMany` batches of 16 with
a 512 MiB Java heap. The last successful engine-info response reported 1,776
cache entries. Admission then lost the daemon connection. The exception log
records `OutOfMemoryError: Java heap space` through `RestartBlock.decode`,
`SSTableReader.DataBlock.loadEntries`, and SSTable verification/publication.
This reproduces the earlier Windows diagnostic on Linux. It is not proof of a
universal 1,776-artifact capacity ceiling or an official-PCam result.

The sampled store-file peak was 262,396,381 bytes at a 0.1-second observation
interval. This is a sampled lower bound on apparent file sizes, not exact peak
disk allocation or successful final-store size. The failed run never completed
its quiescence, fresh-JVM recovery and full-hash checks at the requested size.

## Consequences

- Do not advance to 2/4/8 GiB Aether admission or construct the complete PCam
  benchmark input merely to work around the failed prerequisite.
- Do not raise Java heap, silently change the storage implementation, modify the parent
  cgroup, or report unrestricted reads as equivalent to the frozen protocol.
- Do not freeze a confirmatory design or claim H4 performance from this evidence.
- Keep H2's frozen implementation and evidence separate.

The current H4 proposal cannot be completed under its unchanged storage freeze
and this Kaggle memory-control setup. Continuing requires an explicitly approved
H4-only storage revision and a resource-control setup that actually qualifies the
total benchmark budget. A proposed change, alternate host, or passing small smoke
is not sufficient: repeat the capacity and memory gates before preparing the
official-PCam pilot, then freeze a separate confirmatory design only after the
qualified pilot.

No production Java implementation was changed to obtain these results.

## Authorized H4-Only Revision

The user subsequently authorized a separate bounded-memory H4 storage candidate.
H2's implementation, source snapshots and results must remain unchanged. The
candidate starts from the byte-verified v3 transport in an isolated build tree;
reviewable candidate patches are kept under `storage-revision/`. This amendment
does not make either failed gate pass and does not authorize a beyond-RAM claim.

A fresh Windows baseline reproduction with Java 21.0.10 and `-Xmx512m` again lost
the connection at 1,776 admitted artifacts with Java heap exhaustion in SSTable
verification. `build/h4-baseline-profile-v4` contains the diagnostic recording
and checksummed failure receipt. Its JFR SHA-256 is
`84819a9900cfda78b15f66cd9bee4a03cb8074e45c994cbedf9065d209dc9e6c`.
The allocation summary reports 2,052 allocation samples, approximately 3.20 GB
of sampled weight with `SSTableReader.verify` on the stack, and approximately
0.93 GB with `RestartBlock.Entry.value`. These categories overlap and must not
be added together. Maximum observed GC heap usage was 533,054,224 bytes;
this is not a continuously measured peak. This diagnostic is not a capacity pass.

The first isolated candidate delegates reader verification to the existing
streaming verifier using the reader's already-open channel and authoritative
metadata. This avoids path reopening and DATA-value materialization during
verification. Checksummed envelopes, ordering, metadata, sequence bounds and
Bloom checks remain authoritative. Restart validation intentionally rejects
malformed offsets inside entries and shared prefixes at restart points.

This first change alone is not a bounded-memory storage solution: decoded read
blocks are still retained by readers, and ordinary compaction materializes its
merged and retained inputs. Those paths need independent implementation and
regression validation before advancing the 1/2/4/8 GiB gates. No candidate
capacity or crash-consistency qualification is claimed yet.

The isolated streaming-open candidate compiled successfully and passed 44
SSTable regression tests copied from the current checkout (the uploaded v3
transport itself contains no test sources). Its unprofiled 1 GiB attempt,
`build/h4-streaming-open-1gib-v1`, advanced to 2,960 artifacts, then failed with
heap exhaustion during compaction. Four flushes had completed, six compaction
attempts had failed, and no compaction manifest was published in those records.
This is diagnostic evidence of the next memory bottleneck, not proof of crash
consistency or a successful capacity gate. The 2/4/8 GiB Aether gates remain closed.

## Bounded Candidate Validation

`storage-revision/bounded-storage-v1.patch` adds streaming compaction input and
output, lazy per-key retention, a shared 8 MiB charged decoded-block cache, and
snapshot-protected entry visitation for training-cache index reconstruction.
The cache charge includes value/key lengths and conservative per-entry overhead;
it is an accounting bound, not an exact JVM object-layout measurement. Cache
eviction discards resident validation markers; reload produces a fresh immutable
value and revalidates. Compaction targets at most 32 MiB before user-key boundary
alignment, without buffering all retained values. Keys/indices and merge heads
still scale with store metadata; no constant-space bound for arbitrary workloads
is claimed. General materialized scan/cursor behavior is unchanged.

Manifest verification, replacement-reader verification, publication fencing,
force/rename/directory-sync order and crash hooks remain in place. Full storage
and cache regression suites passed on Ubuntu WSL with Java 21.0.12.1: 45 SSTable,
17 LSM, 56 engine and 23 training-cache tests, including the new candidate tests.
On the Windows drive, default-engine tests hit the existing enabled disk-pressure
guard; the Linux suite passed without disabling that guard. These unit tests do
not substitute for the separate H3 process-death experiment.

| Diagnostic | Outcome | Local Evidence |
| --- | --- | --- |
| Aether 1 GiB, 512 MiB heap | Passed admission, fresh-JVM reopen, full hashes, shuffled reads | `build/h4-streaming-storage-1gib-v1` |
| Aether 2 GiB, 512 MiB heap | Passed the same checks | `build/h4-streaming-storage-2gib-v1` |
| Aether 4 GiB, unpaced | Failed at L0 write pressure; no heap exhaustion observed | `build/h4-streaming-storage-4gib-v1` |
| Aether 4 GiB, paced | Passed all checks; 38,837 artifacts | `build/h4-streaming-storage-4gib-v2` |
| Aether 8 GiB, local | Not attempted: disk-capacity safety gate failed | `build/h4-streaming-storage-8gib-capacity-check` |
| mmap 2, 4, 8 GiB | Passed independent materialization, reopen, full hashes and shuffled reads | `build/h4-mmap-2gib`, `build/h4-mmap-4gib`, `build/h4-mmap-8gib` |

The paced H4 driver drains background compaction every 256 artifacts, keeping
PUT_MANY batches at 16. This is an explicit population-policy amendment, not an
engine threshold increase or an outcome-unknown-write retry. Drain time is part
of secondary population time. The 4 GiB passed run observed heap usage up to
512,204,272 bytes and committed heap up to 536,870,912 bytes over 4,625 samples at
100 ms intervals. Sampled apparent store-file peak was 7,885,686,236 bytes.
Neither sampled maximum is a continuous peak or a total-workload memory limit.

The local 8 GiB guard required 27,917,520,896 free bytes; 22,574,112,768 were
available. The guard was not lowered and personal files were not deleted.

A separate private CPU-only Kaggle continuation was submitted at
<https://www.kaggle.com/code/grgur321/aether-h4-bounded-storage-cpu-validation>.
Its exact remotely downloaded transport SHA-256 is
`ff247f86b5052bb407d13ea3d1b4b6ba8459dd9ca94afa03245df6f53f5355c6`, pinned to
`grgur321/aether-h4-storage-revision-source/1`. It runs the regression gates and
1/2/4/8 GiB capacity diagnostics in sequence, stopping on failure. It requests
no GPU and never runs PCam retrieval or H2. Submission was confirmed RUNNING;
the result is pending, not a passed 8 GiB gate. The uploaded source and launcher
receipts remain frozen even though the local status utility now records Kaggle's
title-derived canonical job identifier.

The delegated memory-control and physical-read gates remain unqualified.
`LINUX-EXECUTION-PLAN.md` describes the controlled VM fallback. No beyond-RAM
scalability result is claimed.
