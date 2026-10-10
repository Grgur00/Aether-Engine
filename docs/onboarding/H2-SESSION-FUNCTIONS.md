# H2 Multi-Session Controller Functions

[Function index](FUNCTION-INDEX.md) | [Original execution](H2-EXECUTION-FUNCTIONS.md) | [Freeze](H2-PROTOCOL-FUNCTIONS.md) | [Inference](H2-ANALYSIS-FUNCTIONS.md)

Source: [h2_sessions.py](../../scripts/h2_sessions.py). This page covers **all eight
explicit functions** in the separately hashed session controller. The source
guard lambda in `run` is described separately, not counted as a named function.
The [session amendment](../../kaggle/H2-SESSION-AMENDMENT.md) records authorization
and reporting policy; this guide documents the implemented controller, not a
completed experiment or an unchanged single-host protocol.

## Control and Evidence Architecture

```text
main -> preserved original harness + preserved candidate dependency paths
run -> frozen source/harness/runtime verification + amendment/controller/ZIP hashes
    -> extract first-session evidence -> original receipt validators
    -> new session campaign/environment -> deterministic input preflight
    -> excluded full four-backend lifecycle -> checkpoint
    -> original assignments 09..17 -> original whole-block retry/worker machinery
    -> per-block count/checkpoint -> original session receipt validation
    -> 18 unique blocks -> partial-campaign completion, no inference
```

The original H2 treatment and worker harness remain immutable. Session 2 calls
their existing lifecycle and receipt helpers rather than reimplementing storage
or training. The new campaign has its own host identity; prior receipts retain
the old one. The original same-host `--resume` fence is not weakened. Daemon
continuity remains **within each Aether V0-V4 lifecycle**, not across blocks or
Kaggle sessions. Each session's excluded preflight is outside confirmatory counts.

## Input and Partition Functions

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `read(path)` | Reads UTF-8 text and returns `json.loads` output. | No schema check, default or error conversion. Callers validate selected fields; missing keys and malformed JSON propagate. |
| `file_hash(path)` | Opens a binary stream and returns the SHA-256 hex digest using `hashlib.file_digest`. | Hashes bytes without loading the entire file. Unkeyed integrity check, not authentication or proof of scientific validity. Requires the supported Python runtime. |
| `assignments(protocol, session)` | Requires an exact integer 1..3, rejecting booleans and strings, then returns the original schedule slice for that session. | Fixed zero-based half-open partitions are `[0,9)`, `[9,18)`, `[18,24)`: human blocks 1-9, 10-18, 19-24. Does not regenerate orders or seeds. Alone it does not validate schedule contents/length; frozen-protocol verification does that. |
| `extract_evidence(path, destination)` | Resolves the destination, opens the ZIP, rejects duplicate entry names, inspects all entries for unsafe paths/symlinks, then extracts. | Rejects normalized-vs-original filename changes, absolute/parent paths, backslashes, colons, resolved targets outside the destination and Unix symlink entries. Does not impose decompression size/count limits, remove old destination files or roll back partial extraction after read/I/O failure. |

An extracted ZIP is not automatically accepted experiment evidence. `run` first
checks the entire previous ZIP's expected SHA-256, then calls `validate_session`
on its campaign tree. ZIP CRC/read failures can propagate during extraction.
The destination is a new output subtree in this workflow, not a general-purpose
user archive import location.

## Archived Session Validation

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `validate_session(root, protocol, session, engine)` | Calls original `load_campaign` to validate campaign/environment provenance, requires exact protocol equality, checks the archived frozen bundle with `check_source=False`, compares manifest/reference/transform identities, requires exactly the expected block directory names, validates the excluded preflight and every scheduled attempt via original `archived_attempt`, and returns metadata plus accepted block payloads. | Uses each archive's original environment ID when checking receipts. `check_source=False` allows archived validation without comparing today's checkout; it does not disable execution source guards. Non-directory extras at block-directory level are not independently rejected by this set check. Session controller metadata is not itself validated by this helper. |

The original attempt validator supplies the one-successful-attempt rule,
contiguous retry history, failed-attempt reasons, maximum attempts, completion
inventory and equality between paired and per-stage receipts. Original block
validation supplies scheduled order/seed, four backend results, cumulative phase
sums, CUDA/training/input-binding checks and paired model/tensor/sample-order
identities. These are delegated contracts, not checks silently omitted because
a progress file says nine blocks completed.

The archived environment's internal digest is verified; this does not require
the new host to have the same hostname or environment ID. Exact runtime versions
and deterministic prepared artifacts are checked again for the new session.
That distinction enables the explicitly amended experiment while preserving
evidence provenance. It does not prove hosts have identical performance.

## Atomic Checkpoint Function

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `checkpoint(root, destination, engine)` | Rejects a destination inside the evidence root, creates a temporary ZIP beside the destination, inventories sorted regular files under `h2-confirmatory/`, skips `.campaign.lock`, rejects source symlinks, adds `checkpoint.json`, reopens the ZIP for CRC and byte-digest verification, then atomically replaces the destination. Deletes the temporary file in `finally` and prints a verified-checkpoint message. | Includes prior evidence, current partial/failed attempts and controller/amendment files, not only successful timings. Archive I/O is outside measured backend phases. Replacement is atomic path publication, not an explicit file/directory fsync guarantee. Final verification reads each archived entry into memory for hashing. |

`checkpoint.json` uses schema `aether-h2-session-checkpoint-v1`, file hashes,
inventory digest and count-only inference/continuation statements. It is an
archive integrity receipt, not campaign completion or a signature. Source files
changing between inventory/hash and ZIP writing are detected by the re-read
verification. A failing checkpoint does not overwrite the prior destination.

## Session Execution Function

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `run(args, engine)` | Verifies the original frozen bundle; binds amendment schema, original protocol hash, fixed partitions, this controller's file hash and previous ZIP hash; requires session 2; checks exact runtime and forbids unrecorded JVM option variables. Creates fresh output/scratch trees, imports and validates prior session 1, archives amendment/controller, opens a new campaign, resolves actual input mount paths without rewriting frozen CSVs, verifies deterministic identities, archives freeze/input/artifact/session metadata, runs excluded preflight and original scheduled blocks 10-18, checkpoints each success, validates the completed current session and 18-block uniqueness, then writes partial completion. | Not a generic resume or final-campaign runner. Existing output or scratch roots fail `exist_ok=False`; there is no cross-session partial lifecycle recovery. No statistics, significance tests, figures or final 24-block completion are produced. Does not inspect performance to choose assignments or stop early. |

Detailed ordering matters:

1. Integrity/runtime guards run before creating output. Session 2 is the only
   currently allowed launch, even though `assignments` also understands session 3.
2. Prior evidence is extracted under `previous-session-01/h2-confirmatory` and
   validated before the new campaign is created. Its bytes and identities are not
   rewritten to resemble the new host.
3. A uniquely named scratch directory lives beneath the newly created scratch
   root. The original retry helper checks capacity and manages whole attempts.
4. The new session lives under `session-02`. `session.json` records human range
   10-18, amendment digest, current environment ID and previous environment ID.
   Stage/block identities still use the original frozen protocol/source plus the
   new campaign environment ID.
5. The source-guard lambda calls original `verify_frozen(args.frozen)` before
   each attempt through `run_with_retries`. Original worker launches use their
   frozen harness path, not this controller as a replacement worker.
6. After each completed block, progress count is `assignment.block + 1`, valid
   only because the first nine were validated and the selected schedule is the
   next contiguous slice. It then removes that owned block scratch subtree and
   checkpoints. The excluded preflight scratch is not explicitly removed here.
7. Final current-session validation and the 18-unique-ID check precede the
   `partial-campaign` receipt. Failure at any step preserves available evidence;
   the controller has no global cleanup/restart handler.

The entry guard does not check every amendment field: the document digest,
descriptive sensitivity wording and other reporting metadata are not independent
schema invariants in `run`. The deployment wrapper separately pins uploaded file
hashes. Do not confuse metadata presence with an implemented session-3 aggregator,
session sensitivity analysis or a cryptographic trust boundary.

## CLI and Dependency Selection

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `h2_sessions.main()` | Requires path arguments for candidate root, harness root, frozen bundle, amendment, previous evidence ZIP, output, scratch root, actual input root and checkpoint ZIP, plus integer session. Prepends candidate scripts, then original harness scripts, imports original `h2_confirmatory` as `engine`, and calls `run`. | Harness scripts take precedence over candidate scripts for overlapping module names; candidate supplies the original dependencies. CLI does not provide `--resume`, `analyze` or an automatic upload command. Path selection itself is not provenance validation; subsequent guards bind treatment and harness bytes. |

## Verification and Reporting Scope

[test_h2_sessions.py](../../scripts/tests/test_h2_sessions.py) contains five
parameter-expanded cases: complete disjoint 9/9/6 schedule slicing and invalid
session types, three unsafe archive-name cases, and wrong-protocol rejection
before block inspection. It does not run training, validate a successful session
with synthetic receipts, exercise atomic replacement failure, or test session
resume/aggregation. Original H2 tests cover delegated validators separately.
The documentation AST gate requires exactly one entry for every named function.

Before the second-session upload, the downloaded first-session bundle was locally
checked with original campaign, frozen, preflight, attempt and stage validators.
That supports that particular evidence tree, not universal archive safety or
proof that the new remote execution completes.

The original campaign prohibited cross-host pooling. This controller implements
an explicitly authorized amendment after session 1, not retroactive authorization
inside original receipts. Final reporting must disclose host/session boundaries
and possible clustered performance effects. Session 3 and final aggregation remain
unimplemented in this file; the original flat single-host `analyze_output` cannot
be applied directly to this nested multi-session tree. Until the full 24-block
evidence and amended aggregation are validated, report correctness/counts only.
