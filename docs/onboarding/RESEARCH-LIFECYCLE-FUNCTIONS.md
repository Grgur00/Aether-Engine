# Research Stage Checkpoint and Persistent Service Functions

[Function index](FUNCTION-INDEX.md) | [Campaign identity](RESEARCH-CAMPAIGN-FUNCTIONS.md) | [Process ownership](RESEARCH-PROCESS-FUNCTIONS.md) | [H2 execution](H2-EXECUTION-FUNCTIONS.md)

Sources: [longitudinal_state.py](../../scripts/longitudinal_state.py) (11 functions)
and [persistent_service.py](../../scripts/persistent_service.py) (five).
All 16 explicit functions, including class lifecycle methods, are covered.
The two files implement different recovery policies. A receipt/checkpoint boundary
for a closed-store restart experiment is not a way to resume a persistent live JVM.

## Two Recovery Architectures

```text
restart-per-stage:
  prior closed checkpoint + receipt -> verify -> live store -> pending marker
  worker closes store -> inventory -> copy/verify checkpoint -> committed receipt
  interruption -> require no live lease -> restore prior checkpoint -> rerun stage

persistent-per-block:
  fresh block marker -> V0 start + controller/daemon lease
  V0..V4 use same daemon -> per-stage receipts, no live-store checkpoint
  V4 closes and accounts residence -> full-block validation/completion
  interruption -> cleanup -> preserve block -> fresh whole-block attempt
```

H2 uses the persistent controller and whole-block retry policy. The earlier restart
pilot uses execute_stage's closed-store checkpoints. Shared receipt/capacity/PID
helpers do not decide which policy applies. Do not copy a live Aether store as though
it were an independently verified closed checkpoint.

## Identity, Path and Capacity Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `backend_order(block, stage, seed)` | Shuffles the four backend names using private Random(seed), rotates by (block+stage)%4 and returns order. Does not change global RNG or enforce a 24-permutation schedule. Seed/type/range policy is left to callers; this is the earlier rotating pilot order, not H2 schedule. |
| `inventory(directory)` | Requires existing nonsymlink directory, recursively rejects symlinks and resolved paths outside root, hashes regular files into sorted relative POSIX names. No lock, open-file lease, directory hash or protection against concurrent filesystem changes. Empty directory produces empty inventory. |
| `remove_owned(path, owner)` | Rejects direct symlink, resolves both paths, refuses owner itself or anything outside owner, then recursively removes an existing descendant directory. Does not require an ownership receipt or examine inventory before deletion; caller supplies trusted owner/path. Missing descendant is a no-op; filesystem races can still raise. |
| `capacity_required(count, image_size)` | Returns `4 * count * image_size**2 * 3 * 12 + 512 MiB`: a conservative estimate for four stores, checkpoints, overhead and reserve. No argument validation, disk reservation or exact backend format model. |
| `check_capacity(root, required, free=None)` | Obtains disk free space unless supplied, rejects available<required and otherwise returns required/free bytes plus explicit nonreservation scope. Equal capacity passes. Free-space changes and format growth can still exhaust storage. |
| `stage_paths(scratch, backend, stage)` | Constructs scratch/backend/live and scratch/backend/checkpoint-vN without resolving, creating or validating names. Trusted backend/stage ownership is a caller invariant, not generic arbitrary-path sanitization. |
| `pid_alive(pid)` | Windows: OpenProcess query handle, false for invalid PID error 87, query exit code==259, close handle in finally; other access/query failures raise. Unix: kill(int(pid),0), false only on ProcessLookupError. Permission/type errors can propagate. Checks a PID, not process start identity, host ownership or worker correctness. |

PID reuse can identify a different process as live. Unix kill(pid,0) semantics
also include special nonpositive PID meanings; callers must supply actual trusted
worker PIDs. A stale lease file is not itself proof of a running process. Conversely,
permission failure must not be interpreted as safely dead. No function here kills
an orphan automatically or silently resumes beside it.

## Stage Receipt Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `save_receipt(path, report, identity)` | Shallow-merges report then identity so identity wins collisions, hashes parsed payload with evidence.digest and atomically writes one sha256/payload envelope. Returns payload. No scientific schema validation, no-overwrite, file fsync, signature or store-inventory verification. |
| `load_receipt(path, identity)` | Parses envelope using default text encoding, requires payload digest and equality for every requested identity field, returns payload. Additional payload fields are allowed. Does not independently validate counts/timing/device, compare live store or require all possible identity fields; caller chooses identity. Missing/malformed fields can raise. |
| `cumulative(stages)` | Enumerates supplied stages, checks every timing value is numeric/finite/nonnegative, compares sum with fullLifecycleMs within 1e-6, then cumulatively sums fullLifecycleMs into V0..VN keys. Does not require five stages, fixed phase names or positive preparation/training. Numeric isinstance accepts bool; fullLifecycleMs itself has no explicit finite/type guard. |

cumulative's accounting check is not a full timing schema: NaN in fullLifecycleMs
can evade the absolute-difference comparison even with finite phase values. H2
adds required stage/phase/count guards and analysis's positive finite ratio checks;
do not use this helper alone as certification of arbitrary input dictionaries.
An empty stage sequence returns an empty result. A receipt's object hash is not
the file's byte hash, so JSON whitespace alone need not invalidate it.

## Closed-Store Stage Execution

| Declaration | Behavior and boundary |
| --- | --- |
| `execute_stage(scratch, reports, backend, stage, identity, worker)` | If committed target receipt exists, validates/returns it without running worker or rechecking stores/leases. Otherwise rejects live lease, verifies prior-stage receipt/checkpoint, resolves pending interruption by owned deletion and restoration, or verifies current live store matches prior inventory. Creates live/pending marker, invokes worker, hashes/copies/verifies closed store, records checkpoint time/workspace bytes/attempt, publishes receipt, removes pending and then prior checkpoint. No persistent live-daemon resume. |

The worker contract is to return only after store closure. execute_stage does not
enforce that closure itself. It writes a pending marker before worker mutation,
but does not create/remove a PID lease; worker/controller code owns leases. Normal
next-stage execution requires both prior checkpoint and live inventory consistency.
After interruption, restored live state comes from the verified prior checkpoint;
stage zero instead starts empty. Partial current output is not trusted.

Checkpoint copying/verification and workspace stat accounting are outside worker
timing. checkpointMs starts after worker returns and includes inventory/copy/check;
the disk snapshot covers whole block scratch before prior checkpoint deletion.
allocatedBytes is st_blocks*512 only if all file stat records expose that field,
otherwise None. This is not per-process physical device-write accounting.

The receipt commit precedes pending deletion and prior-checkpoint deletion. If
cleanup fails after commit, subsequent calls can return the committed receipt
while old markers/checkpoints remain. A committed receipt fast path does not
revalidate its referenced store bytes. Next-stage preflight validates its prior
checkpoint. These JSON/filesystem steps are not one transaction, and copying is
not a storage snapshot under concurrent writes or a power-loss durable commit.

## Persistent Service Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `validate_service_stages(stages)` | Requires exactly five stages, one engineInfo PID, starts [1,0,0,0,0], stops [0,0,0,0,1] and persistent-per-block markers. Does not inspect a live process, validate PID positivity or prove recorded continuity against PID reuse; checks receipt fields. |
| `PersistentService.__init__(scratch, reports, identity, *, completed=False, factory=java_daemon, clock=time.perf_counter)` | Stores paths/identity/factory/clock and completion flag, initializes manager/daemon/start state and service lease path. No filesystem/process work or validation yet. |
| `PersistentService.__enter__()` | Creates report directory. Unless completed, rejects existing block marker, v*-*.json files or any existing scratch path; writes started marker with lifecycle. Returns self without starting daemon. completed bypasses freshness checks, not a complete-receipt loader or live-state restore. |
| `PersistentService.run_stage(backend, stage, worker)` | For Aether V0 obtains factory manager, times its enter, retains daemon and writes controller/daemon PID lease. Later Aether stages require existing daemon, never restart fallback. Makes live directory, runs callback, sets lifecycle/checkpoint policy, validates reported daemon PID and start/stop markers. Charges V0 startup, closes/times V4, records residence, recomputes Aether totals/nested stage total and saves receipt. Other backends receive markers/receipt but no Java start/stop accounting. |
| `PersistentService.__exit__(exc_type, exc, traceback)` | If both manager/daemon are held, delegates exit with body exception, then clears state and removes lease in finally. Does not suppress body exceptions by returning manager's result, remove scratch/receipts or retry. Cleanup can raise. If manager enter failed before daemon assignment, this method does not exit that partial manager; factory must own startup cleanup. |

run_stage is not itself an order/range state machine. Callers must call Aether V0
once then V1..V4; an arbitrary repeated V0 can replace retained manager state.
The separate five-stage validator and orchestration establish the recorded sequence.
The default factory is a lazy generator; factory creation occurs before startup
timer, while actual child launch occurs inside its enter. Injected factories should
respect that measurement boundary.

serviceResidenceMs spans daemon startup through final exit and can include Python
launch/validation/report gaps. It is diagnostic wall time, not the sum of the six
measured phases. Worker phases must correctly exclude externally charged startup/
shutdown. V4 shutdown can drain/export background work through java_daemon. On a
mid-block exception, cleanup closes the held service but there is no successful
V4 report to turn that interrupted lifecycle into an observation.

The started marker survives cleanup to reject partial-block reuse. Stage receipts
do not imply a block is complete. H2's completed-block loader validates all stages
and inventory before reuse, while whole-block retry preserves the failed attempt
and selects a fresh scratch directory. No tuning or performance-based retry rule
is provided by this controller.

## Verification Scope

[test_longitudinal_comparison.py](../../scripts/tests/test_longitudinal_comparison.py)
checks closed-store checkpoint validation/recovery, including a disposable child
that exits during mutation. [test_persistent_service.py](../../scripts/tests/test_persistent_service.py)
uses factory/clock doubles for one start/stop, charged timing and partial-block
rejection. Their real Java campaign tests are opt-in and are not implied by these
local fixture results. [Documentation contracts](../../scripts/tests/test_research_infrastructure_contracts.py)
check receipt identity, accounting-helper limits and ownership/failure edges.
Qualified AST checks cover all 16 declarations across these two files.
