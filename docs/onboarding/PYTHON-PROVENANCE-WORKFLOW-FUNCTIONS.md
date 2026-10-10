# Filesystem Transform, Dataset, and Provenance Functions

[Function index](FUNCTION-INDEX.md) | [Store and publication](PYTHON-PROVENANCE-STORE-FUNCTIONS.md) | [Validation and diagnostics](PYTHON-PROVENANCE-DIAGNOSTIC-FUNCTIONS.md)

Source: [ml.py](../../clients/python/aether_training_cache/ml.py), exported as
aetherml. This page covers 29 explicit functions, including nested decoration and
lineage traversal. This is not aether_ml.AetherDataset or the daemon-backed
reference loader. Its transforms, datasets, snapshots and experiment records use
the separate local-filesystem AetherMLStore.

## Workflow and Authority

```text
dataset[index] -> source value
  -> cached transform: serialized input key -> cache pointer -> hit or compute
  -> next transform: previous artifact ID attached as a parent
  -> final value (optionally all collected node IDs)

snapshot = JSON membership/configuration record, no automatic payload validation
manifest = metadata export, optionally parents before descendants
verify = direct snapshot payload checksum/size checks
restore = load/deserialise members into a dict, no database rollback
experiment = JSON snapshot/model/seed/metrics/output record, no training execution
```

Identity is caller- and descriptor-driven. CachedTransform hashes serialized
arguments and a transformation descriptor. It does not prove determinism, hash
function implementation source, bind external file bytes automatically, freeze
random state or ensure that parameters describe what the function actually does.
Its parameters affect identity/provenance; they are not automatically supplied as
function kwargs. Use explicit version/name/descriptor discipline.

## Transform and Dataset Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `CachedTransform.__init__(store, function, *, version, name=None, parameters=None)` | Retains owner/function, selects supplied name or module.qualname, stringifies version, retains nonempty parameters and applies functools.update_wrapper. No connection, preprocessing, version validation or determinism inspection. |
| `CachedTransform.__call__(*args, **kwargs)` | Calls apply and returns value only, discarding its metadata receipt. |
| `CachedTransform.apply(*args, source_artifact_ids=(), **kwargs)` | Computes key/inferred parents, deduplicates inferred+explicit source IDs preserving order, loads cached value and, if not None, separately reads pointer and node metadata. A vanished pointer raises IOError. Otherwise executes original function with original args/kwargs, commits serialized result and returns result/new metadata. No lock spans check/compute/commit; concurrent misses can both compute. |
| `CachedTransform.cache_key_for_call(*args, **kwargs)` | Delegates top-level input normalization/hash and transformation descriptor hashing; returns cache key and inferred direct metadata parents. Explicit apply source_artifact_ids are not included. Does not look up payload or call the function. |
| `CachedTransform.aether_cache_key(*args, **kwargs)` | Returns just the calculated key, with input serialization/hashing cost but no cache read. |
| `CachedTransform.aether_artifact_id(*args, **kwargs)` | Computes key and reads current cache pointer. No payload validation; ID can be dangling. |
| `AetherMLStore.cached_transform(version, *, name=None, parameters=None)` | Returns a closure capturing settings/store for decorator syntax. Does not decorate until the closure is invoked. |
| `AetherMLStore.cached_transform.decorate(function)` | Nested closure delegates to cached_transform_function with captured descriptor. |
| `AetherMLStore.cached_transform_function(function, *, version, name=None, parameters=None)` | Constructs CachedTransform. No function execution or eager population. |
| `AetherDataset.__init__(source, transforms=(), *, return_artifact_ids=False)` | Requires len/getitem attributes, retains source, materializes transforms and stores output mode. No source copy, transform type check, worker context or population. |
| `AetherDataset.__len__()` | Delegates source length on each call. No stored membership snapshot. |
| `AetherDataset.__getitem__(index)` | Reads source and applies transforms in order. For actual CachedTransform objects, passes the last collected ID as an explicit parent and appends returned metadata ID. For other callables, transforms then optionally asks aether_artifact_id about the original transform input. Returns value or (value, collected IDs). Failures propagate with earlier stage publications retained. |

A legitimately cached None triggers recomputation: apply's miss check is
`cached is not None`. Other falsy values are usable hits. The hit value, pointer
and metadata are separate reads; another handle can change the pointer between
them, so they do not constitute an atomic provenance receipt. Hits use persisted
metadata and do not merge a newly supplied parent list. A check/compute race has
no single-flight deduplication or cancellation mechanism.

Dataset lineage passes only the latest known ID, not the entire collection, to
the next cached stage. A noncached transform with no identity hook changes the
value but adds no node: a later cached stage can still name the last earlier
artifact as parent. The serialized value enters identity, but the provenance
graph is not necessarily a complete audit of every intermediate operation.
Each access reruns noncached/random transforms; this API does not automatically
split deterministic preprocessing from augmentation.

## Loader Factory and Fallback Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherDataLoader.__new__(dataset, *, batch_size=1, shuffle=False, num_workers=0, drop_last=False, collate_fn=None, **kwargs)` | If any nonzero/truthy worker count or extra kwargs are supplied, imports Torch DataLoader and forwards settings. Otherwise returns the sequential fallback without requiring Torch. Returned object is not an instance of this facade. No independent worker/pinning validation or cache/client owner management. |
| `_SequentialAetherDataLoader.__init__(dataset, *, batch_size=1, shuffle=False, drop_last=False, collate_fn=None)` | Rejects nonpositive batch size, requires len/getitem attributes, retains dataset/settings and uses supplied collation or identity lambda. Does not reject every noninteger/Boolean size at construction. |
| `_SequentialAetherDataLoader.__len__()` | divmod over current dataset length; floor count for drop_last, otherwise ceiling. Does not preload items or freeze membership. |
| `_SequentialAetherDataLoader.__iter__()` | Builds all indices, optionally shuffles with Python's global random generator, slices batches, drops incomplete final batch if selected, fetches items sequentially and calls collate_fn. No threads, prefetch queue, daemon client, explicit cleanup or generator-owned resource release. |

The fallback defaults to lists, while Torch's default collation can return tensors
or structured batches. Supplying an otherwise minor extra kwarg can select Torch
and change those semantics. No seed is set by the fallback. Configuring Torch
workers does not make the filesystem store a cross-process transaction manager;
serialization/worker ownership remains caller/framework responsibility.

## Lineage Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore.parents(artifact_id)` | Returns persisted metadata's direct source_artifact_ids list. Does not validate those parent nodes or bytes. |
| `AetherMLStore.children(artifact_id)` | Scans all immediate metadata JSON records and collects IDs naming this parent. Does not require the requested parent to exist or traverse grandchildren. Result order follows glob enumeration. |
| `AetherMLStore.lineage(artifact_id)` | Builds a per-call visited set and recursively visits parents, then appends each node. Returns ancestors-before-descendant for an acyclic graph, including the queried node itself. Missing/malformed metadata propagates; cycles are not reported as corruption. |
| `AetherMLStore.lineage.visit(current)` | Nested DFS marks ID seen before loading/visiting parents, so repeated references/cycles terminate and each ID is emitted once. Recursive depth can reach Python's recursion limit; no iterative DAG index or topological-cycle validator. |
| `AetherMLStore.depends_on(artifact_id, ancestor_artifact_id)` | Tests whether ancestor ID appears anywhere in lineage, including artifact_id itself. This reflexive result is intentional source behavior, not strict-parent-only dependency. Reads all lineage metadata. |

Parents are not part of the node hash directly: node identity is cache key plus
content hash. Tampered/mismatched parent metadata is not authenticated by those
hashes. DFS avoids infinite cycles but does not prove a valid acyclic provenance
graph. Metadata/reference checks and payload checksum checks remain separate.

## Snapshot Functions

DatasetSnapshot is a frozen dataclass containing snapshot_id, name, artifact_ids,
configuration and created_at. Lists/dictionaries remain mutable. No explicit
methods or MVCC lease are generated by this conceptual snapshot record.

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore.create_snapshot(name, artifact_ids, configuration=None)` | Under the instance lock, scans numeric JSON stems, chooses max+1, materializes IDs and writes snapshot JSON. Keeps duplicates/order. No artifact/parent existence or checksum preflight. Records snapshot_create timing even on failure, including lock wait. Separate store handles can choose the same number. |
| `AetherMLStore.open_snapshot(snapshot_id)` | Reads that JSON into DatasetSnapshot; missing path raises KeyError. No schema/version/authenticity/payload verification beyond constructor fields. Uses formatted caller ID as path component without a dedicated containment validator. |
| `AetherMLStore.restore_snapshot(snapshot_id)` | Loads/deserializes each listed artifact into a dict keyed by ID. Duplicates collapse; any failure aborts with no result. Does not replace current pointers, restore raw dataset files, rerun transforms or train a model. |
| `AetherMLStore.snapshot_manifest(snapshot_id, *, include_lineage=True)` | Exports snapshot plus asdict metadata, by default traversing each member's lineage, deduplicating IDs across members. With false, includes direct member metadata only. Does not copy blobs or hash/check their content; missing/malformed lineage can raise. |
| `AetherMLStore.verify_snapshot(snapshot_id)` | Visits direct snapshot IDs only; reports absent node metadata, missing files, checksum/size mismatches and verified IDs. Repeated IDs can be reported repeatedly. Does not recursively verify parents, deserialize payloads or verify snapshot/model reproducibility. Malformed metadata/read errors outside explicit absence branches propagate. |
| `AetherMLStore.compare_snapshots(left_id, right_id)` | Converts membership to sets and returns sorted added/removed/unchanged IDs. Ignores membership order, duplicates, name/configuration and changed external contents under an unchanged ID. No payload or lineage check. |

An empty snapshot passes the direct verification property. A snapshot containing
a valid child with a missing parent can also pass; validate and manifest traversal
have different checks. The property named is_reproducible means its three issue
lists are empty, not that code/environment/randomness can reproduce training.
The diagnostic reference records those limitations and recovery's destructive scope.

## Experiment Functions

ExperimentRecord is a frozen dataclass with experiment_id, dataset_snapshot,
model, training_parameters, optional random_seed, environment, start/end time,
metrics and output_artifacts. Defaults use fresh empty collections. The API does
not populate them automatically or validate that a run really took place.

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherMLStore.record_experiment(record)` | Writes asdict(record) to experiment-ID JSON by atomic replacement. Same ID overwrites. Does not validate its snapshot/output nodes, run training, capture environment, fill timestamps or acquire the commit lock. IDs are trusted filenames, not sanitized user input. |
| `AetherMLStore.experiments_using(artifact_id)` | Scans records, opens each dataset snapshot, combines direct members and recorded output IDs, and tests reflexive/transitive depends_on for each candidate. Returns records whose dataset/output lineage contains the queried artifact. Missing snapshot/node or malformed JSON can abort the whole scan; glob order is not chronology. |

These records are bookkeeping, not the frozen H2 experiment orchestration or its
paired evidence validation. A saved seed/environment field is a user assertion;
the store does not validate model hashes, complete epochs, backend identity,
timing boundaries or balanced block ordering.

## Verification Scope

[test_aetherml.py](../../clients/python/tests/test_aetherml.py) includes transform
reuse, sequential batching, multistage lineage, upstream experiment matching,
metadata manifests and direct snapshot verification. The focused
[provenance contract tests](../../scripts/tests/test_provenance_documented_contracts.py)
cover None hits, explicit-parent identity, loader selection, direct-only verification,
reflexive/cyclic lineage and snapshot comparison semantics. Local fixtures do not
prove Torch process ownership, training reproducibility or Java/GPU performance.
