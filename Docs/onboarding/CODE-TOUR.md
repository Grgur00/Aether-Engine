# A Guided Code Tour

[Onboarding index](README.md) | [Module guide](MODULE-GUIDE.md) | [Architecture](ARCHITECTURE.md)

Read this after [Getting started](GETTING-STARTED.md). The goal is to navigate an
ordinary application request, locate its implementation and choose a regression
test before changing code. You do not need Python, a dataset or a remote service
for the first four routes.

## 1. From An Application To The Database

Open these files in order:

1. [SocialNetworkApplication](../../examples/aether-sample-app/src/main/java/io/aetherdb/examples/social/SocialNetworkApplication.java): chooses the in-memory or persistent database and runs the demonstration.
2. [SocialNetworkRepository](../../examples/aether-sample-app/src/main/java/io/aetherdb/examples/social/SocialNetworkRepository.java): defines collections and implements domain CRUD, joins and relationship checks.
3. [AetherEmbedded](../../modules/aether-embedded-typed/src/main/java/io/aetherdb/embedded/typed/AetherEmbedded.java): creates or adapts a byte database, transferring close ownership to the typed wrapper.
4. [EmbeddedTypedDatabase](../../modules/aether-embedded-typed/src/main/java/io/aetherdb/embedded/typed/EmbeddedTypedDatabase.java): collection metadata, key/value encoding and typed-to-byte adaptation.
5. [AetherDatabase](../../modules/aether-api/src/main/java/io/aetherdb/api/AetherDatabase.java): the contract implemented by both byte engines.
6. [Aether](../../modules/aether-engine/src/main/java/io/aetherdb/engine/Aether.java): the composition root selecting the actual implementation.

Trace `createProfile` and `findProfile`. Notice where a Java record becomes bytes,
where a collection's identity is added, and where the returned bytes become a
record again. Generated codecs come from annotations and schema locks, not
reflection over arbitrary objects. See [Typed API and schemas](TYPED-API-AND-SCHEMAS.md).

Then trace `follow`: two related mutations are placed in one typed batch. The
repository's preceding lookups are application checks, not a serializable
transaction or database-native foreign-key constraint. Do not infer concurrency
guarantees from a single-threaded sample.

**Checkpoint:** explain which object owns the database and why a collection name,
schema UUID and user key are three different identities.

## 2. One Online Write

Start at `put` in
[PersistentAetherDatabase](../../modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentAetherDatabase.java).
It constructs a `WriteBatch`, then calls `write`. Read the inner
`CommitCoordinator.submit` and `processCommitGroup` next. The persistent class is
package-private: application code should use `Aether`, not construct it directly.

```text
put / delete / explicit batch
  -> WriteBatch owns copies of caller bytes
  -> CommitCoordinator gathers requests on a caller thread
  -> processCommitGroup seals and admits each batch
  -> assign sequence range
  -> encode logical WAL group and physical fragments
  -> mark submitted; append WAL
  -> apply mutations to active native memtable
  -> advance visible sequence
  -> requested WAL barrier; complete write outcome
```

Read these boundary implementations alongside the coordinator:

- [WriteBatch](../../modules/aether-api/src/main/java/io/aetherdb/api/WriteBatch.java): byte limits, copied inputs and one-shot submission state.
- [WalLogicalGroupCodec](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalLogicalGroupCodec.java): batch/sequence encoding.
- [WalFragmentCodec](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalFragmentCodec.java): physical framing and validation.
- [NativeSkipListMemTable](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/skiplist/NativeSkipListMemTable.java): ordered versioned records and native ownership.

Public reads and group processing share the database monitor; intermediate
mutation application is not a public partial-batch view. Submission/force failure
can yield an indeterminate outcome and fence writes. An exception is not proof
that no bytes were written. Admission can trigger synchronous flush before the
new batch proceeds. The complete ordering and current durability distinctions
are in [Storage engine](STORAGE-ENGINE.md#3-online-write-path).

**Checkpoint:** locate the line that marks the batch submitted, the WAL append,
the visibility update and the force. Explain failures on both sides of submission.

## 3. A Read, Snapshot And Delete

Follow `get` into `lookup`, then follow `newSnapshot` and `validateSnapshot` in
the same persistent class. Compare with
[InMemoryAetherDatabase](../../modules/aether-engine/src/main/java/io/aetherdb/engine/InMemoryAetherDatabase.java)
to separate semantic rules from file mechanics.

A read selects a visible record using a sequence boundary. A snapshot retains an
older boundary and belongs to one database instance. A deletion is a versioned
tombstone, not permission to erase every older record immediately. Compaction
must preserve records needed by retained snapshots.

Follow a table lookup into
[SSTableReader](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableReader.java)
and read [InternalKey](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/InternalKey.java).
User keys and internal keys have different ordering jobs. Check absence versus
tombstone, and the lifetime/copy contract of
[LookupResult](../../modules/aether-api/src/main/java/io/aetherdb/api/result/LookupResult.java).
Persistent scan cursors own materialized rows; they are not streaming iterators
pinning arbitrary table files.

**Checkpoint:** predict current and snapshot results after put, overwrite and
delete, then find the assertions in
[InMemoryAetherDatabaseTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/InMemoryAetherDatabaseTest.java).

## 4. Flush, Publication And Reopen

Read `flushActiveBody`, `open`, `recoverWal` and `close` in the persistent class.
Use these collaborators to understand what survives a process exit:

- [SSTableBuilder](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableBuilder.java): creates sorted immutable files.
- [VersionSet](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/VersionSet.java): validates and publishes the authoritative inventory.
- [SSTableVerifier](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableVerifier.java): structural/checksum verification without materializing DATA values.
- [CompactionCoordinator](../../modules/aether-engine/src/main/java/io/aetherdb/engine/CompactionCoordinator.java): schedules the single background worker.
- [CompactionDroppingIterator](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionDroppingIterator.java): version/tombstone retention rules.

A file existing in the directory is not enough to make it authoritative. Manifest
publication and the required WAL determine reopen behavior. Compaction builds
outside the database monitor, then rechecks inputs before installation. Removing
that recheck can lose a concurrent flush even when a single-threaded test passes.

Read `valuesAndDeletesSurviveMultipleReopens`,
`walCrashPointAfterForceBeforeAckMakesBatchOutcomeIndeterminate` and
`corruptReferencedSstableIsFatal` in
[PersistentAetherDatabaseTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/PersistentAetherDatabaseTest.java).
Then read [BackgroundCompactionTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/BackgroundCompactionTest.java).
Crash tests establish their injected failure semantics, not every hardware
power-loss scenario.

**Checkpoint:** explain why recovery must not initialize a fresh empty database
over corrupt authoritative metadata, and when obsolete inputs can be removed.

## 5. The Optional Python Route

Read [AetherTransformCache](../../clients/python/aether_ml/transform_cache.py),
[artifact identity](../../clients/python/aether_ml/identity.py), the
[Python wire client](../../clients/python/aether_training_cache/client.py),
[TrainingCacheDaemon](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheDaemon.java)
and [TrainingCacheProtocol](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheProtocol.java).
Follow one `get_many_or_compute` call through key derivation, batch lookup,
miss-only transformation, codec encoding and publication. The transform stays
Python-side; Java stores immutable artifacts rather than executing a model.

This protocol is different from the general RPC/client modules. Offline bulk
ingestion is also different from an ordinary online write. Use
[Training cache and Python](TRAINING-CACHE-AND-PYTHON.md) for identities,
multiprocessing, packed-buffer lifetime and supported failure behavior.

**Checkpoint:** explain why changing source content or transform parameters must
change the artifact key, while appending unrelated samples must not invalidate
old artifacts. Find the corresponding tests in
[test_aether_ml.py](../../clients/python/tests/test_aether_ml.py).

## Choose A First Contribution

| Task shape | Where to work | Verification |
| --- | --- | --- |
| Add a sample-domain behavior | Social repository and its tests | Sample tests; explain application-level concurrency limits |
| Cover a byte ownership or snapshot edge case | API/engine tests, then owning implementation if needed | In-memory semantic tests; persistent counterpart for a persistence claim |
| Improve malformed binary-input rejection | Owning WAL/SSTable codec and test | Valid fixtures, corruption/truncation and affected reopen behavior |
| Fix a cache identity regression | Python identity/transform adapter | Stable append keys, changed-content misses, caller ordering and Java integration where affected |
| Clarify a configuration setting | Setting registry plus actual consumer | Validation tests and a test proving the runtime consumes the value |

Start with a failing assertion in an existing suite, not a new abstraction or a
benchmark campaign. Keep the change inside its ownership boundary; expand testing
when persisted bytes, concurrency or cross-language contracts change. See
[Testing and contributing](TESTING-AND-CONTRIBUTING.md) for commands and review.

From the repository root, a dataset-free first verification is:

```powershell
.\gradlew.bat :examples:aether-sample-app:run
.\gradlew.bat :modules:aether-engine:test --tests io.aetherdb.engine.InMemoryAetherDatabaseTest --console=plain
```

On POSIX use `./gradlew`. Persistence, corruption and real-process suites are
additional gates, not covered by this first verification.
