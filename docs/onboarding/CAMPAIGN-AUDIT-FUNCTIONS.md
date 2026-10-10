# Campaign Audit and Preservation Functions

[Function index](FUNCTION-INDEX.md) | [H2 analysis](H2-ANALYSIS-FUNCTIONS.md) | [Systems campaign receipts](RESEARCH-CAMPAIGN-FUNCTIONS.md)

Sources: [submission_gate.py](../../scripts/submission_gate.py),
[preserve_campaign.py](../../scripts/preserve_campaign.py), and
[confirmatory_v1.py](../../scripts/confirmatory_v1.py). This reference covers
**5 explicit functions across three complete files**, with file-qualified names.
The archived V1 protocol and broad submission gate are not H2's V0-V4 lifecycle
protocol. Use the H2-specific references for that experiment.

## Architecture

```text
systems fault summary -> individual result hash + campaign receipt + contract
research result tree -> per-family validated blocks -> coverage and claim checks
    -> automated readiness report + mandatory human review
source ZIP inventory + result ZIP checksums -> safe temporary extraction
    -> passed status/source binding -> complete paired indices/environment checks
    -> copied reports + original archives + preservation receipt
archived V1 design -> exact plan/training configuration validation only
```

Integrity, completeness, scientific support and submission approval are separate
questions. A valid receipt does not imply enough trials; enough trials do not
force a favorable result. No function here authorizes modifying, adding or
discarding runs to obtain a preferred claim.

## Function Reference

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `submission_gate.validated_faults(root)` | Loads immutable process-crash campaign metadata and summary trials. Rejects duplicate/unsafe trial IDs, changed result hashes, receipt mismatch and summary/result inequality. Requires passed/reached-boundary flags, zero lost acknowledged writes/corruption and atomic batch visibility; batch targets must include at least two artifacts. Requires point/mode/index membership and exact formatted ID. Returns trials. | Validates recorded evidence, not re-execution. Does not require full trial coverage or even a nonempty list; `audit` applies counts separately. Does not compare kill mechanism, writer exit code or recovery duration here. Missing fields/files and malformed IDs propagate. |
| `submission_gate.audit(root)` | Independently inspects primary, coverage sweeps, process-crash, concurrency, transform evolution, cumulative workflow and DALI result families. Builds coverage checks and separates primary/secondary claim booleans. Returns schema v2 with automated coverage, planned-claim support and scientific evidence readiness; always returns `submissionReady=False` with human-review requirements. | Not the H2 confirmatory completion checker. Catches ValueError/KeyError/OSError per family, not every possible exception. Primary evidence error text remains in checks and prevents all-true coverage. Numerical claims are delegated to analysis helpers. Coverage and supported primary superiority/equivalence are both required for scientific readiness; an unfavorable primary result remains a scientific result. |
| `preserve_campaign.preserve(results, source, output, receipt)` | Requires a new output directory. Reads source ZIP provenance and verifies its listed file hashes, hashes provenance bytes, then verifies result ZIP inventory/duplicate names/member hashes and contained extraction paths. Requires passed run status with matching source-manifest hash, validates paired blocks and full index range for each protocol condition, checks archived environment source binding, reads saved analysis groups, copies protocol/analysis/status and both original archives, and writes a preservation receipt with hashes/source metadata/counts/analysis summary. | Source ZIP inventory checks only listed files, unlike exact result ZIP inventory. Does not recompute analysis or require `sourceClean=True`/confirmatory=True; records those values. Uses paper-block layout, not general H2 session preservation. No rollback: failures leave the new directory or copied files. Report/receipt publication is separate and direct. Inputs must remain unchanged during verification/copy; no filesystem snapshot or lock. |
| `confirmatory_v1.validate_plan(plan)` | Requires archived V1 plan values: 24 repeats, 10 epochs, batch 16, prefetch zero, tracing off, full steps, one workflow, alpha .05, equivalence margin .03, confirmatory true, exact frozen design and one condition. Requires OCT5K 1505 V2 samples, workers zero, four preprocess passes, one GPU, no reuse sweep, V1 1170, overlap 1003, image size 256, train split and four backend keys. | Archived validation only, not permission to launch V1 as the current experiment. Ordinary Python equality is used; no strict type validation. Checks backend key set, not mapping values/order. Missing nested structure can raise KeyError/TypeError rather than a protocol-specific message. |
| `confirmatory_v1.validate_training(training)` | Checks archived training configuration, first run's checksum policy version and enabled async compaction, 1003 prepopulated entries, then every reported backend's ten finite positive epoch walls and step totals of exactly 1505 samples for epochs 0-9. | Does not enforce exact backend set, independently validate per-step bytes/losses/model parity, verify CUDA or recompute throughput. Uses only first run. Empty backend mapping passes the backend loop; broader evidence validators supply additional checks. No return report: success is absence of error. |

## Audit Coverage Matrix

Primary requires at least 24 paired runs per analyzed group and 1505 samples;
expected reusable entries are 1430 for the current `confirmatory.DESIGN`, otherwise
1003. It reports raw superiority, mmap superiority and equivalence separately.
The primary endpoint is not silently changed to a longitudinal H2 endpoint.

| Family | Required Representation |
| --- | --- |
| `cross-lifecycle` | COCO and ImageNet, at least 12 blocks per qualifying identity group. |
| `cross-window` | OCT5K, COCO and ImageNet, at least 24. |
| `workers` | 0, 2, 4, 8; at least 10. |
| `reuse` | 0, 25, 50, 66.6445, 75, 90, 100%; at least 10. |
| `size` | 1505, 5000, 10000, 25000, 50000, 100000; at least 10, all records COCO/ImageNet. |
| `cost` | 1, 2, 4, 8 preprocessing passes; at least 10. |
| `gpu-scaling` | 1 and 2 GPUs; at least 10. |
| `durability` | At least 100 validated trials for each of five points and single/batch modes. |
| `concurrency` | At least 10 each for Aether/mmap x clients 1/2/4 x workers 0/2/4/8. |
| `evolution` | At least ten distinct hashed trial reports containing all six passing scenarios. |
| `workflow` | At least five workflow entries per block and ten blocks per identity group. |
| `dali` | COCO/DALI and ImageNet/DALI, at least 12 per environment/condition group. |

Representation sweeps count groups by protocol/environment/condition, then check
dimension values; they do not require one common environment across all points.
Evolution validates expected miss equality in each saved scenario, not fresh
artifact computation. Workflow and fault validators rely on upstream loaders for
other semantics. The gate is a suite-specific checklist, not a generic exhaustive
reproduction audit. Human review includes authors/ORCID, journal limits,
citations/manuscript, licensing, AI disclosure, independent reproduction and
author approval.

## Preservation and CLI Boundaries

Result ZIP names must equal checksum names plus `SHA256SUMS`; duplicate ZIP names
are rejected. Checksum text is parsed into a dictionary, not a signed trust root.
Source provenance file hashing binds result status/environment to that exact
manifest byte digest. Environment records are checked when present; this
function does not independently require a nonempty environment glob. Extra
protocol-external blocks are not separately rejected by the per-condition loop;
individual block validation remains delegated.

Preserved analysis is copied from `processed/analysis.json`; the receipt extracts
its protocol/condition/environment and Aether/mmap/raw fields without comparing
newly recomputed statistics. Record hashes are computed on input archives after
copying, not independently on the destination copies. The receipt may be outside
the preservation directory because its path is supplied by the caller.

Both CLI blocks are module-level with no separate declared main. The gate writes
JSON and exits zero only for scientific readiness, not journal submission
readiness. Preservation requires results/source/output/receipt paths. Archived
V1 has no CLI; its design specifies paired log-ratio TOST equivalence with a 90%
t interval strictly within .97-1.03, excluding V1 population and other setup from
V2 training timing. It is retained for old evidence validation.

## Verification

AST coverage matches all five functions. Offline contracts verify empty fault
coverage behavior, archived-plan rejection and failed source-preservation cleanup
boundaries. These fixtures are not real campaign evidence or proof of statistical
support, complete archive preservation, Java crash recovery or H2 completion.
