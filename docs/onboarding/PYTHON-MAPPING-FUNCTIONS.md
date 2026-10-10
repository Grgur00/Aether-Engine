# Python Mapped View and Tensor Functions

[Function index](FUNCTION-INDEX.md) | [Value APIs](PYTHON-CACHE-VALUE-FUNCTIONS.md) | [Java segments](TRAINING-CACHE-FUNCTIONS.md)

This reference covers mapped registries and array/tensor helpers in client.py,
mapping.py and tensor.py. It distinguishes file mapping, slice ownership, payload
hashing, CPU views, pinned copies and accelerator transfer. A read-only mapping
does not make an arbitrary tensor operation immutable or provide a database
snapshot/segment-file retention transaction.

## Two Registry Implementations

The package [exports](../../clients/python/aether_training_cache/__init__.py) import
`MappedSegmentRegistry` from **client.py**, not mapping.py. `CacheView` comes from
mapping.py. Direct imports determine registry behavior; matching class names do
not imply identical generation/bounds/cleanup semantics.

| Boundary | client.py registry | mapping.py registry |
| --- | --- | --- |
| Cache key | Segment ID only | Segment ID and generation |
| Maximum validation | None | Requires maximum_open >= 1 |
| Reference bounds | Relies on slicing | Explicit nonnegative/in-file checks |
| Hash mismatch | Raises IOError | Releases slice, raises ValueError |
| Mapping counters | None | Open/reuse/close counters |
| Evicted mapping | Retired, not immediately closed | Retired, not immediately closed |

Neither uses a thread lock or validates segment paths as confined managed paths.
References supplied by the Java cache use safe derived hex filenames, but arbitrary
direct callers can construct other IDs. Both use string path concatenation and
map the whole file. Local file availability/access is required; GET_REF is not a
remote file-streaming endpoint.

## client.py Registry Functions

Source: [client.py](../../clients/python/aether_training_cache/client.py).

| Function | Behavior |
| --- | --- |
| `MappedSegmentRegistry.__init__(segment_directory, maximum_open)` | Stores directory/limit, creates active OrderedDict and retired list; default limit 32, no positive-limit check. |
| `__enter__()` | Returns registry. |
| `__exit__(...)` | Calls close. |
| `view(reference)` | Pops cached mapping by ID or opens file and read-only mmap; reinserts newest, retires oldest beyond limit, creates slice and verifies SHA-256 against supplied checksum. |
| `cache_view(reference)` | Wraps result in mapping.py CacheView retaining this registry. |
| `close()` | Attempts active mappings/handles, retires BufferError cases, clears active map; retries retired items, ignores BufferError and clears retired list. |

view does not use generation to reopen a same-ID mapping and does not explicitly
validate offset/length against file size. Python slicing can clamp values. SHA-256
checks bytes in the resulting view but does not independently prove reference
metadata is well formed. A mismatch raises IOError; there is no explicit slice
release in that branch. Empty files can fail whole-file mmap construction.

Opening and mapping use no finally cleanup if mmap creation fails after file open.
Eviction moves mappings to a retired list rather than closing them; maximum_open
is an active-cache bound, not a bound on total live mappings/handles. Invalid
negative limits can fail eviction logic rather than being rejected at construction.

## mapping.py Registry Functions

Source: [mapping.py](../../clients/python/aether_training_cache/mapping.py).

| Function | Behavior |
| --- | --- |
| `MappedSegmentRegistry.__init__(segment_directory, maximum_open)` | Rejects limit < 1; stores directory and creates active/retired collections plus counters. |
| `__enter__()` and `__exit__(...)` | Return registry and close on context exit. |
| `view(reference)` | Uses `(ID, generation)` cache key; increments open counter before opening, or reuse counter on cache hit; checks nonnegative offset/length and end <= mapping length, creates slice, hashes, then retires excess active mappings. |
| `cache_view(reference)` | Returns CacheView wrapping the freshly validated view and retaining registry owner. |
| `close()` | Tries active plus retired mappings, closes mapping then file and counts successful pair closure; ignores BufferError, then clears both collections. |

Generation distinguishes registry entries but is not verified against file metadata;
the actual pathname still uses only segment ID. SHA-256 runs on every view even
when the mapping is reused. Hash mismatch releases the newly created slice before
raising. Bounds/hash failure can leave the opened mapping cached; limit retirement
happens after successful validation. Open counters can include failed opens, so
they are attempts rather than guaranteed live-handle counts.

Both registries can encounter BufferError while NumPy/tensor/memoryview exports
remain alive. close then clears ownership lists even for failed closures; it does
not retain those entries for a future reliable explicit retry. Cleanup can still
depend on remaining references and eventual object lifetime. There is no global
reference-counted lease manager, active-map close-on-eviction, or safe-forcing-unmap
of exported views. Release consumers before closing registry, and do not claim
maximum_open proves a hard file-descriptor or virtual-address-space bound.

## CacheView Functions

CacheView is defined in mapping.py.

| Function | Behavior |
| --- | --- |
| `CacheView.__init__(view, registry)` | Retains supplied view/owner, sets closed false; no validation or additional slice copy. |
| `buffer` property | Returns original view unless closed, then raises RuntimeError. Does not create a new independent lease. |
| `__enter__()` | Returns view owner without a separate closed check. |
| `__exit__(...)` | Calls close. |
| `close()` | If not closed, releases its memoryview and marks closed; idempotent at wrapper level. Does not close registry or erase arrays/tensors derived from exported storage. |

Keeping `_registry` retains an owner reference but does not prevent the registry's
explicit close. The wrapper has no destructor or file-retention coordination with
server eviction. Its closed flag governs future buffer property access, not every
previously returned derived array/tensor.

## CPU Array and Tensor Functions

Source: [tensor.py](../../clients/python/aether_training_cache/tensor.py).

| Function | Behavior |
| --- | --- |
| `numpy_array(view, dtype, shape)` | Lazy-imports NumPy, uses view.buffer when available or raw input, calls frombuffer, optionally reshape. No explicit payload schema, byte-order or shape validation beyond NumPy. |
| `torch_tensor(view, dtype, shape)` | Lazy-imports Torch and wraps the NumPy view with from_numpy; no explicit writable copy or GPU transfer. |
| `torch_tensor_from_view(view, dtype, shape)` | Constructs AetherTensor around CPU tensor and supplied view lease. |
| client.py `numpy_view(view, dtype, shape)` | Uses frombuffer and optional reshape directly on supplied memoryview; does not return an owner wrapper. |
| client.py `torch_view(view, dtype, shape)` | Calls from_numpy on numpy_view; no separate lifetime wrapper. |

These functions share underlying host storage where the library operation permits.
They do not protect against caller mutation through other references or make
writable copies for read-only mapping input. Treat mapped artifact bytes as read-only
and keep the relevant owner alive. They do not parse tensor metadata from a cache
envelope; dtype/shape come from the caller, with defaults int32 and no reshape.

### AetherArray and AetherTensor

| Function | Behavior |
| --- | --- |
| `AetherArray.__init__(array, lease)` | Stores array and lease; does not validate owner/type. |
| `AetherArray.close()` | Calls lease.close, leaving array attribute in place. |
| `AetherArray.__enter__()` and `__exit__(...)` | Return wrapper and close on exit; no closed guard. |
| `AetherTensor.__init__(tensor, lease)` | Stores tensor and lease. |
| `AetherTensor.close()` | Calls lease.close, retaining tensor attribute; no wrapper-level idempotence state. |
| `AetherTensor.__enter__()` and `__exit__(...)` | Return wrapper and close on exit. |
| `AetherTensor.to(device, non_blocking)` | Returns underlying tensor.to result; does not return a new AetherTensor/lease wrapper. Defaults CUDA and synchronous flag false. |
| `AetherTensor.checksum()` | Views tensor as uint8, converts to int64, sums and masks to 32 bits. This additive checksum is not segment SHA-256 or a corruption-proof identity hash. |

Closing an owner does not automatically invalidate or clear all consumers created
from its array/tensor. A to call that does not actually copy storage still needs
the original lifetime; the wrapper does not decide that for the caller. Closing
arbitrary supplied leases can also throw; these context exits do not suppress it.

## Pinned Memory and Accelerator Transfer

| Function | Behavior |
| --- | --- |
| `pinned_tensor(tensor)` | Requires CPU tensor, allocates pinned empty_like host storage and copies tensor; returns that new tensor. This is explicitly a copy, not mmap sharing. |
| `gpu_transfer(tensor, device, non_blocking)` | Returns None if CUDA is unavailable; otherwise times tensor.to followed by torch.cuda.synchronize and returns `(result, elapsed_ns)`. |

gpu_transfer defaults to CUDA, but accepts a supplied device and still uses CUDA
availability/synchronization logic. It is not a generic accelerator timing helper.
Synchronization waits for completion even with non_blocking true, so returned
elapsed time is not merely host enqueue time and can include other queued work.
Neither helper selects a training model, computes gradients, or guarantees transfer
overlap in a surrounding pipeline.

## Coverage and Lifetime Limits

Python declaration checks cover mapping.py/tensor.py and the client.py functions
collectively across references. They check explicit functions/properties, not
dataclass-generated methods, live mmap destruction or GPU runtime correctness.
No dedicated mapping/tensor lifetime regression suite was identified in the
client test directory during this source pass. Export ownership, read-only sharing,
failure cleanup and device timing require distinct runtime tests before claims of
hard resource bounds or universal zero-copy behavior.
