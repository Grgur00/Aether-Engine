# Research Campaign Identity and Evidence Functions

[Function index](FUNCTION-INDEX.md) | [Process and build ownership](RESEARCH-PROCESS-FUNCTIONS.md) | [Stage and daemon lifecycle](RESEARCH-LIFECYCLE-FUNCTIONS.md)

Sources: [system_campaign.py](../../scripts/system_campaign.py) and
[evidence.py](../../scripts/evidence.py). All nine explicit functions are covered.
These modules support different experiment families: the systems campaign API
uses campaign.json and environment.json; the training-matrix evidence validator
uses protocol.json and environment-ID files. Their validators are not interchangeable
with [H2's archived block gate](H2-EXECUTION-FUNCTIONS.md).

## Architecture and Authority

```text
Java build validation -> environment probes -> selected measurement identity
systems campaign: OS lock -> fresh immutable metadata or exact resume checks
trial report -> protocol/environment fields -> atomic JSON -> byte-hash receipt
load result -> recorded bytes and identity checks

training matrix: protocol + condition/block/block.json
  -> environment identity + underlying training/manifests
  -> checksum/model/durability/cache/order/throughput consistency
  -> optional declared workflow validation -> accepted block
```

OS locks prevent cooperating controllers from owning one campaign simultaneously.
Unkeyed hashes detect change relative to the recorded receipts; they do not sign
results or independently observe training. A caller can fabricate a self-consistent
fixture. Correctness flags are necessary evidence gates, not independent proof of
model quality, device execution or all storage invariants.

## Systems Campaign Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `measurement_identity(report)` | Selects current host name, platform, CPU text/count, Python, full Java/package command result objects and named performance environment variables. Removes CPU lines containing MHz, BogoMIPS or CPU(s) scaling. Does not include capturedAt, executable path, GPU/storage/mount probes, javaBuild, archive provenance or source hashes in this identity; source is compared separately. |
| `load_campaign(root)` | Reads saved campaign/environment JSON and checks campaign schema, protocol digest, environment file byte digest, recorded measurement-identity digest and source-inventory equality. Returns saved metadata. Does not probe current host, validate a Java build, enforce clean Git or inspect trial files; campaign's resume path performs current-state comparison. |
| `campaign(root, protocol, resume=False)` | Context-manager generator resolving/creating output, opening persistent .campaign.lock and acquiring nonblocking kernel ownership: one byte with msvcrt on Windows, flock on Unix. Validates Java classpath before collecting environment. Existing campaign requires explicit resume and exactly matching protocol/source/measurement identity. Fresh creation rejects any unversioned content beyond the lock, writes environment then campaign metadata, yields under lock and unlocks in finally. |
| `save_result(path, report, meta)` | Shallow-copies report, overwrites protocolHash/environmentId from meta, atomically replaces report JSON, then separately writes its .receipt.json byte SHA. Returns augmented report. Does not validate scientific payload or prohibit replacement; interruption between the two writes can leave unmatched report/receipt. |
| `load_result(path, meta)` | Reads report and sidecar, rehashes report bytes, requires exact protocol/environment fields and returns it. Missing/malformed data can raise before the mismatch error. Does not rerun science, verify a campaign independently, or interpret performance. |

The lock file may remain after normal completion or a controller crash; kernel
ownership expires when its open handle closes. File existence is not a live-owner
test, and deleting the pathname is not the unlock protocol. Lock acquisition errors
are translated to the same ownership error, including errors other than contention.
The lock coordinates this campaign path, not all output paths or remote hosts.

Resume re-collects environment but preserves the original environment.json bytes.
measurement_identity builds a new outer dict but retains nested Java/package and
performance-environment objects from its report; it is not an immutable deep copy.
Later in-memory mutation of those objects can change both views until serialization.
Package output, Java output and selected environment variables are compared exactly;
host name is part of identity. GPU identity is recorded descriptively but is not
included by measurement_identity itself. A generic systems campaign does not
require clean Git; the caller's frozen protocol/source policy supplies that guard.
H2's exact runtime and source checks add constraints, not a universal capability
of this context manager. Source equality covers the declared inventory only.

Fresh environment and campaign writes are separate. A failure after environment
publication can leave an incomplete directory which a later call rejects instead
of repairing or overwriting. The JSON helper uses replace, not fsync or a multi-file
transaction. No remote scheduler or live progress monitor is started here.

## Shared Digest and Training Evidence Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `digest(value)` | SHA-256 of compact JSON encoded with sorted mapping keys. Stable for mapping insertion order, not a general semantic normalization: list order, numeric representation and JSON serialization choices matter. Uses default ensure_ascii and allow_nan; unlike write_json it does not reject NaN/Infinity at serialization. Unsupported values raise. |
| `file_digest(path)` | Streams a binary file through hashlib.file_digest using SHA-256. Returns byte identity including whitespace, not parsed-object identity. Missing/unreadable files raise; no path containment or signature verification. |
| `read(path)` | UTF-8 JSON read/parse without independent schema, ownership, checksum or field validation. JSON's default parser can accept nonfinite constants; later validators determine which fields require finite values. |
| `validate_block(path)` | Reads a training-matrix block, infers campaign root as path.parent.parent.parent and validates schema/PASSED/correctness, frozen protocol digest, exact condition membership/ID, actual-int block index/seed, environment ID/source and optional confirmatory design/clean-source checks. Rehashes training.json and V1/V2 manifests, requires Java/CUDA/passed/checksum flags and one run, paired model/durability/cache/order/reuse/throughput consistency, and optional workflow evidence. Returns the block. This is not the H2 or systems-trial loader. |

For a confirmatory training block, the validator dynamically calls confirmatory
plan/training guards. When its design equals the current DESIGN, it additionally
checks the frozen append-only manifest hashes and training seed. Older supported
designs remain separate. It does not convert an arbitrary record to the current
design or authorize mixing campaigns. Source cleanliness can be established by
recorded clean Git or verified clean archive flags in the bound environment.

Throughput is compared exactly with steadyState.effectiveSamplesPerSecond derived
from the underlying run and must be positive/finite for every backend, with at least
raw/Aether/mmap. The number/type checks use isinstance(int, float), so bool is not
universally excluded as a numeric value. The block-index check specifically excludes
bool. Model parity, Java engine and CUDA availability are reported flags, not
per-operation hardware traces or a model evaluation metric.

## Optional Repeated-Workflow Evidence

When protocol has workflowExperiments, optional population.json must match its
byte hash and embedded population summary. Initial costs come from the report's
Aether/mmap populationWallMs and zero for other backends. With no population
report, expected initial costs are zero. This is not H2's six-phase lifecycle.

Each workflow entry must be ordered, name its expected training file, match the
saved byte hash, have successful Java/model/cache flags, and match per-backend
lifecycle totals and reuse summaries. The validator accumulates those costs,
checks every recorded cumulativeWorkflowMs, then the final workflowCostMs. It
does not repeat every top-level CUDA/confirmatory/count/finite-throughput check
on each workflow report. Describe the checks actually present, not a recursive
promise that all reports underwent identical validation.

This function validates one block at a fixed path layout. It does not ensure all
planned blocks exist, enforce between-block independent starts, establish balanced
ordering across the campaign or compute a statistical test. The family-specific
orchestrator and analysis loader own those responsibilities.

## Verification Scope

[test_system_campaign.py](../../scripts/tests/test_system_campaign.py) uses real
local lock/file behavior with substituted environment/build probes for ownership,
resume and tamper guards. [test_evidence.py](../../scripts/tests/test_evidence.py)
uses explicit artificial reports, manifest bytes and flags to check training-block
consistency/rejection; those fixtures are not GPU evidence. The
[documentation contracts](../../scripts/tests/test_research_infrastructure_contracts.py)
check identity selection, two-write publication boundaries and recorded-vs-current
validation. Qualified AST coverage checks enumerate all nine declarations here.
