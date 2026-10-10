# Python Transform Cache, Identity, and Codecs

[Function index](FUNCTION-INDEX.md) | [Dataset integration](PYTHON-DATASET-FUNCTIONS.md) | [Low-level client](PYTHON-CLIENT-FUNCTIONS.md)

This reference covers every explicit function in
[transform_cache.py](../../clients/python/aether_ml/transform_cache.py),
[identity.py](../../clients/python/aether_ml/identity.py), and
[codecs.py](../../clients/python/aether_ml/codecs.py). It describes the active
checkout, not the separately frozen H2 benchmark worker. These convenience APIs
do not define the confirmatory campaign's timing boundary or storage configuration.

## Architecture and Responsibility

`AetherTransformCache` is a deterministic-work adapter above the socket client:

```text
caller supplies source + stable source identity
  -> namespace / source / transform / artifact schema form CacheKey
  -> one get_many request for distinct keys within a chunk
  -> transform and encode only missing distinct keys
  -> one put_many request for those encoded values
  -> decode every requested result in original order
```

The transform produces artifacts, not model predictions. The wrapper does not
train a network, prove a transform deterministic, hash input files automatically,
or make a multi-request transaction. The low-level client and Java daemon enforce
wire limits and publication policy. The artifact codec defines application payload
bytes; it does not replace SSTable/WAL checksums or the daemon's integrity checks.

Use a namespace for the artifact family, a source identity that changes when its
meaningful input changes, and an explicit transform/schema identity that changes
when preprocessing or serialization changes. Reusing an identity for different
source content can return stale data without executing the transform at all.

## Identity Functions

| Function | Inputs, output, and failure boundary |
| --- | --- |
| `canonical_identity(value, field)` | Bytes become lowercase hexadecimal; a nonempty string passes unchanged. Other values and empty strings raise IdentityError. Empty bytes are accepted and become an empty string despite the error message's nonempty wording. `field` names the error. |
| `canonical_json(value)` | Uses sorted JSON keys, compact separators, ASCII escaping, and rejects NaN/Infinity. Wraps TypeError/ValueError as TransformIdentityError. It is JSON normalization, not a code or library-version hash. |
| `transform_fingerprint(identity)` | Canonicalizes dictionaries, normalizes a string/bytes descriptor, then delegates to TransformationFingerprint.from_descriptor for SHA-256. Converts IdentityError to TransformIdentityError. |
| `fingerprint_transform(transform)` | Accepts only objects whose class module starts with torchvision.transforms or monai.transforms. Describes supported state recursively and returns canonical JSON, not digest bytes. Arbitrary functions require explicit transform_identity. |
| `fingerprint_transform.describe(value)` | Nested recursive helper: retains scalar values, converts list/tuple to lists and dictionary keys to strings; recognized framework objects become type plus public non-callable state. Unsupported parameter objects raise TransformIdentityError. |
| `artifact_key(namespace, source_identity, transform_identity, artifact_schema_version)` | Requires a nonempty string namespace, normalizes source/schema, fingerprints a dictionary containing transform identity and schema version, and returns the low-level CacheKey. Does not read source bytes or include the selected codec class automatically. |
| `hashed_identity(value)` | SHA-256 of the UTF-8 normalized identity descriptor, returned as hex. Bytes are first converted to hex; this is not SHA-256 directly over the original bytes. |

Automatic descriptions omit private fields and callable state. They do not include
installed framework versions, code bodies, hidden random state, or every semantic
parameter. Different dictionaries can also collapse when keys stringify identically.
Treat the description as a supported convenience, not a determinism certificate.

There is an important current API distinction: `transform_fingerprint` directly
accepts bytes, but `artifact_key` wraps transform_identity inside JSON first.
A bytes transform identity, or bytes nested inside a configuration dictionary,
therefore raises TransformIdentityError during JSON serialization in that path.
Use explicit string or JSON-compatible dictionary transform identities for this
cache wrapper. Bytes source identities are normalized before the JSON step.

## Cache Construction and Client Ownership

| Function | Behavior |
| --- | --- |
| `AetherTransformCache.__init__(client, transform, ...)` | Validates mode and on_cache_error, records the transform and identity callback, chooses explicit identity or automatic fingerprint, defaults codec to AutoCodec, and initializes counters/timing lists. A missing client becomes the lazy lifecycle.create_client factory. No connection or capability handshake is performed here. |
| `AetherTransformCache._client_for_process()` | Compares os.getpid to the saved PID. On change, closes a retained client and either invokes the factory or reuses the supplied object, then records the PID. Same PID returns the retained client. |
| `AetherTransformCache._key(source_identity)` | Builds the CacheKey from retained namespace, transform identity, schema version, and supplied source identity. Identity validation happens here on use rather than uniformly in construction. |
| `AetherTransformCache.identity_for(source, index)` | Requires identity_fn. Uses inspect.signature.bind to choose a two-argument call, falling back to one argument when binding raises TypeError. Exceptions raised by the actual callback are not retried with another arity. Unsupported inspect.signature errors can propagate. |
| `AetherTransformCache.__call__(source, source_identity)` | Delegates to get_or_compute; source identity is keyword-only. |
| `AetherTransformCache.get_or_compute(source, source_identity)` | Forms a one-entry batch and returns result zero. Uses the same encode/decode path as larger requests. |
| `AetherTransformCache.close()` | Closes a retained client, then clears client/PID. If client.close raises, clearing does not execute. Does not close the daemon, clear stored artifacts, or set a permanent wrapper closed flag. |
| `AetherTransformCache.__enter__()` | Returns the wrapper; does not connect. |
| `AetherTransformCache.__exit__(exc_type, exc_value, traceback)` | Calls close without suppressing an exception from the body. |

A factory can create a fresh connection for each worker process. Supplying one
already-created client object does not turn it into a factory: after a PID change,
that same object can be selected again after close. A later call after wrapper.close
can also recreate/select a client. Factory creation failures remain retryable on a
later call because the new PID is recorded only after creation succeeds.

Counters and client selection have no wrapper-level synchronization. Do not infer
thread-safe transform execution or atomic metrics from a low-level socket lock.

## Batch Lookup and Publication

`AetherTransformCache.get_many_or_compute(sources)` materializes the input iterable.
More than 4,096 entries are processed recursively in consecutive chunks; output
order is preserved, but duplicate-key coalescing is per chunk, not whole iterable.
The whole original iterable is already in memory, so this is not streaming input.

For one enabled chunk, the exact phases are:

1. Build keys and preserve first-seen distinct-key order with dict.fromkeys.
2. Fetch distinct keys through client.get_many. Successful lookup counters count
   original requested entries, including duplicates, rather than wire key count.
3. Reject missing keys in read-only mode before running any transforms.
4. Build a key-to-source dictionary. For repeated identities with different source
   objects, the **last** source wins for miss computation. Compute and encode each
   missing distinct key once in first-seen key order.
5. Publish all computed payloads with put_many in read-write or populate mode.
6. Combine fetched/computed payloads and decode once per original requested entry.
   Freshly computed artifacts are encoded then decoded; they are not returned as
   the original transform objects. Duplicate identities can yield separately copied
   decoded arrays/tensors.

This wrapper does not reserve misses while computing, deduplicate concurrent
workers, or roll back earlier chunks if a later chunk fails. A publication error
does not prove the server committed nothing: transport failure may occur after
publication. Consult the low-level retry and Java cache publication references.

### Modes and Failures

| Setting or phase | Actual effect |
| --- | --- |
| read-write | Reuses hits and computes/publishes misses. |
| populate | Uses the same lookup/reuse/compute/publish path as read-write. It does not overwrite every existing hit or invoke the offline bulk loader. |
| read-only | Missing distinct keys raise CacheMissError; no transform or publication follows. |
| disabled | Directly transforms every requested source, including duplicates. Skips identity/key building, client, codec, counters, and successful batch/decode timing samples. |
| Lookup failure with raise | Increments cacheErrors, records lookup timing in finally, and raises CacheUnavailableError chained to the original exception. |
| Lookup failure with fallback | Logs a warning and directly transforms every source; does not encode/publish/decode, and does not record successful batch preparation timing. Even read-only can compute in this explicitly selected fallback policy. |
| Transform or encode failure | Propagates the underlying exception; no put_many occurs for that chunk. Previously completed chunks remain published. Not handled by on_cache_error. |
| Publication failure with raise | Increments cacheErrors and raises PublishError. No result decoding follows. |
| Publication failure with fallback | Logs and decodes computed payloads for return. Does not retry publication at this wrapper layer or increment successful publishes. |
| Decode failure | Propagates codec failure, potentially after publication succeeded. No successful decode/batch timing sample is appended. |

An empty enabled batch still passes an empty key list to get_many, so a client
creation error can occur even with no samples. The wrapper catches Exception for
lookup/publication, not process termination or every BaseException.

## Planning and Observability

| Function | Behavior |
| --- | --- |
| `AetherTransformCache.plan(source_identities, estimated_compute_seconds_per_sample)` | Materializes identities, rejects a negative estimate, then checks presence in chunks of 4,096 through contains_many. Counts duplicate requested keys independently. Returns total/reusable/missing/reuseRatio without transforming or publishing. Disabled mode reports all missing without constructing keys. Any client failure is CacheUnavailableError even when fallback is configured. |
| `_with_work_estimate(result, seconds_per_sample)` | With no estimate, returns the same dictionary. Otherwise adds reusable * estimate and missing * estimate; these are modeled compute seconds, not measured lifecycle savings. |
| `AetherTransformCache.stats()` | Returns a fresh top-level dictionary of existing counters, hitRate, accumulated lookupNs, and summaries of stored timing samples. Zero-valued metric names not yet inserted in Counter may be absent. Does not reset counters or poll server metrics. |
| `_latency_summary(values)` | Empty input yields zero count/mean/p95. Otherwise sorts samples and selects min(n-1, int(n * .95)); reports milliseconds. This is the implementation's index rule, not interpolated statistical quantiles. |
| `AetherTransformCache.reproducibility_metadata()` | Returns namespace, schema version, str(transform_identity), and literal integrityPolicy=engine-managed. Does not record a verified source commit, runtime versions, actual checksum configuration, or canonical identity digest. |

Timing samples grow for the wrapper's lifetime without a retention cap. Lookup
timing includes client selection/exchange and failed attempts at this layer.
Publish samples exist only for successful put_many; decode and batchPreparation
samples exist only after successful decoding. Counters mix requested-entry units
(lookups/hits/misses/bytesRead) with distinct-artifact units (publishes/bytesPublished).
bytesRead counts encoded cached payload bytes per requested occurrence, not socket
traffic. Cache misses are counted only after all missing transforms encode
successfully. Read-only miss errors and lookup fallback do not increment misses.

Presence planning is not a reservation: eviction, another producer, or an input
identity mistake can change what a later lookup observes. The plan does not verify
every payload can decode or establish a transactional snapshot of all chunks.

## Artifact Codec Functions

| Function | Conversion and ownership |
| --- | --- |
| `ArtifactCodec.encode(value)` | Protocol declaration requiring bytes output; no runtime implementation or validation. |
| `ArtifactCodec.decode(payload)` | Protocol declaration accepting bytes/memoryview; caller chooses semantic result type. |
| `BytesCodec.encode(value)` | Applies bytes(value), translating TypeError to a bytes-like diagnostic. Conversion is broader than the annotation: integers and integer iterables can also be accepted by Python bytes. |
| `BytesCodec.decode(payload)` | Returns bytes(payload); an existing immutable bytes input may be reused, while a memoryview is copied. |
| `NumPyCodec.encode(value)` | Lazy imports NumPy, makes a C-contiguous array, JSON-encodes dtype.str and shape, and concatenates AENP1 + big-endian uint32 header length + header + array.tobytes. Array conversion and serialization may copy. |
| `NumPyCodec.decode(payload)` | Checks nine-byte minimum/magic/header bounds, parses JSON, uses dtype/frombuffer and reshape, then copy for an independent writable array. Wraps KeyError/TypeError/ValueError as ArtifactDecodeError; missing NumPy is ImportError. |
| `TensorCodec.__init__()` | Owns a NumPyCodec delegate, not a tensor/device buffer. |
| `TensorCodec.encode(value)` | Calls detach().cpu().numpy() then NumPy encoding. Can synchronize/copy GPU data to host, discards autograd history, and translates AttributeError to a tensor diagnostic. Other unsupported tensor/dtype/layout errors propagate. |
| `TensorCodec.decode(payload)` | Imports Torch and wraps the independently copied decoded NumPy array with from_numpy. Returns a CPU tensor sharing that new array, not the original GPU device/storage/gradient state. |
| `TensorDictCodec.encode(value)` | Requires a dict with string keys, sorts keys, converts detach-capable values through CPU NumPy, serializes each contiguous array, and records key/dtype/shape/offset/size/tensor flag. Writes AETD1 + header length + JSON entries + concatenated raw payloads. |
| `TensorDictCodec.decode(payload)` | Checks header framing, then entry offset lower bounds and payload end upper bounds. Reconstructs and copies each array; tensor-marked entries become CPU Torch tensors. Wraps KeyError/TypeError/ValueError as ArtifactDecodeError. Missing optional libraries may propagate ImportError. |
| `AutoCodec.encode(value)` | Selects bytes first, dict second, detach-capable object third, actual NumPy ndarray last. Prepends B/D/T/N to its codec payload. Unsupported values raise TypeError; no pickle fallback. |
| `AutoCodec.decode(payload)` | Reads the first-byte tag from a memoryview and delegates on the remainder. Empty/unknown tag raises ArtifactDecodeError. Creates a new codec instance per call. |

### Format and Validation Limits

NumPy/tensor payloads contain type and shape metadata but no codec-level checksum.
Successful cache retrieval does not independently prove this application schema
is correct. Decode copies deliberately separate returned arrays from encoded cache
bytes; these codecs are not the mapped zero-copy tensor APIs.

The dictionary parser does not explicitly enforce unique keys, disjoint/ordered
regions, exhaustive payload consumption, or nonnegative sizes; malformed shape,
slice, and dtype failures are partly delegated to NumPy. Duplicate keys overwrite
earlier decoded dictionary entries. Trailing bytes outside referenced regions may
be ignored. These are descriptions of existing checks, not guarantees of a hardened
untrusted-input parser.

NumPy object dtypes are not explicitly rejected during encode even though raw
object-array bytes are not a portable serialized object representation. Structured
dtype descriptions are not preserved in full by dtype.str. Use supported numeric
arrays/tensors and an explicit schema identity; do not treat non-pickle framing as
support for arbitrary Python objects, every dtype, or every Torch layout.

## Tests and Source-Reading Exercises

[test_aether_ml.py](../../clients/python/tests/test_aether_ml.py) covers identity
component changes, supported/unsupported automatic descriptions, batched miss
computation and reuse, read-only misses, fallback warnings, planning estimates,
metrics, codec round trips, and client-factory replacement on a simulated PID
change. Its MemoryClient fixtures verify wrapper behavior, not actual Java
durability, network retries, eviction races, or GPU campaign reproducibility.

Trace a duplicate input through keys, distinct lookup, last-source selection,
publication, and repeated decode. Then compare disabled, read-only, and fallback
paths. Explain which counters and timing samples exist after each failure; they
are intentionally not interchangeable with the H2 lifecycle endpoint.
