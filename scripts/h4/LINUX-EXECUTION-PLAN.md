# Controlled Linux H4 Fallback

Status: execution plan, not a provisioned host or qualified performance result.
Kaggle preflight v3 found no delegated child memory controller. Its 30 GiB parent
limit cannot substitute for the H4 2 GiB total-workload budget. The probe did not
modify that parent. Do not use Java heap limits or unrestricted Kaggle reads to
claim beyond-RAM scalability.

## Host Requirements

Use a dedicated Linux VM with cgroup v2 and administrator-controlled delegation
to the benchmark account. Allocate sufficient host RAM for the OS and build
processes outside the measured workload; the measured Python process, JVM and
their charged file cache must share one delegated child limited to 2 GiB, with
swap disabled. Use a real block-backed scratch filesystem, not tmpfs. Retain
device, filesystem, VM, CPU, Java, Python and package identities. Record whether
the virtual disk is backed by local or network storage and any host-side caching
that cannot be observed or controlled.

Budget scratch capacity for verified PCam sources, prepared artifacts, stores,
WAL, simultaneous compaction inputs/outputs and observed disk amplification.
Admission preflights enforce their existing free-space checks. Do not purchase
or provision a paid host without separate approval.

## Execution Gates

1. Verify H4 candidate source/patch identity; build and run storage regressions.
2. Run the delegated-controller feasibility probe with Python and Java membership
   checks. Refuse primary execution if delegation, file-cache charging, swap
   control or cleanup cannot be demonstrated.
3. Run unprofiled Aether admission at 1, then 2, 4 and 8 GiB with a 512 MiB heap.
   Require healthy flush/compaction quiescence, fresh-JVM reopen, complete artifact
   hash verification, shuffled byte consumption and peak disk/heap evidence.
4. Independently run the same mmap admission/reopen/hash workloads; archive each
   result with source and input identity. Population is secondary, outside read
   timing. Failed stores are not benchmark observations.
5. Only after capacity gates pass, prepare the complete verified PCam input.
6. Run paired reads using the existing controlled worker. Verify child membership
   throughout, actual memory pressure/reclaim, no OOM, complete byte consumption
   and positive physical storage-read counters. Retain raw counters and explicit
   unavailable fields; do not infer physical I/O from logical payload bytes.
7. Qualify the exploratory pilot before freezing any confirmatory protocol.

A controlled 2 GiB guest workload does not prove that the host hypervisor cache
misses. State this limitation explicitly, and qualify the storage-read claim at
the observable guest block-device/process-I/O level.

H2 is neither modified nor rerun at any stage.
