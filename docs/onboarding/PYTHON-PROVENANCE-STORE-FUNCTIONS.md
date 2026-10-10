# Filesystem Artifact Store and Publication Functions

[Function index](FUNCTION-INDEX.md) | [Dataset and lineage functions](PYTHON-PROVENANCE-WORKFLOW-FUNCTIONS.md) | [Validation and diagnostics](PYTHON-PROVENANCE-DIAGNOSTIC-FUNCTIONS.md)

Source: [ml.py](../../clients/python/aether_training_cache/ml.py), re-exported by
[aetherml](../../clients/python/aetherml/__init__.py), **without an underscore**.
This page covers 31 explicit functions. Together the three provenance references
cover all 79 functions in that module, including nested helpers and properties.
The module's location inside aether_training_cache does not make this store a
Java-backed cache. It is a separate filesystem prototype using Python files,
pickle by default, per-instance locking and JSON metadata.

## Architecture and File Authority

```text
transform descriptor + serialized inputs -> caller cache key
serialized result -> content SHA-256 -> artifacts/aa/bb/full-content-hash
cache key + content hash -> aether:provenance-node-hash
  -> metadata/artifacts/node-hash.json
  -> metadata/cache/cache-key.json (replaceable pointer)

commit order: blob -> artifact metadata -> cache pointer
each write: tmp file -> flush/fsync file -> os.replace destination
snapshot/experiment records: separate JSON files, not part of that commit
```

Content and provenance identities are different. Two transforms can store one
identical content blob but have distinct artifact nodes. A later publication of
different bytes under the **same cache key** creates a different node and replaces
the pointer; it is not the Java cache's immutable-key conflict policy. Existing
blob/metadata paths are reused without rewriting them. There is no WAL, manifest,
SSTable, daemon, eviction service, cross-process lock or bulk Java admission here.

The per-instance RLock serializes commit and snapshot numbering only for callers
sharing that object. Reads and many other operations do not acquire it. Separate
handles/processes can race, including selecting the same snapshot number. The
context manager is a convenience, not a resource/transaction lease: exit performs
no close, sync, rollback or consistency validation.

## Records and Ownership

ArtifactMetadata is a frozen dataclass with no explicit methods. Fields are
artifact_id, content_hash, source_artifact_ids, transformation_name/version,
parameters, created_at, code_commit, python_version, library_versions,
storage_location, size, checksum and cache_key. Frozen does not make its lists or
dictionaries deeply immutable. Commit retains supplied nonempty parameters and
the store's library_versions in the returned object; JSON writes serialize their
values at that moment. Metadata loaded later is a new object reconstructed from
JSON, not an alias to the returned receipt.

Defaults serialize values with pickle.dumps and load them with pickle.loads.
**Only read trusted stores**: a checksum does not make pickle safe against code
execution. Serializer/deserializer are caller-supplied and must agree; commit_file
and commit_bytes bypass serialization, while load_artifact still deserializes.
Use byte-loading APIs for arbitrary raw files. No codec identifier is recorded or
negotiated, and a changed serializer can change identity and interpretation.

## Construction and Identity Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `open(path)` | Constructs a default AetherMLStore. This exported name is not the built-in file opener or a Java connection. Cannot pass custom serializers through this convenience function. |
| `AetherMLStore.__init__(root, serializer=None, deserializer=None, code_commit=None, library_versions=None)` | Constructs paths, chooses supplied callables or pickle defaults, selects supplied/env/unknown commit, copies library_versions, creates an RLock and five initial latency buckets, then creates layout directories. Does not run validate/recover, detect a Git commit automatically or open a daemon. |
| `AetherMLStore.__enter__()` | Returns the same store. Does not begin a transaction or validate its contents. |
| `AetherMLStore.__exit__(exc_type, exc_value, traceback)` | Returns None. Does not suppress the body's exception or perform cleanup; no store close method is implied. |
| `AetherMLStore._ensure_layout()` | mkdir(parents=True, exist_ok=True) for blob, metadata, snapshot, experiment, cache and temp directories. Existing incompatible filesystem entries raise; no version/format header or identity lock is created. |
| `AetherMLStore.transformation_key(*, input_hash, transformation_name, transformation_version, parameters=None)` | SHA-256 of compact canonical JSON containing input hash, name/version, parameters, store code commit/library versions and current Python version. Includes no function-source inspection, automatic source-file hashing or random-state capture. default=str can stringify otherwise unsupported descriptor values. |
| `AetherMLStore._hash_inputs(args, kwargs)` | Normalizes each top-level positional/keyword value, collects any directly supplied ArtifactMetadata IDs, serializes the args-list/kwargs-dictionary with this store's serializer and hashes those bytes. Does not sort kwargs or recursively normalize metadata buried in containers. The callable still receives its original arguments. |
| `AetherMLStore._normalize_input(value)` | Direct ArtifactMetadata becomes `{artifact_id: value.artifact_id}` plus that ID for lineage inference; every other value passes through unchanged with no inferred parent. Does not load the artifact, verify metadata or recognize string paths as source content. |
| `_canonical_json(value)` | Sorted-key compact JSON encoded as UTF-8, with default=str. Deterministic for appropriately stable values, not a universal type-preserving serializer; unsupported types can collide through equal string representations. |

Input hashing defaults to pickle rather than canonical source-content descriptors.
Keyword insertion order, serializer behavior and input object state can affect
the hash. A path/string argument does not automatically bind the file's bytes.
Explicit source_artifact_ids supplied to CachedTransform.apply contribute lineage
but do **not** enter cache_key_for_call. A changed parent list alone need not cause
a miss. The workflow reference explains this distinction and the None miss sentinel.

## Publication Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore.commit_artifact(value, *, cache_key, source_artifact_ids=(), transformation_name, transformation_version, parameters=None)` | Serializes value before delegating to commit_bytes. Serialization failures occur before the latter's commit timer/lock. The default uses pickle, not the built-in aether_ml binary codecs. |
| `AetherMLStore.commit_file(path, *, cache_key, source_artifact_ids=(), transformation_name, transformation_version, parameters=None)` | Reads the entire source file into memory and delegates as raw bytes. No streaming/chunked file import, source-file lease or default pickle wrapping. File-read time is outside commit_bytes timing. |
| `AetherMLStore.commit_bytes(data, *, cache_key, source_artifact_ids=(), transformation_name, transformation_version, parameters=None)` | Starts timing before acquiring the instance lock, delegates to _commit_bytes_locked, and records artifact_commit latency in finally, including failure and lock wait. A successful result is metadata, not a Java durability receipt. |
| `AetherMLStore.commit_bytes_many(entries, *, transformation_name, transformation_version, parameters=None)` | Holds the instance lock while iterating entry mappings and committing each independently. Uses entry data/cache_key, optional source IDs and per-entry parameter override. One artifact_commit_batch timing includes iterator time, lock wait and failure. No all-or-nothing batch rollback; earlier entries remain published if a later entry fails. |
| `AetherMLStore._commit_bytes_locked(data, *, cache_key, source_artifact_ids=(), transformation_name, transformation_version, parameters=None)` | Converts to bytes, hashes content and the cache-key/content pair, derives blob/node/cache paths, writes a missing blob, creates current metadata, writes it only if absent, then always replaces cache pointer JSON. Returns the newly constructed metadata even when existing on-disk node metadata was reused. Does not validate parent existence, rehash an existing blob or reject an existing key with other content. |

### Commit and Failure Boundaries

Each file is individually replaced, but the three-file sequence is not one atomic
transaction. Failure after the blob can leave an orphan. Failure after metadata can
leave a valid node with no new cache pointer. Failure after a pointer replacement
can leave published data despite an exception, including a later cleanup error.
Reopen/validate outcomes; do not infer rollback from a failed method call.

Existing content is not healed if someone has corrupted its blob. Publishing the
same bytes/key again skips that blob and existing node metadata. The returned
receipt has a fresh timestamp and supplied lineage, while persisted metadata can
still have the first timestamp/lineage. A later hit uses persisted metadata. The
commit API is therefore not a merge/update mechanism for provenance of an existing
node. It also does not preflight that every parent exists or establish acyclicity.

The atomic helper fsyncs the temporary **file** before replacement, not the parent
directory afterward. New directories are not force-synced. Individual replace
atomicity and power-loss durability depend on the filesystem/platform; no Java
DURABLE barrier or crash-atomic multi-file publication is claimed.

## Lookup and Retrieval Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore.load_cached(cache_key)` | Looks up one artifact ID, returns None for no usable pointer or missing node metadata, otherwise deserializes through load_artifact. Missing/corrupt payload and malformed node metadata propagate; a legitimately cached None is indistinguishable from a miss to CachedTransform. |
| `AetherMLStore.cached_artifact_id(cache_key)` | Reads cache JSON artifact_id, returning None for missing file, missing field or JSON decode error. Does not validate node/payload existence. Other OS/type/encoding errors can propagate. Records cache_lookup even for a miss/failure. |
| `AetherMLStore.cached_artifact_ids(cache_keys)` | Iterates _cache_entry and returns pointers for parsed entries; duplicates collapse. Does not hash payloads or require node metadata. Missing artifact_id/type errors can propagate. One cache_lookup_batch timing, not individual cache_lookup samples. |
| `AetherMLStore.load_cached_bytes_many(cache_keys)` | Uses each cache entry, skips missing entries and supported KeyError/FileNotFoundError/IOError retrieval failures (including checksum mismatch), returns copied bytes for hits. Other malformed-data/type/encoding failures are not universally converted into misses. Records one cache_payload_retrieval_batch timing even after failure. |
| `AetherMLStore._cache_entry(cache_key)` | Returns decoded JSON or None for absent file, JSON decode error or OSError during reading. Does not assert an object shape or metadata fields. |
| `AetherMLStore._load_cache_entry_bytes(cache_entry)` | Uses embedded storage_location/checksum, or falls back to artifact metadata if either is None. Reads all bytes and verifies SHA-256. Does not compare size or check that complete embedded fields match node metadata; a self-contained cache pointer can work without node metadata. |
| `AetherMLStore.load_artifact(artifact_id)` | Passes checksum-verified bytes to the configured deserializer. Deserialization time/failure is outside load_artifact_bytes' retrieval timing. Never feed untrusted pickle here. |
| `AetherMLStore.load_artifact_bytes(artifact_id)` | Loads metadata, reads its storage path, validates checksum, returns owned bytes. Records artifact_retrieval in finally, nesting metadata_lookup timing. Does not separately enforce metadata.size, recompute artifact identity or verify parents. |
| `AetherMLStore.load_artifact_bytes_many(artifact_ids)` | Repeats metadata/read/checksum work for every ID into a dictionary. Any invalid item aborts the method; earlier locally collected results are not returned. Duplicates collapse but are still read. Records one artifact_retrieval_batch rather than per-item artifact_retrieval. |
| `AetherMLStore.artifact_path(artifact_id)` | Loads metadata and requires its storage path to exist. Returns a Path, not a checksum-verified read, open handle, immutable lease or containment guarantee. |
| `AetherMLStore.artifact_metadata(artifact_id)` | Loads node JSON and constructs ArtifactMetadata; missing node raises KeyError. Dataclass construction checks field names/arity, not runtime annotations or full identity/schema correctness. Records metadata_lookup in finally. |

Single-ID retrieval is strict about a missing/corrupt payload. Cache-byte batch
retrieval deliberately treats several such cases as misses. These policies are
different from contains/presence or the Java adapter. Returned bytes are full-file
copies, not mmap memoryviews. None of these reads pins a multi-file snapshot
against another handle replacing a pointer or editing metadata.

## Filesystem Helpers and Trust

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore._iter_artifact_metadata()` | Yields decoded ArtifactMetadata for each immediate metadata JSON file. Filesystem glob order is not an ordering contract; malformed files stop consumers rather than producing a diagnostic item. |
| `AetherMLStore._metadata_path(artifact_id)` | Removes an optional leading aether: and appends .json under metadata_dir. Does not require a SHA-256 digest or reject path separators/traversal. |
| `AetherMLStore._cache_path(cache_key)` | Appends .json under cache_dir without validating the supplied key as a digest or safe filename. |
| `AetherMLStore._atomic_write_json(path, value)` | Pretty sorted JSON encoding followed by atomic byte write. Unlike _canonical_json, no default=str; unsupported values can fail serialization before writing. |
| `AetherMLStore._atomic_write_bytes(path, data)` | Creates parent directories and a uniquely named temp file under tmp_dir; writes, flushes, fsyncs file, closes and replaces destination. Finally unlinks any remaining temp file. No destination-directory fsync, multi-file rollback or secure path validation. Cross-filesystem replacement can fail if layout has external mount/symlink boundaries. |
| `_artifact_relative_path(content_hash)` | Splits the first two and next two characters into blob fanout directories and uses the full hash as filename. Normal caller supplies a generated content SHA-256; the helper itself does not validate arbitrary input. |

Treat the entire root, IDs, keys, storage_location and experiment/snapshot fields
as trusted. Helpers do not implement the managed-path containment checks from the
Java filesystem module. Absolute paths, separators or tampered JSON can redirect
reads/writes; SHA-256 does not authenticate metadata, constrain paths or authorize
access. This reference documents current behavior, not a hardened import service.

## Verification Scope

[test_aetherml.py](../../clients/python/tests/test_aetherml.py) exercises reuse,
distinct provenance/shared content, raw-file commits, batch lookups/publication,
snapshot and corruption behavior. The focused
[provenance contract tests](../../scripts/tests/test_provenance_documented_contracts.py)
exercise sentinel, identity, metadata-reuse and failure boundaries with disposable
files. They do not establish cross-process safety, power-loss durability, arbitrary
corruption-parser coverage or Java/GPU performance. AST inventory and rendered-page
checks establish reference coverage, not a production certification.
