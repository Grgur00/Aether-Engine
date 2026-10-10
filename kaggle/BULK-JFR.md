# Bulk Population JFR Diagnostic

Run mode: `population-jfr`. Fixed V0 1,200 samples, 32 MiB SSTable target,
publication batch 16, no training, no update, no other cache backends, no server
tracing. Three fresh processes/stores execute control-before, JFR, control-after.
Only the middle process records. No benchmark claim is made. For the v2 source,
run the [unprofiled Phase-1 comparison](BULK-VERIFICATION-V2.md) first; do not launch
the follow-up remote JFR until its timing and correctness gates pass.

The actual population process is `BulkArtifactWriter`, an offline stdin-pipe JVM.
Starting the regular TCP daemon with JFR would profile the wrong process. The
driver passes `StartFlightRecording` with `settings=profile`, `disk=true`,
`dumponexit=true`, and stackdepth 256 to the offline writer. JFR startup logging
is redirected to stderr so stdout remains exclusively the pipe protocol.
Gradle daemon/benchmark tasks also accept optional `jfrSettings`, default profile.
The same production encoder, preprocessing, manifest and empty-store checks are
used by all three runs. Restart readback remains mandatory and outside the
population endpoint, in a separate unprofiled JVM.

`aether.BulkPopulation` spans writer readiness through durable finish, including
waits for Python preprocessing/input. It excludes JVM/bootstrap startup. A failure
emits an unsuccessful interval on ordinary close; abrupt process death cannot be
expected to emit a final event. The Python population timer remains authoritative.

`aether.BulkPhase` events cover batch request decode/admission, sorted partition
traversal, per-table build/force/verification/rename, manifest encode/write/force/
install, and final synchronous cleanup/directory barrier (`QUIESCE`). There is no
asynchronous bulk queue. Admission events are one per batch, not per artifact;
INTEGRITY includes SHA, ownership/envelope copies, CRC and sorted-map admission.
Existing separate nanosecond counters remain available for attribution.
SSTABLE_BUILD is inclusive of the builder's force marker. The original v1 recording
had three verification passes; Bulk Verification v2 retains only the authoritative
inventory verification before manifest append. New recordings use the v2 protocol
and include the policy identity. Do not sum nested event durations or relabel old
recordings as measurements of the changed implementation.

The shared phase type resides in the SSTable module to avoid an engine-to-training
cache dependency cycle. The population type lives in training-cache/jfr. Events
are opt-in under `-Daether.bulk.jfr=true`; controls do not enable this property.
Ordinary write methods, forces and publication ordering are unchanged.

## Outputs

`population-jfr` contains protocol/campaign provenance, three JSON receipts,
`aether-bulk-32.jfr`, generic and custom event JSON, `jfr-event-summary.txt`,
`jfr-summary.md`, `jfr-analysis.json`, and `checksums.sha256`. Existing Kaggle
result bundling includes the binary recording and checksums. Failed stores are
retained; only fully validated successful scratch stores are deleted.

The programmatic summary restricts evidence to the population interval, reports
top sampled methods/call stacks, weighted allocation samples, GC pauses and observed
heap occupancy, thresholded I/O/blocking events, and phase durations. It computes
JFR population / mean(control-before, control-after). This fixed order cannot remove
host drift. Stock profile thresholds may omit short operations; missing events do
not mean zero work. Allocation weights are estimates, not exact allocated/copy
bytes. JFR alone does not prove disk saturation or recoverable wall time.

Use Mission Control for detailed thread/call-tree review before choosing ONE next
optimization. Do not select an optimization from a small local fixture or treat the
profiled duration as a benchmark. No 64/128 MiB sweep, five-block pilot or 24-block
confirmation is launched by this mode.

Local pipeline validation uses a tiny synthetic OCT fixture and is not performance
evidence. The full OCT5K recording must be collected on Kaggle from a newly frozen
source snapshot; preserve/check any already submitted job before replacing it.
