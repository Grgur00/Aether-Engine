# H2 Protocol, Pilot Audit, and Freeze Functions

[Function index](FUNCTION-INDEX.md) | [H2 execution](H2-EXECUTION-FUNCTIONS.md) | [H2 analysis](H2-ANALYSIS-FUNCTIONS.md)

Source: [h2_protocol.py](../../scripts/h2_protocol.py). This page covers all
13 explicit functions. It documents the current separately deployable H2 harness,
not a new protocol or a live Kaggle job. The runner deliberately imports its
candidate dependencies from the preserved pilot checkout. Changing the harness
requires a new freeze; do not edit an existing receipt to accommodate changes.

## Identity Architecture

```text
successful pilot raw receipts -> workload/storage/model audit -> evidence hashes
clean candidate or verified source archive -> inventory -> exact origin commit
H2 harness files -> separate inventory/hash
original manifests + prepared hashes + runtime -> frozen protocol
24 backend permutations -> recorded schedule
source.zip + harness.zip + pilot-evidence.zip + JSON -> freeze.json digests
verify_frozen -> source/harness/protocol guard before execution
```

The candidate treatment and new orchestration have separate source inventories.
The candidate is not silently patched for deployment. The frozen storage settings
include inline placement, 32 MiB SSTable target, admission batch 16, bulk V0,
streaming-v1/deferred inventory verification, DURABLE, immutable-inline admission,
asynchronous compaction and no JFR/detailed trace. Training is five versions,
20 epochs each, small segmentation model, AdamW 0.001, BCEWithLogitsLoss,
12 warmup steps, batch 16, prefetch zero and fresh model/optimizer per version.

The endpoint is the sum of startup, modelSetup, scanAdmission, training, drain
and close across V0-V4. Launch/import, shared input/hash validation, instrumentation,
model/artifact validation and archival are excluded as declared. These constants
are protocol data, not automatic proof that every worker measured them correctly;
stage and paired checks establish the observable contract.

## Configuration and Assignment Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `read(path)` | UTF-8 JSON read/parse. No independent schema, checksum, path containment or exception translation. Callers apply the relevant validator. |
| `schedule(seed)` | Requires an actual int, excluding bool. Enumerates all permutations of the four BACKENDS, shuffles with a private Random(seed), and emits block index, fixed-base block seed 20260926+i and order. Order seed changes assignment ordering, not those block seeds. Does not mutate global random state. |
| `validate_config(config)` | Requires exact type and value for the declared schema, persistent-per-block lifecycle, counts, samples, seeds, 24 blocks, 20 epochs, V0 training, bulk/storage identity, image/batch sizes, trace/JFR/prefetch and confirmatory flag. Checks orderSeed by calling schedule. Extra keys are not generically prohibited; absent keys/errors can propagate. |

Every backend appears in each of the four positions six times over the full
24-permutation schedule. An arbitrary 12-block prefix does not necessarily have
that balance and is not a separately powered/authorized significance test. Block
seeds are independent of orderSeed; validation fixes config.seed to the same base.
The execution reference explains bounded launch versus planned sample size.

## Receipt Validation and Pilot Audit Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `validate_bulk(commit, count)` | Checks committed status, artifact/SHA call counts, entry count, target size, zero WAL payload/memtable insertions, exact verification counters/policy and streaming implementation/table/entry totals. Verified bytes must equal finish-reported table bytes. Validates receipt fields, not freshly opened SSTable bytes; missing/malformed fields can raise. |
| `validate_stages(stages, config, reference, backend, block)` | Requires five stages, exact new/reused/preprocessing/request/epoch counts, per-version seed, tensor/transform/order hashes, valid model-hash strings and exact phase names. Checks finite losses, epoch batch counts/positive wall times and mandatory positive preparation/training, then calls cumulative for timing accounting. Aether additionally requires DURABLE/integrity policy, expected cache count, enabled/idle drained compaction, one service lifetime and same-JVM bulk/training PID. Does not itself enforce CUDA/trace flags or paired equality; validate_block does. |
| `audit_pilot(directory)` | Loads the candidate campaign/environment and raw reference, accepts only the successful five-block persistent GPU pilot configuration with V0 training/bulk streaming and nonconfirmatory/non-CPU markers. Loads paired receipts, validates all backend stages/cumulative endpoints and their separate per-stage receipts, checks paired hashes/seeds, then inventories those files plus campaign/environment/preflight. Returns audit metadata/hashes and constants, not a pooled effect estimate. |

The audit reads archived raw evidence, not a supplied mean or a screenshot. It
checks paired tensor, initial-model, final-model, order and seed identity. This
supports preservation of the tested workload; it does not prove general model
quality, all possible checksum corruption, hardware independence or a production
release. Receipt counters remain measurements/assertions from the validated worker,
not an independent rerun of Java verification during freeze.

## Runtime and Source Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `pilot_runtime(report)` | Parses package command stdout lines containing == into lowercased names, requires recorded Java command success and extracts Python's first version token plus seven required package versions. Missing required packages/fields raise. Keeps the recorded Java result object for exact later comparison, not merely major JDK number. |
| `validate_runtime(expected)` | Executes java -version and obtains current Python and torch/numpy/Pillow/monai/lmdb/scipy/matplotlib distribution versions. Requires exact equality with expected runtime dict, including stripped Java stdout/stderr/returncode. Raises on drift or failed probes; no CPU/GPU workload execution or host-resume decision here. Java subprocess has no explicit timeout in this function. |
| `source_files(root=None)` | Inventories existing configured root files and files recursively under packaging DIRECTORIES, filtering EXCLUDED path parts, symlinks in recursive traversal and non-Python/toml/Markdown files under clients/python. Returns sorted relative POSIX paths with SHA-256. This is the declared packaging inventory, not every repository file or every loaded external dependency. |
| `source_identity(root=None)` | Computes source inventory, probes Git commit/status using a scoped safe.directory setting, and accepts clean Git or an artifact-provenance.json claiming clean source with an exactly matching inventory. Requires a 40-character lowercase hex originating commit. Returns commit/sourceHash/files. Does not create a commit or repair dirty source; subprocesses have no explicit timeout here. |
| `harness_files()` | Hashes the explicitly listed seven harness/config/protocol-document paths relative to HARNESS_ROOT. This identity is separate from the candidate ROOT and its packaging inventory. Missing files raise; unlisted harness additions are not automatically inventoried. |

An archive identity is an integrity check against its recorded provenance, not a
cryptographic signature or automatic Git checkout verification. The protocol binds
declared inventories; excluded outputs, optional external packages and files not
in those lists require their own controls. Exact package checks do not by themselves
identify a host; the imported campaign guard enforces recorded host/environment
identity on execution resume.

## Frozen Bundle Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `verify_frozen(directory, root=None, *, check_source=True)` | Requires exact frozen directory members and named file hashes, validates configuration, protocol/source hashes, deterministic schedule, separate schedule file, commits, schema, primary method, attempts, pilot audit, training/storage/boundary and input-binding policy. Checks harness hash; with check_source also compares current candidate and harness inventories. Returns protocol. With false retains archived checks but does not require current code inventories to match. |
| `freeze(config_path, pilot, output)` | Validates config/audited pilot/clean candidate/archive provenance, matches pilot-recorded treatment files and all recorded source hashes, verifies manifest membership and seed, constructs the immutable protocol and exact runtime/schedule, rejects output inside inventoried source directories, creates a fresh directory, writes JSON and pilot/source/harness ZIPs, writes freeze receipt and re-verifies it. Existing output is not overwritten. Failures can leave a partial new output directory for preservation, not automatic cleanup/retry. |

Bundle members are protocol.json, order_schedule.json, source-manifest.json,
pilot-audit.json, source.zip, harness.zip and pilot-evidence.zip, plus freeze.json.
Candidate source ZIP entries use fixed timestamps and contain generated archive
provenance. Pilot/source file bytes are rechecked during packaging. Harness files
are archived separately and the final current-inventory check binds their identity.
verify_frozen hashes ZIP bytes; it does not independently extract/re-audit every
inner member. Treat an untrusted bundle as untrusted code, not safe input merely
because its own unkeyed hashes agree.

check_source=False exists for archived analysis where the active checkout can
have evolved. It is not an execution escape hatch for modifying candidate code.
The execution runner uses default true before preflight, blocks and retry guards.
No changes to sample size, epochs, storage or statistical method occur here.

The separately authorized [multi-session controller](H2-SESSION-FUNCTIONS.md)
preserves this frozen treatment while explicitly amending the same-host
continuation policy. It records a new host campaign rather than altering old
receipts or changing `verify_frozen`; session 3 and final amended aggregation
are not implemented by the original freeze functions.

## Verification Scope

[test_h2_confirmatory.py](../../scripts/tests/test_h2_confirmatory.py) exercises
balanced seeded assignments, strict config/bulk/stage guards, paired identities,
runtime drift, archive/source/harness binding and invalid-pilot rejection. Freeze
tests use disposable synthetic source/evidence and some audit doubles, not the
real successful pilot or a new GPU experiment. Qualified AST entries cover every
function in this file. [Documentation contracts](../../scripts/tests/test_h2_documented_contracts.py)
also check private schedule RNG, fixed block seeds, unknown metadata and packaging
inventory scope. Source review and tests support specific contracts, not
end-to-end certification of arbitrary frozen bundles.
