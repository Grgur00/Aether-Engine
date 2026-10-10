# Bulk Verification Campaign Functions

[Function index](FUNCTION-INDEX.md) | [Bulk recording drivers](BULK-DIAGNOSTIC-DRIVERS.md) | [JFR analysis](JFR-ANALYSIS-FUNCTIONS.md)

Source: [profile_bulk_verification.py](../../scripts/profile_bulk_verification.py).
All **8 explicit declarations**, including the nested stats helper, are covered.
This is an exploratory population diagnostic, not an H2 confirmatory campaign.
It does not train, record JFR or automatically start a follow-up optimization.

## Paired Campaign Architecture

```text
separate frozen baseline checkout -> inventory/hash/shared-input checks
frozen V0 manifest -> common reference preflight
alternating baseline/candidate order per block
  -> fresh store + isolated checkout-specific subprocess and PYTHONPATH
  -> population/readback receipt -> arm-specific correctness validation
  -> result receipt + partial summary -> safe successful scratch removal
all arms completed -> diagnostic completion (not necessarily performance pass)
```

Original mode accepts three to five pairs and compares immediate-plus-reopen
baseline verification with one-inventory-pass candidate verification. Streaming
mode requires five pairs and compares reader-based one-pass verification with
streaming-v1. Each arm starts a fresh process/store; this is not a persistent
daemon through dataset revisions. Common reference preflight warms source inputs;
OS cache state is not controlled.

## Frozen Source and Correctness Functions

| Function | Behavior and boundaries |
| --- | --- |
| `baseline_identity(root)` | Resolves root and rejects the current ROOT as baseline. Reads artifact-provenance.json files, reconstructs the packaged source inventory using ROOT_FILES/DIRECTORIES/EXCLUDED and Python-client suffix rules, requires exact membership and checks each listed resolved path stays inside root with matching SHA-256. Also requires baseline hashes for bulk_population.py, monai_comparison.py and current non-excluded client Python sources to match candidate input/encoding code. Returns provenance hash and source hash map. |
| `validate_report(report, arm, reference)` | Requires 1200 samples and preprocessing calls, zero training requests, model=None, matching tensor digest and passed ordinary restart validation. Requires offline bulk, batch/lookup 16, no tracing and 32 MiB target. Candidate requires bulk-deferred-inventory-v2 and exact one-call/table/byte verification counters. Other arms require positive baseline verificationNs and positive per-table verificationOpenAndRead. Population and preprocessing times must be finite and nonnegative. |
| `validate_streaming_report(report, arm, reference)` | First applies candidate one-pass validation to both arms. Requires finite positive manifest.inventoryVerificationNs. Baseline must have no streamingVerification field value. Other arms require streaming-v1, matching table count, 1200 entries and positive logical value bytes/read bytes/elapsed metrics. Does not independently parse tables or rerun verification. |

Source identity covers the packaging inventory, not every file on disk or every
compiled dependency. Excluded/generated files do not cause inventory drift. Shared
preprocessing/client checks compare bytes, not only CSV membership. The baseline
is revalidated before every arm. Candidate source changes outside shared-input
files are not independently frozen/rechecked by this helper; use campaign/build
provenance and source receipts to establish full implementation identity.

Report gates consume supplied fields, not independent observations. Exact
verification dict equality rejects extra keys as well as changed counts. Candidate
bytesFullyVerified equals the sum of finish-record byte fields; table count is not
separately compared to finish-list length here. Baseline all(...) is vacuously true
for an empty finish list if its aggregate timing is positive. Unique-artifact count,
all secondary times, complete phase sums and GPU/model correctness are not checked.
Missing required fields fail by exception. arm is not an enum validator: unknown
names follow the non-candidate branch in validate_report and non-baseline branch
in validate_streaming_report; the runner supplies only the two expected labels.

Streaming metric positives are not independently checked for finiteness or exact
agreement with inventory byte count/manifest elapsed; the authoritative manifest
timing receives the explicit finite check. A zero-table receipt is not separately
rejected by these functions. These limitations are not permission to accept an
unverified campaign; inspect the producer's correctness evidence too.

## Summary and Decision Functions

| Function | Behavior and boundaries |
| --- | --- |
| `summarize(rows, repetitions)` | Groups baseline/candidate rows into population and population-minus-preprocessing statistics plus verification receipts. Complete requires both arm labels in every expected block and total row count 2*repetitions. If both arms exist, improvement is baseline median population minus candidate median population; performanceGatePassed requires complete and >=750 ms. Returns the diagnostic role and next-review instruction, not a new experiment launch. |
| `summarize.stats(values)` | Returns n, median, mean, min, max and the original values list. Assumes nonempty numeric input; no confidence interval, variance, outlier removal, weighting or pairing. |
| `summarize_streaming(rows, repetitions)` | Starts with summarize, removes its threshold/improvement fields, and adds each arm's authoritative verification times in milliseconds and median. Computes candidate median / baseline median; overwrites the gate with complete and ratio<=0.75, labels the primary endpoint, and retains prior750msGate='failed; unchanged'. |

Pairing controls workload order and block membership, but these primary effects
are **differences or ratios of arm medians**, not medians of paired differences or
ratios. Population-minus-preprocessing is a residual that still includes feeding,
encoding and storage; it can be negative and is not isolated storage CPU time.
Summary functions assume validation occurred upstream and do not check finite
inputs, valid repetition counts or report identity themselves.

The completeness test uses arm-label sets plus total row count. It does not
independently certify protocol identity, fresh stores or absence of retries; the
runner and receipts establish those boundaries. A missing arm leaves improvement
or ratio null and the gate false. Streaming has no zero-baseline denominator guard.
Direct calls to summarize_streaming do not enforce five pairs; run does. There are
no paired confidence intervals or confirmatory significance claims here.

## Controller and CLI Functions

| Function | Behavior and boundaries |
| --- | --- |
| `run(output, baseline_root, repetitions=3, scratch_root=None, *, streaming=False)` | Rejects streaming repetitions other than five and other counts outside 3/4/5. Validates baseline, frozen version counts/seed, and computes V0 canonical reference at resize 256. Constructs alternating arm order and immutable campaign metadata, launches fresh checkout-specific workers, validates/saves results and writes partial summaries. Copies side diagnostics in finally, removes only successful scratch stores after parent/prefix checks, and writes diagnostic completion after all arms. |
| `main()` | Parses required output/baseline-root, optional scratch-root, repetitions choices 3/4/5 (default 3) and streaming flag, then calls run. Selecting streaming alone with default repetitions fails the five-pair gate; callers must explicitly choose five. No argv parameter, GPU settings, training epochs or automatic follow-up action. |

Frozen membership requires counts 1200/1260/1323/1389/1458 and seed 20260926.
Only V0 is measured. Alternation gives baseline-first in even blocks and
candidate-first in odd blocks; odd repetition counts have one extra baseline-first
block, not perfectly balanced ordering. Both arms use the same absolute manifest,
reference hash, 32 MiB target, batch/lookup 16 and no trace/JFR/layout regression.

Workers use the current Python executable but their own checkout's
profile_population.py, cwd and replacement PYTHONPATH containing scripts and
clients/python. Other environment variables are inherited. Separate source roots
avoid importing candidate adapters into baseline workers. No subprocess timeout
is set here. Worker stdout/stderr go to each arm's worker.log.

Each arm requires available scratch capacity, a new output location and a new
bulk-verification-prefixed scratch store. Existing arm directories fail; there is
no completed-arm resume. On any worker/validation failure, finally copies top-level
store.* files, leaving the actual store and diagnostics for inspection. A copying
failure can replace the earlier exception. After success, scratch cleanup checks
parent and prefix before recursive removal; it does not delete failed evidence.

completion.json status='passed' means the diagnostic execution and correctness
gates finished, **not** that performanceGatePassed is true. The summary can record
a failed performance gate alongside successful completion. No JFR or Phase 2 is
started automatically; nextGate is explanatory metadata. Secondary warm-read and
incremental behavior require separate measurements before longitudinal use.

## Coverage and Evidence

AST checks cover all eight qualified declarations. Existing
[verification tests](../../scripts/tests/test_bulk_verification.py) check exact
verification counters, workload drift, the two threshold endpoints, baseline file
drift, allowed repetition counts and isolated worker/failure retention behavior.
Focused documented-contract tests examine residual timing, receipt limits and
direct summary assumptions. These fixtures do not replace a real paired campaign.

This file has **8/8 declarations covered (100%; 0% remaining)**. Other research
drivers and repository subsystems remain in the original documentation scope.
