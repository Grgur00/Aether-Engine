# Verification-v2 Candidate-Only JFR

One separately requested diagnostic, not a new performance claim. The completed
three-pair Phase-1 gate remains failed: 185.164 ms median savings versus the
predeclared 750 ms threshold (17,837.776 ms baseline; 17,652.612 ms candidate).

Freeze the same Java engine, Python feeding code and preprocessing as candidate
commit `fddfca697a8f33612f87905e2dd41f08580cfc92`. Only profiling orchestration,
analysis, tests and this protocol change. No streaming verifier is implemented.

Run `population-candidate-jfr`: exactly one 1,200-artifact V0 population, batch 16,
32 MiB SSTables, no training, no dataset evolution, no controls, JFR profile
settings and stack depth 256. Validate tensors and restart readback outside the
population timer. Require one inventory call and one full verification per table.

Retain the raw `.jfr`, exported events, correctness receipt, source provenance,
checksums and the interval-scoped `authoritativeVerification` analysis. Inspect
inclusive stacks for RestartBlock.decode, DataBlock.loadEntries, entry value
accessors, clones, Arrays.copyOfRange, checksums and FileChannel reads. Allocation
weights are sampled estimates, not exact copied bytes. Short I/O events can fall
below stock JFR thresholds. GC overlap does not establish allocation causality.
No earlier full verification occurs; OS page-cache coldness is not controlled.

No profiler-overhead estimate is possible without controls. Do not compare this
profiled wall time as a fresh unprofiled benchmark. The 750 ms gate is unchanged.
Review the single verification's allocation/copy stacks before deciding whether
a separately tested streaming verifier is justified. Otherwise stop V0 tuning
and move to warm-read/incremental checks. No automatic follow-up run or optimization.
