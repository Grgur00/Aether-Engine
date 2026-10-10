# Concurrency and Fault Driver Functions

[Function index](FUNCTION-INDEX.md) | [Systems campaign receipts](RESEARCH-CAMPAIGN-FUNCTIONS.md) | [Research process ownership](RESEARCH-PROCESS-FUNCTIONS.md)

Sources: [concurrency_matrix.py](../../scripts/concurrency_matrix.py) and
[fault_injection.py](../../scripts/fault_injection.py).
This reference covers **10 explicit functions across both complete files**.
Names are file-qualified to distinguish campaign and CLI functions.

## Architecture and Scope

```text
immutable systems campaign -> source/environment/protocol receipts
concurrency: fresh store -> prepopulate prefix -> spawn clients/worker ranks
    -> readiness queue -> shared start event -> lookups/missing publications
    -> worker reports -> process exit checks -> verify every unique final key
fault: fresh trial -> Java fault writer -> boundary marker -> kill/wait
    -> new Java recovery verifier -> parsed result -> receipt + rolling summary
resume -> verify receipts and trial identity -> skip completed trials
```

Concurrency is a synthetic storage-client microbenchmark, not GPU input or model
throughput. Duplicate missing-key computation is allowed, and publications count
client attempts rather than unique engine commits. Page cache is uncontrolled.
Fault injection exercises process death (SIGKILL/TerminateProcess), not loss of
power, device-cache behavior or every possible interruption point. Assertions
about acknowledged writes/checksums are delegated to the Java fault probe.

## Concurrency Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `concurrency_matrix.payload(index, size)` | Hashes decimal index text with SHA-256, repeats the 32-byte digest and truncates to requested size. | Deterministic synthetic bytes, not real preprocessed samples. CLI requires positive sizes; helper itself has no argument validation. |
| `concurrency_matrix.worker(backend, root, port, indices, epochs, size, ready, start, results)` | Opens a Java store in namespace `concurrency` or shared durable mmap; announces readiness and waits up to 120 seconds for start. For every index each epoch, fetches one key, strips mmap's four-byte prefix, computes/publishes misses, and checks payload hash. Sends pass/count/time metrics, or catches `BaseException` and sends failure details; closes the store in `finally`. | Each Java operation is a singleton batch. Miss bytes are not counted in `bytesRead`. Timing includes checksumming and payload recomputation even for hits, not just storage lookup. Store close occurs after sending the result; close failure can turn a reported pass into nonzero process exit, caught by the parent. Unknown backend strings follow mmap branch when called directly. |
| `concurrency_matrix.run_clients(backend, root, port, clients, workers, samples, epochs, size)` | Uses multiprocessing `spawn`, creating `clients * max(1, workers)` processes. Each client gets ranks partitioning indices by stride. Starts processes, waits for each readiness message, sets a shared event, collects reports with 300-second queue timeouts, joins each for 15 seconds and requires successful exits/reports. Returns synchronized aggregate throughput plus startup-inclusive duration. In `finally`, kills live children and closes queues. | `workers=0` still means one process per client. Every client reads the full sample set across its ranks; clients share keys. Timeouts apply per queue read/join, not one global campaign deadline. Synchronized wall ends after result collection, before join/close; startup-inclusive time includes successful joins. Empty rank partitions are possible. No GPU/model work. |
| `concurrency_matrix.main(argv)` | Parses backend/client/worker matrix, repetitions, samples, epochs, payload bytes, reuse percentage, seed, output and resume. Requires supported backends, positive client/workload sizes, nonnegative worker counts, reuse 0-100 and no duplicate matrix entries. Opens immutable campaign metadata and delegates execution. | Defaults Aether/mmap, clients 1/2/4, workers 0/2/4/8, 10 repeats, 128 samples, five epochs, 65536 bytes, reuse 66.6445%, seed 20260904. Comma tokens are not stripped; conversion errors may occur before `parser.error`. Host/source/protocol resume checks are delegated to `campaign`. |
| `concurrency_matrix.run_campaign(args, backends, client_counts, worker_counts, metadata)` | Iterates client/worker/repeat conditions with backend order shuffled using seed+repeat. Validates and skips existing receipted results. New trials use temporary stores, start a Java daemon even for mmap, prepopulate `round(samples * reusePercent / 100)` keys, run clients, and verify every final key's bytes. Adds role/order/accounting caveats, validates and saves result/receipt. | Backend order repeats across client/worker conditions for the same repeat seed. Prepopulation, daemon startup and final verification are outside synchronized client throughput. Stores are disposable; report evidence persists. Prepopulation store close is not protected by a local `finally`; errors unwind outer contexts. A result without a valid receipt is rejected, not automatically recovered. |
| `concurrency_matrix.validate_report(report, metadata)` | Checks protocol kind and condition membership/range, deterministic backend order, pass flags, process/report counts, final unique-key count, initial reusable count, positive finite elapsed time, exact aggregate throughput consistency, total worker sample counts and each worker's hits+misses accounting. | Does not re-read stores or recompute payload hashes. Does not check publication attempts, bytes-read totals, per-worker timing or startup duration. It validates a reported measurement; receipt integrity is separate. Does not require every planned trial to exist. |
| `concurrency_matrix.validated_concurrency(root)` | Loads campaign metadata, scans matching trial JSONs excluding receipts, loads each verified result, runs semantic validation, rejects duplicate backend/client/worker/repeat identities, and requires at least one report. | May return a partial campaign; does not prove matrix completeness. Does not bind each report to its filename as the execution skip path does. Source/environment evidence is delegated to campaign loader. |

## Fault Functions

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `fault_injection.run_trial(root, point, mode, timeout, metadata)` | Creates a fresh trial directory, obtains Java classpath and starts `TrainingCacheFaultProbe write` with preview enabled and stdout/stderr in `writer.log`. Polls every 20 ms for exact boundary marker text, rejecting early writer exit or timeout. Always kills a live writer and waits up to 15 seconds. Runs a separate timed/checking `verify`, saves recovery output, parses the last stdout line beginning with `{`, attaches boundary/mode/kill/exit metadata, saves plain or campaign result, and returns its file hash. | Default timeout 60 seconds. Process creation happens before the kill/wait `try`; failed launch leaves the fresh directory/log. Marker read/parse errors propagate. Recovery failure occurs before `recovery.log` write, so captured output is not saved by this function on that path. Last JSON line is assumed to be the report; no Python-level report schema validation. Does not itself reject `passed=False`; campaign does. |
| `fault_injection.main(argv)` | Parses Java-only engine, supported points, single/batch modes, positive trials-per-point, output and resume; rejects duplicates/unsupported tokens. Creates `process-crash` protocol metadata and delegates campaign. | Default five boundaries, both modes and 100 trials per point/mode: 1000 trials. `--verify-checksum` and `--verify-acknowledged-writes` are accepted but do not toggle behavior; help says always enabled. Tokens are not stripped. |
| `fault_injection.run_campaign(args, points, modes, metadata)` | Enumerates fixed point/mode/trial order. If a receipt exists, loads it and requires trial ID/point/mode identity. Otherwise moves any existing interrupted trial under `interrupted-attempts` with UUID suffix and starts a new trial. Appends result/hash, writes rolling summary after each trial, prints progress, and stops on a false `passed` result. | Summary is partial while running, not proof of planned completeness. Exceptions before a result do not update summary. Resume validates result bytes/protocol/environment through receipts but does not re-run recovery or independently validate Java report fields. Failed receipted trials remain failures on resume. No randomized fault ordering or power-loss injection. |

## Boundaries and Cleanup

Fault points are `before-data-write`, `after-data-fsync`, `before-index-commit`,
`after-index-commit-before-ack`, and `after-ack`. Their exact Java mapping is in
`TrainingCacheFaultProbe`; Python treats them as marker/probe arguments rather
than inferring recovery guarantees from the names. The explicit marker match
prevents killing a writer merely because a timeout elapsed without reaching the
requested point. A timeout still kills the live writer but produces no success
result.

Systems receipt helpers bind reports to immutable campaign protocol/environment
and report-byte hashes. They are integrity fences, not independent validation of
all measured semantics. Concurrency validates more report fields locally than
fault resume does. Read the campaign reference for lock ownership, publication
ordering and same-host resume requirements.

## Verification

The AST inventory test checks all ten file-qualified declarations against the
tables. Existing systems-campaign tests cover report integrity and concurrency
worker initialization failure. Offline driver contracts check payload construction and mock the
fault writer/marker/kill/recovery lifecycle. They do not launch/kill Java,
exercise spawned shared-store contention, measure throughput or prove recovery
durability. Real runs require the built Java runtime and disposable output stores.
