# SSTable Construction And Tracing

[Block functions](SSTABLE-BLOCK-FUNCTIONS.md) | [Reads and verification](SSTABLE-READ-FUNCTIONS.md)

[Manifest publication and versions](MANIFEST-VERSION-FUNCTIONS.md) follows
inventory validation, durable edits and recovery after construction.

This reference describes the local development implementation, including the
empty-store deferred-verification bridge. Published `main` may not yet contain
all these functions.

## Construction Is Not Publication

`SSTableBuilder` accumulates owned entries, constructs a complete in-memory table
image, creates a new file and forces it. Normal `finish` then opens a fully
verifying reader and closes it. The empty-store bulk path defers that verification
to the version-set inventory boundary. Neither path commits a manifest itself.

The builder has three states: `OPEN`, `FINISHED` and `ABANDONED`. `FINISHED` means
the finish attempt has consumed the builder, not that every later write, force
or verification step succeeded. A failed finish is not automatically retryable
on the same builder and does not automatically remove a partial output file.
The higher-level owner handles temporary-file cleanup and publication safety.

There are three distinct sizes worth keeping separate:

- The v1 DATA payload target is 16,384 bytes, a soft partitioning target.
- A raw DATA block may not exceed 32 MiB in this builder.
- A whole-SSTable target, such as the research configuration's 32 MiB, belongs to the higher-level population/compaction policy. This builder does not enforce a total-file target.

## Builder Public Functions

Source: [SSTableBuilder.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableBuilder.java).

### `SSTableBuilder(path, fileNumber, databaseId, creationEpochMillis)`

Requires non-null path/identity and a positive file number, then records diagnostic
creation time. It does not create the file, create parent directories or check
path existence yet. Exclusive creation occurs during finish.

### `add(key, value)`

Requires `OPEN`, non-null key/value and an empty payload for type-2 tombstones.
It encodes the immutable internal key, compares against the last encoded key
using semantic internal ordering, and rejects equality or decreasing order.
The builder retains the new encoded key and a clone of the value in private
`Entry` records. There is no sorting, deduplication or application-record decoding.

All accepted values remain retained until the builder is collected; `add` is not
a streaming file write. A rejected add leaves previously accepted entries intact.

### `finish()` And `finish(trace)`

The no-argument overload delegates with no trace. The traced overload delegates
to `finishInternal(trace, true)`, requesting immediate reader verification after
writing. Both return `TableFileMetadata` only after the requested finish succeeds.
The trace changes instrumentation, not the bytes or verification policy.

### `finishForBulkInstall(trace)`

Package-private bridge to `finishInternal(trace, false)`. It writes and forces
identical format bytes but does not create a verifying reader. It is not the
application-facing way to skip integrity checks; the cross-module bridge below
restricts the intended caller to an empty-store installation workflow.

### `abandon()` And `requireOpen()`

`abandon` changes only an open builder to `ABANDONED`. It performs no file I/O,
deletion or explicit clearing of retained entry arrays. Calling it after a finish
attempt does not revert state or clean up an output file. Private `requireOpen`
rejects operations once either terminal state has been reached.

## Finish Control Flow

### `finishInternal(trace, verifyImmediately)`

Starts a non-null trace with the current entry count, calls `finishBody`, and
records completion in `finally`. Only a normal return from the body marks success.
This wrapper does not translate exceptions, delete files or reset builder state.

### `finishBody(trace, verifyImmediately)`

Requires an open, nonempty builder, then sets `FINISHED` **before** partitioning
or file creation. The rest of the pipeline is:

1. Partition DATA entries, reserve a zero-filled 4 KiB header region and append encoded/checksummed DATA blocks.
2. Collect distinct user keys and build the full-file Bloom block.
3. Compute entry/byte/sequence metrics and encode placeholder properties with file size zero.
4. Plan properties, metaindex, index and footer offsets. The file-size property is fixed-width, so replacing zero with final size preserves encoded length.
5. Append final properties, metaindex and index; require the accumulated body size to match the planned footer offset.
6. Append the footer, copy the complete body into a new table array and overlay the 128-byte header at its start. The remaining reserved region stays zero.
7. Open the path with `CREATE_NEW` and `WRITE`, write the full array in a loop, call `force(true)` and close through try-with-resources.
8. Construct metadata. If immediate verification was requested, open `SSTableReader` against it, run the open path's full verification and close the reader. Return metadata.

This holds a table-sized output stream and then a table-sized copied array,
in addition to retained input values and intermediate block buffers. Streaming
verification does not make this builder itself a streaming writer.

An existing output path fails exclusive creation instead of being overwritten.
Write/force/close failures propagate. A newly created partial file can remain;
caller-owned temporary-file handling is therefore part of the surrounding
durability protocol. A successful file force alone is not manifest authorization.

## DATA Partition And Encoding Helpers

### `partitionDataBlocks()`

Tracks encoded body bytes, restart count and the previous key within each block.
For each entry it computes the exact body contribution plus restart offsets and
the count suffix. If adding it would exceed the 16 KiB target and the current
block is nonempty, that block is finalized and the entry starts a new block with
zero shared prefix. A single large entry may exceed the target; any candidate
raw payload above 32 MiB is rejected.

The immutable partition lists share private owning `Entry` objects; payloads
are not cloned during list partitioning. This algorithm partitions at entry
boundaries and **does not keep all versions of one user key together**. Callers
must not assume that merely using the builder satisfies a reader's one-block
lookup assumptions. Empty normal input is rejected before this helper is called.

### `encodeDataBlock(entries)` And `writeBlock(...)`

`encodeDataBlock` feeds owned arrays directly into a restart encoder with interval
16 and internal-key ordering, avoiding per-entry adapter records. `writeBlock`
envelopes/checksums a raw payload, constructs a physical handle at the current
output position, appends physical bytes and returns a `BlockDescription` with
that handle and optional last key. Nested trace stage `blockBufferAppend` is
restored in `finally`; it does not write to the disk channel itself.

### `index(blocks)` And `metaindex(filter, properties)`

`index` uses each DATA block's actual last encoded key and encoded handle as a
restart entry. It encodes with interval one and internal ordering, so each index
key is independently reconstructible. Metadata entry construction copies those
small arrays. `metaindex` emits two bytewise-sorted names pointing to Bloom and
properties blocks, also at restart interval one.

## Properties And Accounting Helpers

### `properties(metrics, fileSize)`

Builds a sorted map of comparator, file number/size, counts, raw byte totals,
sequence/key bounds, filter/compression policy and diagnostic creation time.
Numeric values are fixed-width little-endian arrays, names/literals are ASCII.
The sorted entries become a restart block with interval one. Key bounds come
from the first and last accumulated entry, not from a separately sorted copy.

### `distinctUserKeys()`

Walks already ordered internal keys and copies only the user portion when it
differs from the preceding distinct key. Consecutive versions contribute one
Bloom key. It avoids allocating an `InternalKey` object for each comparison,
but still allocates user-key arrays; Bloom building also takes owning copies.

### `metrics(dataBlockCount)` And `metadata(metrics, blocks, size)`

`metrics` reads each encoded sequence, computes extrema and sums raw internal-key
and value lengths with `Math.addExact`. It returns a private `Metrics` record;
it does not measure physical bytes or compression savings. `metadata` combines
those observations, file identity, first/last encoded keys and final physical
size into a defensively owning `TableFileMetadata`.

### Scalar Encoding And Private Records

`ascii(value)` encodes US-ASCII. `littleLong(value)` and `littleInt(value)` allocate
eight/four bytes and write little-endian scalars. These are metadata encoders,
not variable-length codecs.

Private `Entry(key, value)` and `BlockDescription(handle, largestKey)` records
retain their supplied references without additional constructor cloning; ownership
is established by the enclosing builder. `Metrics` carries sequence extrema,
raw byte totals and block count. Generated record accessors do not perform I/O.

## Deferred Verification Bridge

Source: [BulkInstallSupport.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/BulkInstallSupport.java).

Private `BulkInstallSupport()` prevents instantiation.
`finishUnpublished(versions, builder, trace)` reads the current version and requires
an empty file inventory, last-assigned sequence zero and persisted sequence
watermark zero. It then invokes the builder's package-private deferred finish.

This check establishes eligibility at the moment it reads `current()`; the helper
does not itself acquire an installation lock, prove there are no competing writers,
verify the resulting table or commit a manifest. `EmptyStoreBulkLoader` owns the
surrounding workflow. The unpublished output must pass inventory verification
before installation; removing immediate verification is not permission to publish
unchecked bytes.

## Builder Stage Trace

Source: [SSTableFinishTrace.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableFinishTrace.java).

### Constructors, `start(entries)` And `enter(trace, stage)`

The default constructor delegates with bulk JFR markers disabled. The boolean
constructor stores whether coarse bulk phase events should be attempted.
Package-private `start` records entry count and sets initial timing timestamps.
The object is intended for one finish attempt; no guard enforces single use or
thread confinement, and start does not clear accumulated stages.

Static `enter` is a no-op returning null when no trace is supplied. Otherwise it
closes the active coarse event, optionally begins a mapped event, attributes time
since the preceding switch to the previous active stage, and returns that previous
stage name for nested restoration. Durations are exclusive stage totals, merged
when a stage is revisited, not a hierarchy of independent additive timings.

Only `fileForce` maps to `SSTABLE_FORCE` and `verificationOpenAndRead` maps to
`SSTABLE_VERIFY` here. Other stage switches end a coarse event but create none.
These markers are available only when `BulkPhaseEvent` is enabled.

### `fileBytes`, `finish` And Accessors

Package-private `fileBytes(bytes)` sets planned physical bytes for trace metadata.
`finish(success)` switches to `other` to close the last measured stage/event,
captures total elapsed time and records success. It is called on both normal and
exceptional finish exits. `stagesNs()` returns an immutable map snapshot;
`totalNs()`, `completed()`, `entryCount()` and public `fileBytes()` return scalars.
These accessors do not write reports or reconcile stage time with campaign time.

## Completed-Verification Trace

Source: [SSTableVerificationTrace.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableVerificationTrace.java).

Private construction captures the owner thread. `begin()` installs a new
thread-local scope and rejects nesting on that thread. Verification on a different
worker thread is not implicitly included.

- `verified(fileBytes)` increments completed full-table count and adds the full physical size with checked addition when a scope is active. Calls without a scope are no-ops.
- `streamed(fileBytes, result)` calls `verified` and also accumulates streaming table/entry/value/read-byte/time/block observations. These extra sums use ordinary long addition, not checked arithmetic.
- `inventoryCall()` increments a separate boundary-call counter. It counts calls, not necessarily successful inventory completions.
- `snapshot()` requires the owner and returns immutable `inventoryCalls`, `tablesFullyVerified` and `bytesFullyVerified` fields. Physical table size is not actual requested-read accounting.
- `streamingSnapshot()` requires the owner and returns the separate `streaming-v1` schema with completed streaming tables, entries, logical value bytes, requested read bytes, elapsed time and block count.
- `requireOwner()` rejects cross-thread inspection/close. `close()` removes the active thread-local scope once and marks the trace closed. Owner-thread snapshots remain accessible afterward; they do not require an open scope.

Neither trace is a replacement for correctness verification. A failed table check
does not produce a completed-table observation simply because the file exists.

## Coarse JFR Events

Source: [BulkPhaseEvent.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/jfr/BulkPhaseEvent.java).

`start(phase, records, bytes, tableIndex)` returns null when the class-load-time
`aether.bulk.jfr` system-property flag is false. Otherwise it creates an event,
fills fields and begins timing. The JVM recording settings still determine
whether that custom event is recorded. `close()` ends and commits the event;
it has no explicit idempotence guard. The event disables stack traces and exposes
coarse phase metadata, not per-allocation stacks or full table verification proof.

## Regression Entry Points

[SSTableFormatV1Test](../../modules/aether-sstable/src/test/java/io/aetherdb/sstable/SSTableFormatV1Test.java)
is the format/reader starting point;
[SSTableVerificationTraceTest](../../modules/aether-sstable/src/test/java/io/aetherdb/sstable/SSTableVerificationTraceTest.java)
checks instrumentation scopes. Function-name coverage checks omissions from this
reference; it does not assert that the prose or timing model has been behaviorally
proven by a benchmark.
