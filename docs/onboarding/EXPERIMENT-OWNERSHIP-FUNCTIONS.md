# Experiment Output and Scratch Ownership Functions

[Function index](FUNCTION-INDEX.md) | [Hit-path diagnostics](HIT-PATH-FUNCTIONS.md) | [Research process ownership](RESEARCH-PROCESS-FUNCTIONS.md)

Sources: [cache_workspace.py](../../scripts/cache_workspace.py) and
[experiment_output.py](../../scripts/experiment_output.py).
All **4 explicit declarations** are covered. Lock-operation lambdas are described
with exclusive_output; they are anonymous callbacks, not additional declarations.

## Ownership Architecture

```text
durable report directory
  -> exclusive_output: nonblocking OS lock for cooperating writers
  -> freeze_metadata: initial protocol/environment or unchanged resume checks
  -> cache_workspace: capacity estimate -> unique owned temporary store
       success: verify child ownership -> copy diagnostics -> retain or remove
       body failure: retain store and mark interrupted
```

These helpers do not run experiments, certify data correctness, freeze a daemon
configuration or implement checkpoint resume themselves. Callers compose ownership,
metadata and workload gates. Locking and metadata freezing are separate APIs:
freeze_metadata does not acquire a lock automatically. Report directories survive
temporary-store cleanup.

## Capacity and Temporary Store Functions

| Function | Behavior and boundaries |
| --- | --- |
| `payload_floor(dataset, samples, image_size, num_classes=1000)` | Requires positive samples, image size and class count. Estimates two uncompressed stores: OCT5K uses 3*size^2 bytes per sample; other datasets use 6*size^2 plus num_classes for COCO or one byte otherwise. Returns 2*samples*payload. Does not validate dataset names or integer dimension types and is not a full disk-space prediction. |
| `cache_workspace(report_directory, *, scratch_root=None, retain=False, payload_bytes=0, name='cache-workspace')` | Resolves/creates report and scratch roots, reads available disk space and requires payload floor plus 64 MiB reserve. Creates a unique aether-store- child, writes owner/running receipts, and yields the path. Body BaseException writes interrupted/retained=True and rethrows. Normal exit checks generated-child ownership, copies top-level log/compaction diagnostics, optionally removes the store, then writes complete/free-space receipt. |

The floor excludes framing, indexes, metadata, transient writes, compaction space,
filesystem overhead and other processes' consumption. The 64 MiB reserve is small
and fixed, not a reservation. Passing a check does not guarantee later writes can
finish. Unknown dataset names use the non-COCO alternative branch. The helper does
not shrink the requested sample count when capacity is insufficient.

Capacity failure writes name.json with insufficient-capacity and raises before
creating a temporary child. payload_bytes is caller-supplied and is not independently
checked for positivity or recomputed from the workload. Directory creation occurs
before the capacity check and may itself fail.

owner.json contains reportDirectory. It is informative metadata; cleanup verifies
the generated path's current symlink state, resolved parent and aether-store-
prefix rather than trusting owner.json. Only that generated child is removed,
never the scratch root or report directory. retain=True still performs ownership
checks and diagnostic copying, but leaves the store. The default scratch root is
the report directory itself, so durable reports and the generated child can share
a filesystem without being the same deletion target.

Logs copied are only owned/*.log and owned/*.compaction-*.json, prefixed by name
in the report directory. Nested diagnostics or all store data are not copied.
Interrupted body execution does not run success-path diagnostic copying or removal;
the store remains available in place. BaseException includes cancellation/interrupts.

Failures before the yield-protected try (owner/running receipt creation), or during
success cleanup/copying/final receipt writing, are not converted to interrupted
receipts. A removal can succeed before the complete receipt fails; conversely a
copy failure retains the child without a completed status. Diagnostic destinations
can be overwritten. name is used to construct paths without independent name/path
sanitization, so callers should supply fixed local names. There is no output lock
inside cache_workspace.

## Exclusive Output and Metadata Functions

| Function | Behavior and boundaries |
| --- | --- |
| `exclusive_output(root)` | Creates root and opens persistent .experiment.lock in a+b mode. Ensures at least one byte, then nonblockingly acquires a one-byte msvcrt lock on Windows or fcntl exclusive flock elsewhere. Acquisition OSError becomes 'another runner owns this experiment output'. Yields no object; finally seeks to zero and releases before file close. Does not delete the lock file, inspect experiment metadata or enforce freshness. |
| `freeze_metadata(root, plan, environment_id, provenance, resume)` | Existing protocol requires resume=True, parsed plan equality, exactly the expected environment-<id>.json file and matching saved measurementIdentity/sourceSha256. Otherwise rejects orphan environment files or block-*.json/block.json measurements, then writes environment first and protocol second. Does not itself lock, execute resume, validate schema or compare all provenance fields. |

The acquire/release lambdas call msvcrt.locking with LK_NBLCK/LK_UNLCK for one byte,
or fcntl.flock with LOCK_EX|LOCK_NB/LOCK_UN. Lock-file existence alone is not proof
that a runner is alive: the OS-held lock is the exclusion mechanism. Successful
reacquisition after exit is normal. Other processes must use the same locking
protocol; this is not a universal filesystem write barrier. A release error can
replace a body error, although closing the file still releases OS resources.
Any acquisition OSError receives the ownership message, not only a verified busy
lock error. root is not resolved or constrained by this function.

freeze_metadata compares parsed JSON structures, not byte-for-byte formatting.
On resume it tolerates changes to provenance fields outside measurementIdentity
and sourceSha256, such as capture time. It does not rewrite accepted metadata.
New metadata writes are sequential, not an atomic pair: a protocol-write failure
can leave an environment file that causes the next invocation to reject the output.

The new-output guard recognizes specified environment/block names, not every
possible measurement artifact. Arbitrary unrelated files can exist. resume=True
without protocol/recognized measurements initializes metadata rather than proving
a previous run exists. Callers must create root beforehand and enforce their own
expected files, completeness and environment_id/path constraints. No source files
or binaries are rehashed here; provenance equality is evidence supplied by the
caller, not independent current-environment verification.

## Coverage and Evidence

AST checks require all four declarations from both modules. Existing
[workspace tests](../../scripts/tests/test_cache_workspace.py) cover successful
cleanup, failure retention, insufficient capacity and explicit retention.
[output tests](../../scripts/tests/test_experiment_output.py) cover unchanged
metadata resume and exclusive lock acquisition/release. Focused contracts examine
estimate formulas and metadata fields outside the equality boundary. These are
disposable local filesystem checks, not a real experiment run.

Combined coverage is **4/4 declarations (100%; 0% remaining)**. The full repository
documentation goal remains active.
