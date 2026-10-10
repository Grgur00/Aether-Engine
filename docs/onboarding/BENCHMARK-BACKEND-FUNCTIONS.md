# Benchmark Backend Ownership and Population Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark data](BENCHMARK-DATA-FUNCTIONS.md) | [Model and training](BENCHMARK-TRAINING-FUNCTIONS.md) | [Storage adapters](PYTHON-STORE-ADAPTER-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This page covers all 24 BackendContext methods plus aether_cold_start_gate and
warm_backend: **26 explicit declarations**. It is not a complete benchmark
reference. The underlying Java client, Python prototype and persistent mmap store
have their own identity/publication contracts; this context does not replace them.

## Ownership Architecture

```text
args + source rows + canonical reference -> BackendContext
  -> temporary/external local directory -> Python artifact store
  -> Java client + namespace -> already-running daemon (not started here)
  -> persistent mmap directory/index/journal -> read-only mapped records
  -> optional RAM list of packed payload bytes

batch(indices) -> raw recomputation OR lookup -> miss preparation -> publication
               -> unpack same artifact precision -> ordered stacked arrays
close -> mmap handles + Java client + legacy handles -> temporary directory cleanup
```

RAW_RECOMPUTE prepares inputs every batch. AETHER_CACHE looks up one logical batch
and publishes misses together. STATIC_PREPROCESSED_MMAP is an incremental keyed
store despite its historical label, not a fixed positional array. RAM_READY holds
encoded bytes, not prebuilt device tensors, and still decodes/stacks each batch.
All paths use the same artifact precision boundary before training augmentation.

This class opens both stores during construction even when one is not selected
for measured training. Construction calls are sequential, not transactional; an
exception partway through initialization has no constructor-wide rollback.
External fresh directories are recursively removed with ignored deletion errors.
Use only dedicated disposable cache paths. Do not point a fresh cache at valuable
data, overlap mmap/Aether directories, or infer that a local root argument selects
the Java daemon's database directory.

The context is not a locking wrapper for concurrent mutation. Worker integration
has its own ownership/merge path. Counter updates and reporting do not establish
thread safety, a durability barrier, or a consistent snapshot of another process.

## Construction, Reset, and Reuse Methods

| Function | Behavior and boundaries |
| --- | --- |
| `BackendContext.__init__(self, args, reference, sources, np)` | Stores inputs and chooses external Aether root or mkdtemp root. External fresh mode deletes/recreates its directory; opens the artifact store before mmap setup. Chooses external mmap root or a subdirectory, deletes external fresh mmap root, and opens PersistentMmapStore with durable flag. Initializes legacy static handles/offsets, RAM data, population times and logical counters; computes reference checksums, builds mmap/RAM state and prepopulates Aether. No all-or-nothing cleanup if a later stage fails. |
| `BackendContext._open_store(self, root)` | Java path creates JavaArtifactStore using configured port, namespace and optional trace, then checks engine equals java-training-cache and reported durability equals requested uppercase value. Mismatch closes client and raises ValueError; successful info is retained. Does not launch/stop the daemon or pass root to it. Python path opens AetherMLStore(root, code_commit="gpu-training"); that label is not an actual source-commit measurement. |
| `BackendContext.reset_aether_cache(self)` | Java resets measured counters only: no namespace deletion or reinitialization. External Python reopens the existing directory without removing its contents, then resets/prepopulates. Temporary Python removes store.root with ignored errors, reopens its aether subdirectory and resets/prepopulates. Does not reset mmap or RAM. Does not explicitly close the replaced Python store. The name does not imply every engine becomes cold. |
| `BackendContext.reset_measured_counters(self)` | Replaces known logical counts/timers and write-batch list with initial zeros and connectionsOpened=1, then asks store to reset operation metrics. Does not clear stored data, initial reusable indices, population times, mmap metrics, worker observations or the entire protocol mapping. Connection count is a logical benchmark field, not a fresh transport probe. |
| `BackendContext._build_static(self)` | Clears mmap in fresh mode; reuse scans each current key and records found offsets. Records zero population time for reuse, otherwise the clear/setup elapsed time before initial prepopulation. Fresh mode seeds the first round(samples * target ratio / 100) references as packed payloads. Discovers actual initial mmap indices with contains, initializes logical mmap counts and resets store metrics. Initial seed writes are outside the previously captured populate_ms and measured store metrics. |
| `BackendContext._build_ram(self)` | If RAM_READY is selected, packs every reference into a byte list and times this work; otherwise leaves ram_ready None. Does not create ready torch tensors, pin memory or eliminate decode/stack costs on subsequent RAM batches. |
| `BackendContext._prepopulate_aether_cache(self)` | Java fresh mode rejects any current requested key already present, but does not inspect unrelated namespace keys. External/Java reuse discovers actual indices and reports their ratio and zero population time without writing. Otherwise chooses a rounded prefix count, handles zero without publication, or commits packed reference entries together with deterministic metadata and resets operation metrics. Sets initial indices before the commit, so a thrown commit does not produce a trustworthy completed context. This is ordinary commit_bytes_many, not the separate offline bulk loader. |
| `BackendContext._existing_cache_indices(self)` | Builds keys for every source, queries cached_artifact_ids, then maps returned membership back to index positions. Does not decode/compare payloads against references or require the store's returned identifiers to be unique. Identity discovery is work even when reported reuse population time is zero. |
| `BackendContext._existing_cache_entries(self)` | Returns the cardinality of _existing_cache_indices, triggering a new store query rather than reading an already captured count. |
| `BackendContext._target_initial_hit_ratio(self)` | External/Java reuse takes actual existing-entry fraction over max(source count,1), overriding explicit ratio/previous-version simulation. Otherwise a positive initial_cache_hit_ratio wins, then prepopulate_previous_version returns max(0,100-changed_percent), else zero. No independent argument-range validation; normal callers validate args. |

Simulated prefix reuse and discovered identity-aware reuse are distinct. Prefix
rounding uses Python round, and counts use args.samples while discovery iterates
sources. Normal validated callers must keep requested sample/reference/source
lengths consistent. A Java fresh check examines only current keys, not proof that
the entire database is empty. Both initial-store index sets are later compared by
the run controller; this constructor does not itself prove backend equivalence.

## Batch and Identity Methods

| Function | Behavior and boundaries |
| --- | --- |
| `BackendContext.batch(self, backend, indices)` | Dispatches exactly four backend labels to their helpers and raises ValueError for an unknown label. Does not check index uniqueness/range or make the four branches atomic. |
| `BackendContext._raw_batch(self, indices)` | Preprocesses each source, accumulates source/preprocess counters, performs artifact pack/unpack quantization through artifact_to_tensor_sample, and stacks in caller order. artifactDecodeMs includes that round trip, not just decoding. Does not publish a cache entry or reuse a prepared reference. |
| `BackendContext._aether_batch(self, indices)` | Derives keys and uses Java cached views or Python cached bytes in one logical lookup; accounts returned dictionary payloads/hits/misses and lookup time. In requested order decodes hits or prepares/quantizes misses, then commits all misses in one call, summing returned metadata sizes and logical batch counts. Returns stacked arrays, phase counters and optional new request traces. Miss packing/metadata construction occurs inside publish timing; an empty publication still records tiny publish elapsed time. Does not release/rollback prior cache updates on decode/commit failure. |
| `BackendContext._static_batch(self, indices)` | Looks up every requested key and counts hit/miss outcomes; prepares/quantizes/packs misses and appends them together. Reads each requested record, rejects a missing record, strips its four-byte frame and unpacks/stacks in original order. Reports read/append metric deltas and source/preprocess/decode timings; mmap lookup time stays in mmap_protocol, and tensorBuildMs is zero rather than a measured stack time. Training steps do not automatically expose every returned field such as mmapPublishMs. |
| `BackendContext._ram_batch(self, indices)` | Unpacks ram_ready payloads and stacks them; reports the entire work as tensorBuildMs. It is not zero-copy ready-tensor access. Calling when RAM data was not built fails naturally. |
| `BackendContext.cache_key(self, index)` | SHA-256 hashes sorted JSON of sample ID, source identity, declared source_hash (or SHA-256 of raw bytes when absent), and deterministic_parameters(args). Changes in deterministic parameters change the key; random training augmentation does not. Does not read files to verify an already declared source hash. The descriptor and metadata conventions are specific to this benchmark. |

Lookup results are dictionaries keyed by identity, while requested indices are a
sequence. Duplicate requested keys can therefore distort logical hit/miss counts
or create duplicate pending entries; ordinary scheduled batches use unique indices.
Batch counter fields describe benchmark operations, not every RPC or physical I/O.
Java views are passed into unpack_payload, which returns float32 arrays; this
training path is not an end-to-end zero-copy GPU pipeline.

## Reporting and Cleanup Methods

| Function | Behavior and boundaries |
| --- | --- |
| `BackendContext.populate_ms(self, backend)` | Returns recorded population time or zero for an absent backend. A zero may mean reuse accounting/excluded work, not that no startup/discovery/seed work occurred. |
| `BackendContext.protocol_counters(self)` | Makes a shallow copy of logical counters, adds write-batch mean, aliases for hits/misses/bytes, initial reuse fields and scope labels. Java merges parent transport metrics with worker connection/request/operation counts; walForceCount is None for Java and zero for Python, not measured WAL forces. Nested lists are not deep copies. Does not reset counters or independently validate them. |
| `BackendContext.aether_operation_metrics(self)` | Python delegates directly. Java obtains observations, extends them with worker observations and passes the merged mapping to store.operation_metrics. Merge mutates the returned observation lists; snapshot ownership comes from the adapter. Does not drain background work or force durability. |
| `BackendContext.mmap_dynamics(self)` | Combines logical mmap counters with store metrics, initial index counts and read-path notes. Computes expected counts from the configured measured schedule and requires exact lookup/hit/miss matches plus entriesAppended == expected misses. Reports per-mapping faults unavailable. Population-only or differently scoped counter windows need not satisfy this training-schedule invariant. |
| `BackendContext.peak_memory_bytes(self)` | Calls torch.cuda.max_memory_allocated on the current/default CUDA device, returning zero on ordinary exceptions. This is accelerator allocator memory, not Python/mmap/native RSS, total VRAM, all selected devices or proof of zero use after failure. |
| `BackendContext.close(self)` | Closes mmap store, then Java client if selected, then non-null legacy static mmap/file handles. Removes the context directory only when Aether root is temporary, ignoring deletion errors. An external mmap root remains if outside that directory. Does not stop the daemon, delete external Aether data or guarantee all later closes run after an earlier close raises; no nested finally here. |

## Explicit Population and Warmup Functions

| Function | Behavior and boundaries |
| --- | --- |
| `BackendContext.populate_mmap_dataset(self)` | Iterates one batch_plan pass, looking up each source key and preparing only missing payloads. Publishes pending batches, updates offsets and accumulates local hits/misses, elapsed lookup/append time and framed payload bytes (payload plus four). Reports total store index size, which may include unrelated historical keys. Initial reusable count comes from static_offsets. Does not decode all existing hits for equivalence, train a model or reset/update the separate mmap_protocol training counts. |
| `BackendContext.populate_aether_dataset(self)` | Iterates one batch_plan pass through ordinary Aether batch lookup/miss preparation/publication, including decode/stack work whose tensors are discarded. Uses deltas for hits/misses/published entries/bytes, but lookupMs and publishMs are cumulative protocol totals, not deltas from call start. Reports captured initial reuse fields and current call wall. Does not invoke offline bulk import or train a model. |
| `aether_cold_start_gate(context)` | Looks up keys for range(args.samples), compares present count with recorded initial prepopulation. Zero target requires both counts zero; nonzero target only requires equality with prepopulated count. Returns a passed flag/report rather than raising. Does not compare exact present index sets, payloads, requested percentage or unrelated namespace keys. |
| `warm_backend(context, backend)` | RAM_READY fetches/decodes one prefix batch to warm that path; other backends do nothing. Does not populate or warm Aether/mmap and does not execute model warmup. |

## Verification and Remaining Coverage

Qualified AST checks cover every declared BackendContext method plus the two
helpers. Tiny actual Python artifact-store/mmap fixtures exercise cold publication,
warm reuse, reconstruction, persistence, identity changes and cleanup. Injected
Java adapters exercise engine/durability rejection and logical-versus-transport
counters without connecting to a live daemon. None is a GPU performance or
power-loss experiment, and this documentation does not alter the frozen campaign.

The function index links all partitions, jointly covering **138 of 138 explicit
benchmark declarations**. This page covers ownership/population, not every
benchmark contract. Complete function entries are not repository-wide completion
or a correctness score.
