# Research Dataset Version Manifest Functions

[Function index](FUNCTION-INDEX.md) | [Research tooling](EXPERIMENTS-AND-PROFILING.md) | [Stage and service ownership](RESEARCH-LIFECYCLE-FUNCTIONS.md)

Source: [longitudinal_manifests.py](../../scripts/longitudinal_manifests.py).
This reference covers all four explicit functions and the module-level command.
The helper freezes ordered CSV membership for additive dataset revisions. It does
not preprocess images, train a model, populate storage, or authenticate an archive.
The H2 harness uses these helpers but separately validates its frozen protocol and
inputs; this file alone is not the complete H2 correctness gate.

## Architecture and Identity

```text
source CSV -> read rows -> retain training split
           -> validate sizes, unique IDs, declared content identity
           -> sort by source identity -> seeded shuffle
           -> V0 prefix -> V1 longer prefix -> ... -> V4 longer prefix
           -> write version CSVs -> hash exact CSV bytes -> manifests.json

verify: read receipt -> compare version CSV hashes -> check ordered prefixes
input preflight: separately read and hash actual images/masks and prepared tensors
```

The standard sizes are `1200, 1260, 1323, 1389, 1458`. New admissions are
`1200, 60, 63, 66, 69`. Each later version retains every previous row in the
same position; these are additions, not a randomized replacement workload.
Reuse is the previous size divided by the new size, about 95.2-95.3% per update.
This membership reuse does not by itself prove a backend hit: transform identity,
stored values, corruption handling and measured preprocessing still matter.

The declared source identity is SHA-256 of the UTF-8 string
`image_sha256:mask_sha256`. Paths, sample IDs and other CSV columns do not enter
that hash, but their complete row dictionaries must remain equal in retained
prefixes. The helper trusts the declared image/mask digests; it does not read
their files or require digest strings to have a specific hexadecimal format.

## Function Reference

| Function | Behavior and boundaries |
| --- | --- |
| `read_rows(path)` | Opens the CSV as UTF-8 with `newline=""`, returns `DictReader.fieldnames` and all row dictionaries in memory. Preserves input column order. Does not validate schema, duplicate headers, surplus cells or required values. Empty input can return `None` headers and no rows; missing columns surface later as ordinary lookup failures. |
| `validate_versions(versions, counts)` | Iterates both sequences with `zip(..., strict=True)`, so different sequence lengths raise. Each version must have the requested row count, unique `source_identity` values, and an unchanged full-row ordered prefix of the prior version. A nonempty prior version must be followed by a larger version. Does not rehash identities, require unique sample IDs or validate counts independently. Empty sequences pass; a first empty version is permitted by this direct helper. The previous list is retained by reference during the call, not copied. |
| `prepare(source, output, counts=COUNTS, seed=20260926)` | Reads source rows, retaining rows whose split is exactly `train`; a missing split column defaults to training. Rejects empty, unsorted, repeated, nonpositive-first or oversized counts. Requires nonempty, unique source identities and sample IDs across the retained training pool, then checks every retained declared identity against its image/mask digest strings, including unused rows. Sorts by identity, uses a local seeded RNG to shuffle, selects prefixes and validates them. Creates a new output directory, writes UTF-8 CSVs with LF line endings and original headers, hashes their exact bytes and writes the receipt. Returns that same receipt dictionary. No raw image reads, preprocessing, global RNG mutation, rollback, directory transaction or fsync. |
| `verify(directory)` | Reads `manifests.json`, derives one `vN.csv` path per recorded count, compares all CSV byte hashes with the receipt list, then checks counts, identity uniqueness and prefix stability using `validate_versions`. Returns the parsed receipt and ordered `Path` list. Does not compare the current original source CSV, rederive content identities, check receipt schema/seed/newCounts/unused, require an expected caller-provided receipt hash, or reject extra directory files. A missing/invalid receipt or CSV raises. |

## Ordering and Output Ownership

Sorting before shuffling makes selected membership independent of source-row
ordering for the same unique identities, seed, counts and Python behavior.
The source receipt hash still changes if source bytes or row order change.
Reordering CSV headers also changes emitted version bytes, even when the row
dictionaries compare equal. A different seed may change membership/order;
determinism is not a cross-version RNG compatibility promise.

`prepare` creates the directory only after source validation. An existing output
directory, even empty, raises `FileExistsError`; its contents are not overwritten.
Once creation succeeds, a later CSV/write/hash/receipt error can leave a partial
directory. The caller must use a new output or explicitly manage that failed
output. CSV publication and receipt replacement are not one atomic operation.

The receipt records schema, seed, exact original source CSV hash, retained training
pool size, counts, new counts, zero removed/modified, unused count and version CSV
hashes. These are generated facts, not authenticated statements. `verify` trusts
the receipt itself: rewriting a CSV and its receipt hash can pass when structural
conditions still hold. External freeze/evidence checks must bind the receipt.

## Command and Timing

The module-level command requires `--source` and `--output`; it uses the standard
counts and seed, not configurable CLI alternatives. Its timer starts immediately
before `prepare` and ends before writing `generation-timing.json` and printing
the receipt. It includes CSV parsing, source/version CSV hashing and manifest
publication, but not image/mask hashing, tensor preprocessing or training.
The timing receipt's phrase about separate source hashing/preflight must not be
read as excluding the original CSV hash performed inside `prepare`.

On failure, exceptions propagate. An error writing the timing file or printing
can occur after the version manifests and their receipt are already complete.
`generation-timing.json` is not read by `verify`.

## Verification Scope

[Existing longitudinal tests](../../scripts/tests/test_longitudinal_comparison.py)
cover standard generation, seeds, additions, unused membership and duplicate
rejection. [Manifest documentation contracts](../../scripts/tests/test_manifest_documented_contracts.py)
add source-order independence, split filtering, direct-helper limitations,
receipt trust, partial output and CLI timing boundaries using disposable CSVs.
AST coverage checks match the four reference entries to explicit declarations.
These establish selected local contracts, not real image correctness, backend
reuse, CUDA execution or scientific performance.
