# Embedded Typed Adapter Functions

[Function index](FUNCTION-INDEX.md) | [Typed keys](TYPED-KEY-FUNCTIONS.md) | [Typed values](TYPED-VALUE-FUNCTIONS.md) | [Collection metadata](COLLECTION-SCHEMA-FUNCTIONS.md)

This reference follows the current checkout's typed facade into the byte engine.
It separates interface promises from the behavior of the embedded implementation.

## Sources and Runtime Boundary

- [AetherEmbedded](../../modules/aether-embedded-typed/src/main/java/io/aetherdb/embedded/typed/AetherEmbedded.java): supported owning factories.
- [EmbeddedTypedDatabase](../../modules/aether-embedded-typed/src/main/java/io/aetherdb/embedded/typed/EmbeddedTypedDatabase.java): database, collection, batch, and snapshot adapters.
- [TypedAetherDatabase](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedAetherDatabase.java), [TypedAetherCollection](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedAetherCollection.java), [TypedAetherSnapshot](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedAetherSnapshot.java), and [TypedWriteBatch](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedWriteBatch.java): public contracts.
- [ReadResult](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/ReadResult.java), [TypedWriteResult](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedWriteResult.java), and [TypedKeyValue](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedKeyValue.java): decoded outcomes.

Logical keys become collection-prefixed byte keys. Values become schema envelopes.
The adapter delegates persistence, sequence assignment, snapshot visibility, and
atomic raw-batch submission to `AetherDatabase`; it is not an RPC client or another
storage engine. Metadata registration may itself write a reserved raw key before
application writes begin.

## Factories and Database Lifecycle

### AetherEmbedded.openInMemory(), open(directory), adapt(database)

`openInMemory()` wraps `Aether.openInMemory()`; `open()` wraps the persistent factory.
Both call `adapt()`, which constructs `EmbeddedTypedDatabase(database, true)`.
Adapting transfers close ownership: closing this facade closes the supplied raw
database. There is no public non-owning factory. The private `AetherEmbedded`
constructor prevents instances; the adapter constructor requires a non-null raw
database and stores the ownership flag.

### collection(definition) and defineCollection overloads

`collection()` checks open state, registers the definition, and returns a new live
`Handle`. The interface's name-only `defineCollection()` derives `CollectionId`
from the name, so a rename changes identity. The explicit-ID overload resolves
built-in key codecs and `GeneratedCodecs.forRecord(valueType)`, creates a definition
with point read/write, range scan, and snapshot capabilities, then calls `collection()`.
It does not dynamically generate a value codec or derive an ID from the Java type.

### batch(), write(candidate), snapshot(), isClosed(), close()

`batch()` returns a new owned `Batch`. `write()` accepts only this implementation's
batch belonging to this adapter, then submits it. `snapshot()` creates `Snap`
around `raw.newSnapshot()`. `isClosed()` reports the adapter flag, not independently
the raw database's state. `close()` closes raw storage only when owning and not
already closed, then sets the flag; if raw close throws, that assignment is skipped.

These top-level methods are synchronized. Collection handles, batch mutations,
and snapshot methods are not synchronized on the adapter. That distinction matters:
the facade is not a blanket guarantee that all registration, submission, and close
races are serialized. External direct use of an adapted raw database is also outside
these synchronized methods.

## Metadata Registration Helpers

### register(definition)

Checks a previously registered definition's key codec ID/version/fingerprint and
value schema UUID. Equal value versions also require equal value fingerprints.
If the prior version is at least the requested version, registration returns early;
the caller still receives a handle with its requested definition.

Otherwise obtains any installed generated descriptor, creates `CollectionMetadata`,
and reads its reserved raw key. Existing metadata must have the same family:

- Equal schema version requires an equal schema fingerprint and sets the minimum writer version.
- A newer requested version must pass `requireCompatibleUpgradeTo`, then persists new metadata and raises the minimum writer version.
- An older requested version leaves stored metadata intact and records the stored version as the minimum writer version.
- Missing metadata is written and establishes the requested minimum writer version.

Finally saves the requested definition in the local definitions map. This is not
a transaction combining metadata and later user writes, not a rewrite of old
values, and not a compare-and-swap against concurrent raw metadata writers. Cached
early returns do not re-read disk. See the metadata reference for family and
descriptor compatibility rules.

### ensureWriterAllowed(definition) and ensureOpen()

`ensureWriterAllowed()` rejects a definition's writer version below the locally
recorded minimum with `OLDER_WRITER_REJECTED`. It does not migrate values or check
capability flags. `ensureOpen()` throws when the adapter's closed flag is true;
it neither checks raw state nor acquires a lock itself.

## Collection Handle Functions

`Handle(owner, definition, snapshot, readOnly)` retains references without copying
the definition/codecs or acquiring a new snapshot. `definition()` returns that
definition without checking lifecycle state.

### get(key)

Checks adapter open state, encodes the physical key, and performs a live or snapshot
raw lookup. A found value is decoded through `TypedValueEnvelope` and wrapped in
`ReadResult.Found`; absence becomes `NotFound`. Codec/envelope failures propagate.
This method does not separately enforce `POINT_READ` or `SNAPSHOT_READ` flags.

### put(key, value) and delete(key)

Call `writable()`, create a one-operation batch, encode immediately through its
put/delete method, and submit directly. They do not route through synchronized
`EmbeddedTypedDatabase.write()`. `writable()` checks adapter state, rejects
snapshot-bound handles, and checks the minimum writer version, but does not enforce
`POINT_WRITE` capability independently.

### scanAll()

Checks adapter state and `RANGE_SCAN`, scans the collection prefix through its
exclusive end with the optional raw snapshot, decodes every key/value, closes the
cursor with try-with-resources, and returns `List.copyOf`. It fully materializes
the collection; there is no public streaming typed cursor or pagination here.
Ordering is raw physical key order, with logical ordering dependent on the key
codec's encoding. A decoding failure stops materialization and still closes the
cursor. The immutable list does not deep-freeze decoded objects.

## Batch Functions and Outcomes

`Batch(owner)` creates a raw `WriteBatch` and an initially false submitted flag.
`put(collection, key, value)` checks handle ownership and writer version, encodes
key/value, and appends raw put. `delete()` similarly encodes key and appends raw
delete. Encoding happens when operations are added, not on submission.
`operationCount()` delegates to raw batch even after submission.

Private `handle(collection)` rejects an already submitted batch and any collection
that is foreign, not an embedded handle, or snapshot-bound. It does not call
`ensureOpen()`; batch mutation alone is not a database-lifecycle check. Earlier
encoded operations are not individually rechecked for writer version at submission.

`submit()` enforces one attempt, sets submitted before calling storage, and assigns
a fresh random command UUID. Empty returns `Rejected(command, "EMPTY_BATCH", false)`.
Nonempty calls `raw.write(rawBatch, WriteOptions.defaults())` and maps successful
operation count and sequence range to `Applied`. Raw exceptions propagate, and
the batch remains submitted. This path does not manufacture `Indeterminate`, wrap
arbitrary failures into `Rejected`, or expose the raw durability-barrier metadata.

The random command UUID is outcome metadata here: it is not passed to raw storage,
persisted for deduplication, or accepted back as a retry identity. Do not infer an
idempotent retry protocol from the public result interface's wording.

## Snapshot Functions

`Snap(owner, rawSnapshot)` retains the captured raw snapshot and a local closed
flag. `collection(definition)` rejects a closed Snap, calls `owner.register()`,
and returns a read-only handle using that raw snapshot. Registration reads/writes
live metadata even though subsequent collection reads use the earlier snapshot.
It is not a read-only metadata operation or a catalog captured at snapshot time.
This method does not call adapter `ensureOpen()` or acquire its synchronization.

`Snap.isClosed()` returns its own flag. `close()` closes the raw snapshot once and
then sets the flag; if raw close fails the flag remains false. Existing collection
handles retain the raw snapshot reference and rely on raw snapshot validation when
reading after its closure, not a direct check of `Snap.closed`.

## Public Result Functions

`ReadResult.value()` is implemented by `Found.value()` as `Optional.of(found)` and
by `NotFound.value()` as empty. `requireValue()` throws `NoSuchElementException`
on absence. `Found(found)` rejects null but does not copy/freeze a logical value.
`NotFound()` is a record with no payload.

`TypedKeyValue(key, value)` rejects null components; its record accessors return
the original decoded objects. Record immutability does not imply deep immutability.

`TypedWriteResult.commandId()` is supplied by the records `Applied`, `Rejected`,
and `Indeterminate`. Their implicit constructors/accessors expose respectively
count/sequence range, reason/retryable, and stage/retry instructions. They have no
explicit validation of null UUID/text, counts, or sequence ranges. Their existence
describes the public outcome model, not which variants every adapter produces.

## Coverage Boundary

This page covers both embedded implementation files and the seven linked public
contracts/results, including nested Handle/Batch/Snap methods and private helpers.
Metadata, envelope, and generated-codec details are linked rather than repeated.
Annotation processing, remote typed behavior, and broader filesystem/ML/distributed
implementation references remain outstanding. No runtime changes accompany this
documentation of lifecycle and concurrency limitations.
