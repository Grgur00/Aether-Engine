# Dataset Preparation and Manifest Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark sources](BENCHMARK-DATA-FUNCTIONS.md) | [Version manifests](RESEARCH-MANIFEST-FUNCTIONS.md)

Sources: [fetch_coco.py](../../scripts/fetch_coco.py),
[prepare_vision.py](../../scripts/prepare_vision.py),
[prepare_evolution.py](../../scripts/prepare_evolution.py), and
[validate_manifests.py](../../scripts/validate_manifests.py).
This reference covers **8 explicit functions across four complete files**.
File-qualified names distinguish the two preparation functions and CLI entries.

## Architecture

```text
COCO URLs -> local ZIP archives -> images + instance annotations
local ImageNet class directories or COCO images/annotations
    -> hashed, seeded-permutation source CSV + class metadata
source CSV -> seeded V1 membership + surviving/new V2 membership
    -> two CSV files + evolution receipt
dataset configuration -> manifest existence, IDs, paths, counts, overlap checks
workload loader -> its own content/identity validation and preprocessing
```

These scripts prepare or inspect inputs. They do not train models, publish cache
artifacts or certify H2's frozen protocol. COCO labels describe object-category
presence, not detection boxes or segmentation masks. ImageNet labels derive from
sorted local directory names, not an externally reconciled canonical class map.
Acquisition/licensing remains the operator's responsibility; manifests reference
local files rather than redistribute images.

## Acquisition

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `fetch_coco.download(url, destination)` | Reuses an existing nonempty destination file. Otherwise removes a stale sibling `.part`, streams `urlopen` to that partial path and replaces the destination after transfer. | Requires a `Path` and an existing parent directory. No expected digest, length check, explicit timeout, retry or HTTP range resume. Nonempty is not proof of a complete archive. Failure leaves a partial file; a later attempt removes it. |
| `fetch_coco.extract(archive, destination, required)` | Reuses an existing required directory. Otherwise resolves every ZIP member destination and rejects paths outside the resolved output root before `extractall`. Requires the expected directory after extraction. | Existing directories are not inventoried or checked for completeness. Extraction is not transactional; errors can leave partial directories that a later invocation skips. Path containment is a traversal guard, not a comprehensive hostile-archive resource/symlink policy. No size/quota preflight. |
| `fetch_coco.main()` | Parses output, one or more supported 2017 splits and optional annotation suppression. Creates archive/image directories; downloads/extracts each split and, by default, train/validation annotations. Prints a train manifest command when applicable. | Default output is `/kaggle/working/coco`; default split is `train2017`. `test2017` does not gain test labels from the train/validation annotation archive. Errors propagate and previous downloads/extractions remain. Does not create a manifest itself. |

## Hashed Source Manifest

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `prepare_vision.prepare(kind, root, output, annotations, split, seed)` | Resolves root/output and requires an image directory. ImageNet lists sorted class directories and recursive JPEG/JPG/PNG paths, assigning integer labels by class order. COCO reads instance annotations, sorts categories by ID, gathers unique category labels per image and lists images by ID. Shuffles the complete row list with a private seeded RNG, writes IDs/absolute paths/file hashes/JSON labels/split to a temporary CSV, then replaces the output and writes metadata with class mapping, counts and manifest/annotation hashes. | Refuses existing CSV or metadata, but existence checks are not exclusive concurrent publication. CSV replacement and metadata writing are separate: metadata failure can leave a CSV. Does not decode images or validate dimensions. ImageNet extension matching is not a file-type check. COCO filenames are joined to root without a containment guard; duplicate IDs/categories and malformed annotations are not explicitly validated. Any non-ImageNet `kind` follows the COCO branch; CLI constrains it. |
| `prepare_vision.main()` | Parses `coco`/`imagenet`, image root, annotations, split, seed and output; delegates to `prepare`. | Default split `train`, seed `20260904`. Required arguments and dataset choices are argparse checks, not license/content certification. No subset-size parameter: later prefix subsets use the fixed seeded full-list order. |

CSV columns are `sample_id`, `image_path`, `image_sha256`, `labels`, `split`.
ImageNet IDs are relative paths; COCO IDs are string image IDs. Metadata goes to
`output.with_suffix('.metadata.json')`. The CSV temporary is cleaned in `finally`;
the preparation function does not roll back a successfully replaced CSV if a
later receipt step fails. Hashing authenticates bytes only relative to the
recorded digest, not image provenance or semantic correctness.

## Membership Evolution

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `prepare_evolution.prepare(source, output, v1_size, v2_size, reusable, split, seed)` | Reads split-filtered rows via `manifest_rows`; validates positive sizes, feasible overlap, sufficient union rows and unique nonempty IDs. Sorts by ID, privately shuffles, selects V1, samples surviving V1 rows, adds disjoint new rows and shuffles V2. Refuses existing `v1.csv`, `v2.csv`, `evolution.json`; writes both CSVs with image/mask paths made absolute relative to the source CSV, then records cardinalities, seed and three manifest hashes. | Defaults 1170/1505 with 1003 shared IDs and seed `20260904`. Membership overlap is not artifact-key reuse when content/transforms change. Does not check file existence or recorded content hashes. Writes are direct and separate, not atomic as a pair; failures leave partial output. Other existing output-directory files are allowed. No resume/rollback. |

The module-level CLI parses `--manifest`, `--output`, size/overlap/split/seed flags
and calls this function; it has no separately declared `main`. It copies rows,
not image bytes. Source split filtering accepts rows whose split is absent/empty.
The evolution receipt records `initialMissing = v2_size - reusable` and
`removedFromV1 = v1_size - reusable`; these are membership counts, not measured
preprocessing or cache misses.

## Preflight Validation

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `validate_manifests.rows_for(path, split)` | Requires CSV headers `sample_id`, `image_path`, `image_sha256`; selects rows with matching or absent/empty split. Requires at least one row, unique nonempty IDs and every image path to be a file. Returns the selected ID set. | Requires a `Path`. Does not validate hash syntax or recompute hashes, parse labels, check masks or decode images. Relative image paths resolve against process working directory, not CSV location. Whitespace-only IDs are not rejected. |
| `validate_manifests.main(argv)` | Reads dataset configuration, chooses all names or a comma-separated subset, and validates each V1/V2 manifest through `rows_for`. Without `expectedReusable`, requires at least the configured counts; with it, requires exact counts and exact ID-set intersection. Prints JSON with available/required rows and overlap. | Dataset subset values are not stripped or deduplicated. Unknown names/missing config fields propagate errors. Counts use all selected rows, not a sampled prefix. ID overlap does not prove equal source hashes or transform identities; this is not authoritative cache reuse certification. No output receipt file or changes to inputs. |

## Verification and Reading Order

Read the acquisition functions before running a network download; then source
manifest preparation, membership selection and preflight checks. Follow the
benchmark source guide for loader-level hash handling and the longitudinal
manifest guide for the separate H2/version sequence.

The documentation test compares all file-qualified AST function declarations
against this table. Existing vision, evidence and remote-preflight tests exercise
local fixtures; additional acquisition contract tests use only temporary ZIPs
and mocked URL streams. Passing these tests does not establish network download
availability, real dataset completeness/licensing or GPU training correctness.
