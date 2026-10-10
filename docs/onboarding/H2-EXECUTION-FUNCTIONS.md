# H2 Execution, Worker, and Input Binding Functions

[Function index](FUNCTION-INDEX.md) | [H2 freeze](H2-PROTOCOL-FUNCTIONS.md) | [H2 inference](H2-ANALYSIS-FUNCTIONS.md)

Sources: [h2_confirmatory.py](../../scripts/h2_confirmatory.py),
[h2_worker.py](../../scripts/h2_worker.py), [h2_input_paths.py](../../scripts/h2_input_paths.py).
This reference covers all 24 explicit functions in these three files, including
the nested stage worker and input resolver/row adapter. Imported campaign,
PersistentService and original pilot worker implementations remain distinct source
boundaries, not fully inventoried by this page.

The [amended session controller](H2-SESSION-FUNCTIONS.md) is a separate layer
over the original frozen harness. It permits the explicitly amended second
session without changing this runner's same-host resume fence. It produces a
nested evidence tree and does not invoke this runner's flat final aggregation.

## Control and Measurement Architecture

```text
script bootstrap -> candidate scripts before dependency imports
verify frozen source/runtime -> campaign lock and same-host identity
full input/hash preflight -> owned scratch -> excluded full GPU lifecycle
scheduled block:
  attempt -> backend order -> complete V0,V1,V2,V3,V4 -> next backend
  Aether: start at V0 -> same JVM -> stop at V4
  isolated stage worker processes -> per-stage receipts -> paired block -> seal
technical failure -> preserve attempt -> fresh whole four-backend retry
completed blocks -> count-only progress -> optional verified checkpoint ZIP
24 blocks -> archived validation -> analysis/figures -> campaign inventory
```

PersistentService starts Aether only for its V0, injects the daemon into worker
requests, checks PID continuity and charges startup to V0 and stop to V4. Stage
Python processes can restart while that external JVM stays alive. A restart between
independent blocks is intentional; a restart inside V0-V4 invalidates the treatment.
There is no partial live-store resume or cross-host pooling under this protocol.

When executed as a script, the top-level preliminary parser validates candidate's
h2_bootstrap.py and prepends candidate/scripts **before** importing dependencies.
main's candidate-root flag alone does not rebind already imported ROOT when called
programmatically. Tests that import the module use the active checkout plus mocks;
that is not proof they executed the preserved candidate's GPU worker.

## Attempt and Worker Functions

TechnicalFailure is a RuntimeError subclass with no explicit methods. It marks
supported execution/correctness failures for the whole-block retry policy; not
every Python exception or protocol drift is automatically retriable.

| Declaration | Behavior and boundary |
| --- | --- |
| `technical_service(factory, scratch, reports, identity)` | Context-manager generator constructing/entering the supplied service and yielding it. Wraps supported OS/runtime/EOF/timeout/key/type/value errors from enter/body/exit as TechnicalFailure with the original cause. Other exceptions, including interruption, can propagate for later interrupted-attempt audit. |
| `subprocess_job(request, reports, name)` | Writes request JSON, opens a new exclusive log, launches this Python interpreter's harness worker with candidate ROOT/cwd, writes a parent launch lease, waits without an explicit timeout, requires zero exit then JSON output. Lease-write failure terminates/waits for child; normal terminal child removes launch lease. Supported crashes/read/parse failures become TechnicalFailure. Adds process wall time outside measured phase endpoint. |
| `run_attempt(assignment, reports, scratch, protocol, reference, paths, identity, *, job=subprocess_job, service_factory=None)` | Creates a fresh report directory/start receipt, selects same-JVM bootstrap/PersistentService unless injected, loops backend-major then version-major, calls service.run_stage and copies live diagnostics, validates each backend and paired block, writes backend summaries/paired receipt and seals evidence. On failure preserves partial evidence; does not compute significance or retry itself. |
| `run_attempt.worker(name, stage, live)` | Nested callback builds config/backend/version/store/manifest/model-seed/reference/lease request, adds live daemon for Aether and optional verified input binding, then calls the injected job. No source mutation, schedule reshuffle or CUDA work in this callback itself. |
| `backend_summary(name, stages, assignment, protocol)` | Derives cumulative phase sums, backend position, PID-by-version, preparation/training/phase seconds, reuse/recompute counts, hashes and optional disk/process/GPU/write observations. bytesWritten scope remains existing Linux pilot process accounting, not physical device writes. Assumes prior validation and marks correctness true; does not establish it independently. |
| `h2_worker.main(argv=None)` | Parses candidate/request/output, prepends candidate scripts, writes its PID lease before heavy dynamic pilot-worker import, rejects CPU fixtures/unavailable CUDA and V0/trace drift, runs original execute with optional metadata resolver binding, adds execution identity fields and writes result. Finally removes worker lease, including import/execution failure. Does not launch/stop the daemon or independently validate the complete block. |

The worker marker device=cuda follows availability/fixture guards, not a per-op
device trace. The actual preserved worker owns model/tensor/training computation.
workerProcessWallMs includes launch/import/validation/report overhead but is not
added to the six-phase cumulative endpoint. Stage logs contain subprocess output;
the controller prints block/backend/version progress separately. A blocking child
wait is not a user-facing watchdog or platform timeout guarantee.

## Evidence and Retry Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `seal(directory)` | Hashes all recursive files except any named completion.json, then writes status/files/inventoryHash to top-level completion.json. Does not sign evidence or validate scientific correctness; run_attempt does that before sealing. |
| `verify_seal(directory)` | Recomputes the same inventory and requires passed status and exact files/digest. Detects missing/changed/added inventoried files. Exclusion is by basename, including nested completion.json; symlink/hostile-path handling is not the checkpoint API's policy. |
| `validate_block(block, protocol, reference, assignment, *, gpu=True)` | Checks experiment/index/seed/order/hash/source/backend set and correctness flags; validates each backend's five stages, cumulative totals, CUDA when selected, trace/fresh-state and input binding. Requires paired tensor/initial/final model/order/seed identity at each version. Returns block. This is the scientific receipt gate, not seal alone. |
| `assert_no_live_leases(reports)` | Scans recursive *.lease.json and uses imported pid_alive for every recorded PID. Any live PID blocks retry; malformed lease or inability to query liveness propagates. A lease file alone is not proof of liveness, and PID reuse/start-identity is not checked here. |
| `load_completed(reports, identity, protocol, reference, assignment)` | Requires valid seal, paired receipt identity/hash and validate_block, then checks all 20 per-stage receipts exactly equal paired embedded stages. Returns completed block. Does not rerun training, tolerate edited results or infer completion from progress.json. |
| `run_with_retries(parent, scratch, assignment, protocol, reference, paths, identity, *, execute=run_attempt, source_guard=None)` | Walks predeclared attempts 1..max. Reuses a fully validated completion without rerun; existing incomplete attempts require no live leases and matching ownership/assignment, then retain/write interruption failure and consume that attempt. New attempts run source/capacity guards and whole execution in fresh attempt scratch. Only selected technical/subprocess/EOF/timeout failures are archived and retried. Exhaustion raises. |
| `archived_attempt(parent, meta, protocol, reference, assignment, *, measurement_role)` | Requires exactly one completed attempt, contiguous numbered history within max, failure files for other attempts and no later attempt after success. Builds expected campaign/source/environment/block/attempt/role identity and load_completed. Failure reason files must exist; their every field is not separately schema-validated here. |

Slow but valid completed blocks are retained; no elapsed-time cutoff selects a
better-looking retry. Seal/identity failures on a completed attempt are hard stops,
not permission to replace it. Existing interrupted attempts are not partially
resumed. Each new attempt preserves assigned order/seed and runs every backend.
The source guard is outside performance exclusion logic: drift requires stopping,
not collecting replacement observations under the old identity.

## Campaign and Checkpoint Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `launch_limit(value)` | Requires actual int in 1..24, excluding bool/float/string. Returns value. Bounded execution does not change planned sample or analysis's 24-observation requirement. |
| `checkpoint(output, destination)` | Requires ZIP destination outside output, writes a temporary ZIP beside destination, rejects symlinks, skips .campaign.lock basename, hashes included files and writes checkpoint.json with continuation/inference policy. Reopens ZIP, tests CRC and each stored SHA, then os.replace publishes it; finally removes temp. Does not fsync file/directory or authorize cross-host resume. |
| `run(frozen, output, scratch_root, *, resume=False, preflight_only=False, stop_after_blocks=24, checkpoint_zip=None, input_root=None)` | Validates limit/frozen/runtime and forbids unrecorded JVM option env vars, enters imported campaign lock/identity guard, logs launch, redoes input preflight with optional binding, verifies hashes/manifests, creates/checks owned scratch and archived freeze/reference. Reuses or executes full excluded preflight, then scheduled prefix with per-block source/capacity checks, count-only progress, owned block scratch deletion and optional checkpoints. Below 24 emits partial completion without inference; at 24 calls archived analysis. |
| `write_artifact_manifest(paths, reference, output)` | Reads version CSV rows, derives existing aether_ml artifact keys in monai-pilot-v1 namespace, records source/image/mask digests, membership hashes, added/reused prefix counts and per-version identity digest. Does not fetch/cache payloads or independently establish membership; input preflight validated those assumptions. |
| `analyze_output(output)` | Loads campaign, validates archived freeze with check_source=False and protocol equality, checks archived input identity, requires excluded preflight and exactly all scheduled block directories, validates attempt history/receipts, analyzes/plots, writes analysis/limitations and completion inventory. It reads archived evidence rather than trusting active source or progress counts. Partial output writes can remain if later figure/archive work fails. |
| `h2_confirmatory.main(argv=None)` | Parses audit-pilot/freeze/run/analyze subcommands with required candidate flag, forwards arguments and prints audit/freeze results. Uses functions/dependencies already selected during script bootstrap; it is not a generic in-process candidate selector or uploader. |

Preflight runs all 100 epochs for **each** of the four backends and is excluded
regardless of its performance. Input preflight is redone on resume, while completed
execution preflight/blocks are integrity-checked and reused. stop_after_blocks
selects schedule[:limit], not "that many additional blocks". A later same-host
launch with limit 24 revisits/reuses the prefix before executing the remainder.
Progress reflects validated blocks encountered in that launch, not live GPU status.

Workspace path/owner receipts and imported remove_owned constrain scratch cleanup.
Capacity is a conservative free-space estimate, not a disk reservation. The
campaign guard rejects changed protocol, source or measured host environment;
different Kaggle sessions cannot simply pool 12+12 under this single-host policy.
Checkpoint archives contain evidence, not a running daemon or resumable partial
live store. The previous published ZIP survives a failed later ZIP build; its
availability after platform termination still depends on the platform.

## Read-Only Input Binding Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `binding(physical_root, logical_root=LOGICAL_ROOT)` | Resolves an existing directory and stores policy schema, absolute logical root and physical root. The error calls it read-only, but this function does not test filesystem writability/permissions or hash data. No aliases/copies created. |
| `resolve_path(value, mapping, manifest_root=None)` | Requires exact mapping keys/schema, rejects .. path parts, resolves relative paths using a required manifest root, remaps logical-prefix paths or accepts declared physical paths, then resolves symlinks and requires physical-root containment. Returns ordinary Path. Does not itself require the target file to exist or hash content; data preflight does. |
| `input_paths(workload, mapping, *, comparison=None)` | Context-manager generator temporarily replaces workload.resolve_manifest_path and optional comparison.read_rows, yields, then restores original callables in finally. Changes module/object resolver state, so use during serial metadata loading rather than concurrent shared consumers. No CSV edits, input writes or measured image-read translation layer. |
| `input_paths.resolver(value, manifest_root)` | Nested adapter delegates to resolve_path with supplied mapping/root. Returns concrete physical Path for the original worker's metadata loading. |
| `input_paths.rows(path)` | Nested optional comparison wrapper reads original fields/rows, builds new row dicts replacing image_path/mask_path with validated physical strings, leaves other fields and original CSV bytes unchanged. Manifest hash is not recomputed over these adapted in-memory rows. |

The execution worker requires the frozen LOGICAL_ROOT; binding itself permits
custom logical roots for tests. Traversal and escaping symlinks are rejected,
but resolution is not an open-file lease against later filesystem mutation. Keep
the declared data mount immutable and run original source/tensor hash preflight.
input_paths restores module callables on error but is not a thread-local adapter.

## Verification Scope

[test_h2_confirmatory.py](../../scripts/tests/test_h2_confirmatory.py) checks
backend-major lifecycle, lease timing, failure/retry/valid-result reuse, completed
evidence tampering, full preflight and bounded launch, checkpoint retention and
archived analysis. [test_h2_input_paths.py](../../scripts/tests/test_h2_input_paths.py)
uses the preserved pilot parser with disposable nested-mount fixtures, unchanged
CSV bytes and original source-hash validation, plus resolver restoration/rejection.
These local mocks/files do not launch a real CUDA campaign or certify platform
termination/resume. They are not live execution progress. All 24 explicit functions
in these three files have qualified inventory entries here.
[Documentation contracts](../../scripts/tests/test_h2_documented_contracts.py)
add seal-exclusion/new-file checks and nested resolver restoration. They document
the current trust boundary rather than strengthening the frozen implementation.
