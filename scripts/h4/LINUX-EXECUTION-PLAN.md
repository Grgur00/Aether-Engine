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

## Delegation Layout

Have the host administrator provide a delegated systemd service/scope subtree
owned by the benchmark account, with memory control enabled for its children.
The launcher occupies a separate leaf; the delegated parent is empty. Export
`H4_CGROUP_PARENT` as the absolute path to that parent. H4 accepts it only when
it is a non-root ancestor of the launcher's verified current cgroup, contains
no direct processes, and already delegates memory control. H4 never enables
controllers, moves the launcher or changes parent limits itself.

Example layout, established by the administrator before execution:

```text
delegated-h4.service/          empty; memory delegated; benchmark-account ownership
  launcher/                   shell and orchestration Python
  aether-h4-<unique-id>/       created by H4; Python/JVM + charged file cache; 2 GiB; swap 0
```

This separation follows the cgroup v2 no-internal-process constraint and
[systemd's delegation guidance](https://github.com/systemd/systemd/blob/main/docs/CGROUP_DELEGATION.md).
The administrator should use the host's supported delegation mechanism, not
grant benchmark code write access to the system cgroup root. See also the
[kernel delegation rules](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html).

## Runnable Readiness Check

Run from a newly prepared isolated candidate, after its Java regression build.
Use a fresh evidence directory for each attempt. These commands do not provision
a VM, change parent controls, download PCam or launch the full experiment.

```bash
export PYTHONPATH="$PWD/scripts:$PWD/clients/python"
export H4_CGROUP_PARENT=/sys/fs/cgroup/PATH_TO_ADMIN_DELEGATED_PARENT
python -m h4.linux_readiness --scratch /mnt/h4-scratch --output /mnt/h4-results/readiness-01
python -m h4.kaggle_preflight --scratch /mnt/h4-scratch --output /mnt/h4-results/capability-01 --attempt-memory-control
```

Readiness verifies candidate inventory and compiled-source provenance, captures
mount/block-device attribution, checks the delegated-parent configuration and
requires at least 26 GiB free for the 8 GiB admission stage alone. Additional
input/prepared-artifact space is not included in that reserve. tmpfs, overlay,
network filesystem and unidentifiable/loop-backed scratch are rejected. A VM's
reported free capacity must also be checked against its underlying physical
storage; thin provisioning is not a physical-capacity guarantee.

`linux_readiness` exits 0 only for host/source readiness, never performance
qualification. The capability probe still exits 2 because it deliberately
cannot qualify primary H4. Inspect its explicit memory-control, file-accounting,
Python/JVM membership and cleanup fields before advancing. Do not blindly chain
commands based on that exit code, and do not weaken either gate to obtain a pass.

Once readiness and capability checks succeed, run the ordered admission commands
in the H4 README from the same candidate. Their new source-bound receipts are
required by the paired runner and worker. Old Kaggle diagnostic receipts remain
valuable diagnostic evidence but cannot be silently promoted to primary runs
when they lack a matching Java source binding. Keep profiler output excluded
from primary timing, and retain guest/host cache limitations in the final analysis.

Local WSL negative-path check: `build/h4-linux-readiness-local-v1/readiness.json`
records failed readiness because the root checkout is not an inventoried isolated
candidate and its launcher cgroup does not delegate memory. The check changed no
parent controls and started no workload. This verifies refusal behavior, not a
provisioned-host success or experimental result.
