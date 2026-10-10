# Benchmark Source, Preprocessing, and Payload Functions

[Function index](FUNCTION-INDEX.md) | [Loading and prefetch](PYTHON-PIPELINE-FUNCTIONS.md) | [Longitudinal worker](LONGITUDINAL-WORKER-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This page covers 27 explicit functions for inputs, deterministic preparation,
artifact bytes and descriptive configuration. The larger module has 138 explicit
declarations; this is one partition, not a complete benchmark reference. Imported
vision and synthetic-input helpers remain separate implementations.

## Architecture and Data Contract

```text
source arguments -> source rows/raw buffers -> deterministic preprocessing
                 -> image + mask + sample ID -> normalization/codec tag
                 -> float16 image + binary uint8 mask -> header/body payload
                 -> optional zlib -> cache -> checked unpack -> float32 arrays
                 -> stack batches -> uncached training augmentation -> model
```

OCT5K input uses actual image/mask files and their paired declared digests. Synthetic
input uses generated or resized raw byte buffers and derives masks from intensity.
COCO/ImageNet routes to the vision workload. None of these functions starts a JVM,
opens a training cache, runs an optimizer or establishes GPU timing validity.

Deterministic preparation stops before random training augmentation. Artifact
packing quantizes images to float16 and masks to positive/nonpositive binary
uint8. Unpacking returns newly converted float32 arrays, not views that retain
zero-copy storage. Raw/reference and cached backends must be compared after this
same quantization boundary; equal preprocessing float32 inputs alone do not prove
equal cached training tensors.

## Source Loading Functions

| Function | Behavior and boundaries |
| --- | --- |
| `load_sources(args)` | Routes COCO/ImageNet to the imported vision loader and OCT5K to load_oct5k_sources. Synthetic mode without input_dir generates args.samples raw OCT buffers, seed/index identities and raw SHA-256 hashes. With input_dir, discovers matching files, takes the requested prefix, requires enough files, reads and coerces each to requested OCT dimensions, and uses path identity, stem ID and coerced-buffer hash. File-backed synthetic inputs are raw buffers here, not semantic image/mask pairs. Imported discovery/coercion owns ordering and resize behavior. |
| `load_oct5k_sources(args)` | Resolves and reads UTF-8 CSV, requires seven named columns, includes rows with absent/empty split or the selected split, and takes the first requested samples. Requires enough rows, nonempty stripped unique sample IDs, existing resolved image/mask files, lowercased declared digests and the correct paired source identity. Unless trust_manifest_hashes is set, hashes actual files and compares digests. Rejects duplicate selected content identities. Returns path-bearing normalized source dictionaries. Checks selected rows only; later unused rows may be invalid. Does not decode images, validate digest string format separately or enforce path containment. |
| `resolve_manifest_path(value, manifest_root)` | Resolves an absolute path directly or a relative path beneath the manifest's parent. Normalizes traversal/symlinks but does not reject escape from that parent or validate existence. H2 input binding adds its own separate containment policy; this helper is not that guard. |
| `file_sha256(path)` | Hashes a Path object's binary file in 1 MiB chunks. Does not convert strings to Path, verify ownership or stabilize a concurrently changing file. Its read-loop lambda returns the next chunk; no image decode or preprocessing is involved. |

Trusted manifest mode omits raw digest verification but still requires files and
valid declared paired identity. Preprocessing later opens the current bytes; a
changed file is not detected merely by trusting an old digest. Sample IDs/disease
labels are stripped; an empty split includes the row rather than excluding it.
Duplicate source identity rejection is stricter than the descriptive integrity
summary's text about reporting content-identical rows.

## Preparation Functions

| Function | Behavior and boundaries |
| --- | --- |
| `preprocess_sample(sample, args, np)` | Returns only the value from preprocess_sample_with_timing, discarding its counters. Does not create a separate computation or cache hit path. |
| `preprocess_sample_with_timing(sample, args, np)` | Delegates base preparation, mutates the returned value with artifactCodec, then optionally scales/offsets only image as float32. Adds that normalization wrapper time to preprocessMs, including the tiny no-op timing path. Mask is unaffected by scale/offset. Expects counters with preprocessMs and relies on validated args; it does not reject nonfinite parameters itself. |
| `_preprocess_sample_with_timing(sample, args, np)` | Routes vision/OCT5K kinds to specialized helpers. Other kinds read uint8 raw bytes into float32, reshape to source dimensions, nearest-resize and divide by 255, apply passes-minus-one denoising, z-score with zero standard deviation replaced by one, and make a mask at the denoised image's mean. Returns 1xresize x resize float32 arrays and preprocessing time with sourceLoadMs zero because raw bytes were already loaded. Buffer/reshape errors propagate. |
| `preprocess_oct5k_sample(sample, args, np)` | Requires Pillow; loads/copies grayscale image and validated-mode mask under file contexts, timing those operations as sourceLoadMs. Bilinear-resizes image, always applies radius-1 Gaussian blur, nearest-resizes labels, clips image to its 1st/99th percentiles with a unit range fallback, min-max normalizes, applies passes-minus-one wraparound denoise passes, and maps mask labels greater than zero to foreground. Returns float32 channel-first arrays and separate load/preprocess times. Does not preserve multiclass labels or verify source hashes during decode. |
| `validate_semantic_mask_mode(mode, path)` | Accepts 1, L, P, I and I;16 modes. Rejects RGB/RGBA/CMYK/HSV as visualizations and every other mode as unsupported. Checks mode only, not label meaning. Palette mode uses index values, not rendered palette colors. |
| `nearest_resize(image, target, np)` | Computes floor-scaled row/column indices with clipping and performs indexed sampling to a square target. No interpolation or Pillow call. Assumes meaningful nonempty source dimensions and target; direct calls lack argument validation. |
| `deterministic_denoise_pass(image, np)` | Returns float32 weighted center 0.5 plus four neighboring rolls at 0.125 each. np.roll wraps edges, so opposite borders influence one another; it is not zero/reflection padding or the Pillow Gaussian blur. Allocates rolled arrays/result and does not mutate the input. |
| `stack_values(values, np)` | Stacks ordered image and mask arrays and casts both to float32. Ignores IDs/codec tags, does not augment or transfer to GPU. Empty/mismatched shapes raise through NumPy. |

OCT foreground includes any positive semantic label, so multiple nonzero classes
collapse to one. Synthetic threshold masks are derived data, not expert annotations.
Both pipelines may be useful workload fixtures, but neither function alone validates
clinical segmentation ground truth. Additional normalization changes the image
after mask construction and must enter artifact identity.

## Payload and Checksum Functions

| Function | Behavior and boundaries |
| --- | --- |
| `pack_payload(sample)` | Accepts codec none or zlib, makes compact sorted JSON of ID/shapes, fixed persisted dtypes, encoding-version tag and codec, then appends native NumPy float16 image and positive-binary uint8 mask bytes, optionally compressed together. Prefixes a four-byte little-endian header length. No checksum, raw-digest verification, shape bound, finite-value check or explicit tensor-byte endianness normalization. Unsupported codec raises before encoding. |
| `unpack_payload(payload, np)` | Requires length prefix/header bounds, parses UTF-8 JSON, multiplies shape counts, uses declared dtypes (legacy defaults float32), and handles none/zlib only. Zlib decompression is bounded to declared byte total plus one; it requires EOF/no trailing stream data and exact resulting body length. Splits image/mask bytes, converts each to newly allocated float32 and reshapes. Does not require the encoding-version tag or the current fixed dtype names, validate schema/positive dimensions explicitly, authenticate contents or impose an independent maximum artifact size. JSON/dtype/zlib/reshape failures propagate. |
| `artifact_to_tensor_sample(sample, np)` | Pack-then-unpack canonicalization. Applies lossy image/mask encoding even without a cache, returning float32 arrays derived from persisted precision. Not a zero-copy tensor constructor. |
| `element_count(shape)` | Multiplies int-converted dimensions starting at one. Empty shape returns one; zero gives zero, negative/fractional/string dimensions are not separately rejected. Downstream slicing/reshape may reject malformed shapes, but this is not a tensor-schema validator. |
| `dataset_checksums(samples)` | Iterates samples in order, separately hashes each sample ID followed by image bytes and mask bytes, then hashes the two binary digest values together. Does not include shape/dtype metadata or separators between ID and bytes; differently shaped arrays with the same raw bytes can match. Not equivalent to the MONAI tensor_digest contract. |
| `assert_equivalent_inputs(reference, checksums)` | Recomputes dataset_checksums and requires exact dictionary equality, raising on mismatch. Consumes iterables; can trigger lazy preprocessing. Does not compare model outcomes, metadata identity or durability. |
| `dataset_integrity_summary(args, sources)` | Reports dataset/manifest hash/split, diseases, selected count, distinct identities and IDs/collisions. passed checks sample count and unique sample IDs only; source identity collisions can coexist with passed when called directly. manifestHashesVerified is inferred from manifest presence and the trust flag, not a fresh per-file verification. No backend/device correctness proof. |

The compressed byte limit is derived from untrusted shape/dtype fields. Exact
length and compressed-stream checks are structural safeguards, not a fixed memory
quota or cryptographic authenticity. Arbitrary JSON/dtypes are not silently
normalized to the current schema. A caller can unpack legacy float32 bytes even
when the encoding tag is absent or different.

## Identity, Scheduling and Description Functions

| Function | Behavior and boundaries |
| --- | --- |
| `batch_plan(args)` | Lazily yields contiguous index batches for one pass, keeping the short final batch. Does not shuffle, repeat epochs or enforce measured_steps. Direct invalid step arguments fail through range; normal parse_args validates them. |
| `effective_measured_steps(args)` | Returns nonzero explicit measured_steps, otherwise ceiling sample/batch count times epochs with a minimum of one. It is a schedule count, not a measured-duration estimate. Direct negative explicit values are returned; argument validation is separate. |
| `deterministic_parameters(args)` | Returns the specialized descriptor plus pipeline version, artifact codec and normalization scale/offset. These wrapper fields override same-named base entries. Produces a dictionary, not a hash or an independent check that every code change updates identity. |
| `_deterministic_parameters(args)` | Routes vision to imported parameters. OCT describes dataset/resize/decode/interpolation/percentile/blur/normalization/dtypes/encoding/passes/version. Synthetic describes dimensions, resize, z-score/mask/payload formats, passes and implementation version. Contains descriptive strings, not serialized executable transforms; actual denoise behavior must still be read in preparation code. |
| `configuration(args)` | Serializes selected argument fields plus derived step count, backend names and descriptive parallelism. Class count is null for segmentation; Java-only port/namespace are null for Python engine. Does not mutate args, execute work, revalidate limits or include every parse option (for example output path, run count and invocation are elsewhere). Claims such as worker startup inclusion are protocol descriptions, not probes. |
| `storage_locations(args)` | Describes raw path, generic temporary-per-run cache locations, cwd filesystem label and platform. Does not resolve configured cache paths or prove actual backend placements; explicit persistent directories can differ from these descriptive labels. COCO/ImageNet rawPath here does not use their manifest like OCT5K does. |
| `filesystem_name(path)` | Returns posix immediately on non-Windows, not the actual filesystem type. Windows runs fsutil volumeinfo without timeout and extracts an English File System Name line; catches exceptions and returns unknown otherwise. Does not require a successful exit code or support localized output explicitly. |
| `dataset_version(args)` | OCT label includes split/count, first 12 hex characters of current manifest hash (or missing-manifest), resize and OCT transform version. Other dataset kinds use input source/dimensions/resize/passes/seed. A readable label, not complete content/transform identity: codec/normalization are absent, and vision kinds use the non-OCT labeling branch. |

## Verification and Remaining Coverage

[Data documentation contracts](../../scripts/tests/test_benchmark_data_documented_contracts.py)
exercise real tiny image/CSV inputs, trust-mode limits, semantic mask modes,
denoise/normalization, quantized and legacy payloads, corruption/stream boundaries,
checksum omissions and descriptive metadata. [Storage tests](../../clients/python/tests/test_paper_storage.py)
already check compressed codec parity and cache behavior. These require no GPU
or live Java daemon. Function entries are checked against a selected AST inventory.

The [prefetch reference](PYTHON-PIPELINE-FUNCTIONS.md) separately covers two routing
declarations. The [function index](FUNCTION-INDEX.md#main-benchmark-workload) links
all partitions, jointly covering the module's 138 explicit declarations. This
page alone is partial, and complete module entries do not complete the repository
overview or establish runtime correctness.
