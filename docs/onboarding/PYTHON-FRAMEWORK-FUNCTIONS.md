# Python PyTorch and MONAI Integration Functions

[Function index](FUNCTION-INDEX.md) | [Dataset functions](PYTHON-DATASET-FUNCTIONS.md) | [Client lifecycle](PYTHON-LIFECYCLE-FUNCTIONS.md)

This reference covers the explicit functions and inherited adapter contracts in
[torch/dataset.py](../../clients/python/aether_ml/torch/dataset.py),
[torch/loader.py](../../clients/python/aether_ml/torch/loader.py),
[torch/worker.py](../../clients/python/aether_ml/torch/worker.py),
[monai/dataset.py](../../clients/python/aether_ml/monai/dataset.py), and
[monai/identity.py](../../clients/python/aether_ml/monai/identity.py).
Package exports are defined in
[torch/__init__.py](../../clients/python/aether_ml/torch/__init__.py) and
[monai/__init__.py](../../clients/python/aether_ml/monai/__init__.py).

## Architecture and Import Boundaries

```text
PyTorch DataLoader sampler produces indices
  -> dataset.__getitems__(indices) for compatible batch-fetch paths
  -> AetherDataset.get_batch
  -> deterministic cache lookup / missing work / publication / decode
  -> per-sample merge and random augmentation
  -> framework collation and optional pinning
  -> caller's training loop and device transfer
```

The adapters do not implement a model, optimizer, epoch loop, loss, accelerator
transfer, framework collator, or the H2 timing endpoint. Their role is to expose
the deterministic cache boundary to normal framework data fetching. These are
active-worktree convenience wrappers; the frozen research runner uses a separate
verified worker and must not be inferred from these class names.

Importing the aether_ml.torch helpers does not import Torch immediately; the loader
and worker functions do so when called. aether_ml.monai deliberately does not import
MONAI: a Compose or other transform is accepted as an ordinary callable. Neither
module's import proves the optional framework is installed or that every transform
output is supported by the selected artifact codec.

## PyTorch Dataset and Loader Functions

`AetherTorchDataset` subclasses AetherDataset without adding methods or state.
Although its docstring calls it an alias, it is a distinct subclass. Construction,
identity validation, caching, `__getitem__`, `__getitems__`, population, and cleanup
are inherited; refer to the complete [dataset reference](PYTHON-DATASET-FUNCTIONS.md).

| Function | Behavior and failure boundary |
| --- | --- |
| `AetherDataLoader.__new__(cls, dataset, *args, **kwargs)` | Lazy imports torch.utils.data.DataLoader and constructs it with the supplied arguments unchanged. Missing Torch is re-raised with an Aether-specific ImportError message. Returns the actual framework DataLoader, not an instance with a separate AetherDataLoader lifecycle. |
| `aether_worker_init(worker_id)` | Imports get_worker_info. If running in a worker and its dataset has a cache attribute, calls that cache's _client_for_process. Otherwise does nothing. Missing Torch is ImportError. The worker_id argument is not used for keys, seeds, client selection, or namespaces. |

The facade adds no default worker count, prefetch settings, sampler, collation,
worker_init_fn, persistent_workers, pin_memory, or spawn method. Those are whatever
the caller passes and whatever the installed framework supports. It also accepts
a non-Aether dataset; cache behavior exists only when that dataset exposes the
corresponding fetch hooks.

For compatible PyTorch 2 map-style batch fetching, inherited `__getitems__` enables
one get_batch call for an index list, before framework collation. This is not a
promise of one socket exchange: the cache chunks requests over 4,096 entries, and
publication may be an additional request. Repeated indices remain repeated samples.

### Worker Ownership

aether_worker_init is optional and must be supplied explicitly as worker_init_fn.
Normal cache fetching already calls _client_for_process lazily. Eager worker init
can perform connection/capability setup earlier, but it is not a startup timer or
an epoch preflight gate. It does not seed transforms or require worker_id to match
the process ID. A dataset wrapper such as a framework subset may hide the cache
attribute, so the helper does not recursively discover its underlying dataset.

The hasattr check is shallow: a dataset with cache=None or a cache lacking the
private method can fail. It does not catch client creation failures or translate
them to WorkerLifecycleError. It can also create a connection for a disabled cache
when explicitly installed, because it calls the private client accessor directly.

Use a picklable client factory for process-local connections. Passing a live client
object does not become safe merely by wrapping it in AetherTorchDataset or using
this helper. Model/data callbacks and source dataset also need to satisfy the
chosen worker-start method. The adapters add no process-finalizer cleanup or
cross-worker shared-memory ownership scheme.

## MONAI Dictionary Dataset Function

| Function | Behavior |
| --- | --- |
| `AetherPersistentDataset.__init__(data, deterministic_transform, ...)` | Materializes the supplied iterable into a list, forwards it and the explicit transform/identity/random callbacks to AetherDataset, and defaults codec to TensorDictCodec. Inherits fetching, population, metrics, and cleanup; defines no other methods. |

Materialization consumes a generator once and retains its elements; it is not a
lazy/out-of-core source. The list copies references, not deep copies of dictionaries
or images. Infinite input will not finish construction. Errors during materializing
data occur before constructing the parent cache.

This wrapper is not MONAI PersistentDataset with a different cache_dir. It does not
inherit MONAI's class, write MONAI cache files, partition a Compose automatically,
walk transforms to detect randomizable operations, or implement LMDB storage. The
caller provides the deterministic and random sides of the boundary separately.
It also does not expose the parent's cache_selector/merge_cached_artifact options.

The inherited parent always wraps the transform in _SelectedTransform; an explicit
transform_identity remains necessary in the current implementation. MONAI module
membership alone does not avoid that parent-wrapper fingerprint restriction.

The default TensorDictCodec requires string-keyed dictionaries whose values can
be converted to supported contiguous arrays/tensors. Metadata such as nested
dictionaries, arbitrary objects, or full MONAI MetaTensor metadata is not promised
to round-trip. The codec detaches tensors and stores CPU array bytes; it does not
preserve arbitrary subclass metadata, gradient state, or original GPU storage.
Choose a suitable explicit codec/schema when the artifact contract differs.

## MONAI Identity Functions

| Function | Input meaning and output |
| --- | --- |
| `monai_dict_identity(item, index, key)` | Requires key in the item (default id), converts that value to str, then returns hashed_identity of the string. Missing field is KeyError. index is accepted for dataset-callback compatibility but unused. |
| `dicom_study_identity(item, index)` | Reads truthy StudyInstanceUID, SeriesInstanceUID, and SOPInstanceUID values in that fixed order, stringifies them, joins with colon, and hashes the descriptor. No truthy UID raises KeyError. index is unused. |
| `nifti_file_identity(path, index)` | Converts to Path and hashes a descriptor of resolved absolute pathname, file size, and nanosecond modification time from stat. index is unused. Filesystem errors propagate. No image decoding or content digest is performed. |

All three helpers return a SHA-256 hexadecimal identity of a **descriptor** using
hashed_identity. That does not make the descriptor equivalent to a content hash.
They do not read masks, labels, referenced image contents, preprocessing settings,
or package versions unless the caller's chosen field/descriptor includes them.

monai_dict_identity accepts values such as None or numbers by stringifying them;
different original types can stringify identically. An unchanged id after editing
an image can therefore reuse an obsolete artifact. dicom_study_identity filters
missing/false values and does not label fields in its joined descriptor. The same
single string in a different UID field can yield the same identity; the helper is
not a DICOM syntax validator or a guarantee of study-versus-instance granularity.

nifti_file_identity is path/size/mtime identity, not file-content identity. Moving
the file changes identity even with identical bytes; a same-size modification
with preserved mtime can keep identity. Stat reads are not an atomic filesystem
snapshot, and the descriptor is based on the resolved path but stat is invoked
through the original Path. Concurrent file/symlink changes are outside its contract.
It is named for NIfTI use but does not validate the file format or even require a
regular file instead of another stat-able object.

For medical-image artifact reuse, explicitly decide whether identity refers to an
image, an image/mask pair, a study, or a preprocessing input bundle. Stable names
alone are insufficient when content can change under those names. Hashing a weak
descriptor does not strengthen the descriptor's invalidation guarantees.

## Tests and Source-Reading Exercises

[test_aether_ml.py](../../clients/python/tests/test_aether_ml.py) exercises the
batch hook, augmentation after cache retrieval, a DataLoader batch with num_workers=0,
and optional worker counts 0/1/2/4/8 using MemoryClient factories. Those fixtures
verify sample preservation and wrapper routing, not production socket inheritance,
an actual MONAI transform graph, Java durability, or GPU performance.

[Documented-contract tests](../../scripts/tests/test_ml_documented_contracts.py)
exercise descriptor-based identities and the optional worker-init branch with local
fixtures. Follow `__getitems__` through get_batch to the codec and explain which bytes
are independent copies by the time collation starts. Then compare an edited image
with unchanged id versus an explicitly updated content identity: only the latter
reliably invalidates identity-aware reuse by contract.
