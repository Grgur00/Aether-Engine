# Persistent-Service Longitudinal Pilot

This is a separate exploratory experiment, not a 24-block confirmation and not
a relabeling of the restart-based longitudinal pilot. That pilot's source is
preserved at `build/longitudinal/prepared/source/aether-paper-artifact.zip`, commit
`6c8927d6cb8063421a24766705d2acf00c44145b`, tag
`aether-longitudinal-oct5k-pilot-v1`. No prior measurements enter this campaign.

## Unchanged Workload

Reuse the exact frozen manifests and hashes in `configs/paper/oct5k-longitudinal`:
V0=1200, V1=1260, V2=1323, V3=1389, V4=1458. There are five fresh paired blocks,
four backends, 20 epochs per update, batch 16, prefetch 0 and tracing off. Seed,
sample order, balanced backend order, transformations, model, optimizer, warmup,
codecs, and Aether durability/compaction settings remain unchanged. No engine
tuning is part of this comparison.

## Changed Deployment Model

Start Aether once immediately before its V0 population, keep the same Java PID
through V1-V4, and stop it after its V4 job. Each training job still has a fresh
Python process, dataset client, model, and optimizer. Native mmap/MONAI dataset
handles reopen per job as before; their on-disk caches are preserved. This tests
a persistent Aether service with independent training jobs, not persistent Python
dataset objects for every baseline.

All versions still verify exact admission counts, zero training misses, tensor
and paired model hashes, and compaction IDLE with zero debt. A changed or missing
Aether PID aborts the block; no automatic daemon restart is allowed.

## Accounting

The endpoint remains the cumulative measured lifecycle through V4, including V0.
Charge the one service startup to V0, and the one final service shutdown to V4.
Every native dataset/client open, admission scan, preparation, model setup/warmup,
training, required drain and client close remains charged. Per-stage and
cumulative phase sums are validated. Service start/stop counters must be
`[1,0,0,0,0]` and `[0,0,0,0,1]` respectively.

Service residence time is recorded separately: it includes the interleaved jobs
of other backends and therefore is not added to Aether's operational endpoint.
The idle service retains memory while baselines run, and OS page caches are
uncontrolled. These deployment/resource differences must accompany results.
Sampled utilization and input wait remain proxies, not direct GPU idle time.

Live stores are neither copied nor hashed for checkpointing between versions,
for any backend. This differs from the restart pilot's recovery instrumentation
and avoids treating a copy of a live database as a restart checkpoint. Diagnostic
receipts remain atomic and checksummed, but an incomplete persistent block cannot
resume: preserve it and start a fresh campaign output. Only fully completed,
validated paired blocks can be reused by `--resume`. No restarted or replayed
partial trajectory can count as an uninterrupted service measurement.

Initial population, service overhead and model setup are not omitted to manufacture
break-even. Common input preflight, instrumentation, validation, and Python process
launch remain separate under the same documented timing conventions. Results use
the separate schema `aether-longitudinal-persistent-campaign-v1`.

## Correctness And Launch

The existing real-process restart test stays separate:

```powershell
$env:PYTHONPATH='scripts;clients/python'
$env:AETHER_JAVA_TEST='1'
.venv/Scripts/python.exe -m pytest scripts/tests/test_longitudinal_comparison.py::test_real_five_version_process_restart -q
```

It checks durable reuse across five actual Java process lifetimes using CPU
fixtures. Those timings are not commercial throughput evidence.

The new Kaggle mode runs that correctness test first, then a separate complete
persistent-service GPU smoke block with one epoch per update, then five fresh
20-epoch pilot blocks. Failure of either gate prevents the pilot. Outputs are
separate (`restart-correctness.xml`, `longitudinal-persistent-smoke`, and
`longitudinal-persistent-pilot`); no smoke or correctness measurements are pooled.

```powershell
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py prepare --mode longitudinal-persistent --no-server-trace --dataset-source grgur321/aether-oct5k-pilot
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py upload-source --update
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py run
```

Prepare from a clean committed source snapshot. Do not update the frozen CSVs or
reuse another campaign's blocks. Stop remote monitoring once Kaggle reports the
notebook running. Assess this pilot before specifying any fresh confirmation.
