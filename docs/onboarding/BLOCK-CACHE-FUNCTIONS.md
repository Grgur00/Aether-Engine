# Block Cache Functions

[SSTable reads](SSTABLE-READ-FUNCTIONS.md) | [Module guide](MODULE-GUIDE.md)

This describes current `aether-cache` source. The local engine and SSTable main
Java sources do not reference these cache types. `SSTableReader` has a different
per-reader decoded-entry cache; do not attribute this module's raw-byte capacity,
leases, or metrics to that read path without tracing an actual integration.

## Architecture

`DataBlockCache` shards by block identity. Each shard has access-ordered probation
and protected maps plus a same-key in-flight future map. A successful lookup pins
the entry; `BlockLease.close` releases that pin. A miss invokes a supplied loader
or joins another caller's load. Residency charges raw array length plus 96 bytes
of estimated entry overhead, not actual total JVM heap usage.

The loader must verify checksums before returning. The cache only checks returned
length; it does not inspect an envelope, validate a checksum, read a channel, or
clone the loader array. Borrowed arrays are read-only by contract, not by type.

## Identity and Loader

Sources: [BlockCacheKey.java](../../modules/aether-cache/src/main/java/io/aetherdb/cache/BlockCacheKey.java)
and [BlockLoader.java](../../modules/aether-cache/src/main/java/io/aetherdb/cache/BlockLoader.java).

### `BlockCacheKey(fileNumber, blockOffset, blockLength)`

Requires positive file number, nonnegative offset and encoded length. Zero-length
blocks are allowed. Scalar record equality forms map identity. There is no database
UUID, pathname, checksum policy, or offset-plus-length overflow validation in this
key. A shared cache owner must prevent collisions across databases or reused file
numbers. Its implicit accessors return the three scalar fields.

### `BlockLoader.load()`

Functional callback returning newly loaded, verified raw bytes. No I/O or checksum
implementation is supplied by the interface. The miss owner executes synchronously
on its own thread; loader failure is delivered through the in-flight future.

## Cache Creation and Routing

Source: [DataBlockCache.java](../../modules/aether-cache/src/main/java/io/aetherdb/cache/DataBlockCache.java).

### `DataBlockCache()` and `DataBlockCache(capacity, shardCount)`

Defaults are 128 MiB and 16 shards. Explicit capacity must be 16 MiB through
4 GiB inclusive; shard count must be a positive power of two. Capacity is split
by integer division, distributing the remainder one byte at a time to initial
shards. There is no additional practical upper bound on shard-array size.

### `shard(key)`, `Shard(capacity)`, and `ensureOpen()`

Routing mixes record hash with its upper bits and masks by shard count minus one.
The nested shard sets probation's nominal limit to one fifth of its capacity and
maximum admission charge to one quarter. Spare capacity in another shard is not
borrowed. `ensureOpen` rejects a volatile closed flag; it is not a lifecycle lock
covering the entire acquire/load/admission operation.

## Acquire or Load

### `acquireOrLoad(key, loader, fillCache)`

Requires nonnull key and loader and checks open. It first tries a synchronized
resident lookup. A hit increments hits and returns a pin, even when `fillCache`
is false. A miss increments misses, then rechecks under the shard monitor; a hit
on that second check increments hits too, so one operation may count both.

Otherwise finds or installs an in-flight future. Its owner runs the loader outside
the shard lock, requires nonnull bytes and exactly the key's encoded length, counts
a completed load, and completes the future. Any thrown `Throwable` increments
load failures and completes exceptionally. Other callers join the same future;
their loaders are not invoked for that coalesced load.

Join failure unwraps runtime exceptions or errors, wrapping other causes in
`IllegalStateException`. The owner removes the in-flight entry on the normal
completion-exception path. There is no timeout or interruptible wait API here.

With `fillCache=false`, returns a nonresident lease with a no-op release and does
not increment admission bypasses. With true, attempts admission, removes the
owner's in-flight mapping, and either returns a resident pin or counts an admission
bypass and returns the loaded bytes nonresident. Each waiter makes its own fill
decision. Completed futures can be removed before every waiter finishes admission;
coalescing is not a permanent one-load-per-key guarantee.

There is no final closed check inside admission. A caller that passed earlier
checks can race close and admit afterward if it already obtained loaded bytes.
Likewise invalidation does not cancel pending loads, so a later admission can
repopulate that file. Owners need lifecycle/file-identity coordination.

### `lease(shard, entry)`

Creates a lease over the exact stored array with a release callback to that shard.
It neither copies bytes nor increments pins itself; acquisition already did so.

## Shard Lookup and Admission

### `acquire(key)` and `acquireLocked(key)`

The synchronized wrapper calls the monitor-owned helper. Protected hits update
access order and increment pins. Probation hits remove the entry, subtract probation
weight, promote to protected, increment pins, and rebalance. There is no pin-counter
overflow guard or checksum revalidation on a cache hit.

### `admitAndAcquire(key, bytes)` and `Entry(key, bytes, weight)`

Synchronized admission first acquires an existing entry if a concurrent caller
admitted it. Otherwise charge is array length plus 96. Over-quarter-shard charge
bypasses immediately. A new `Entry` stores the array without cloning and starts
with one pin when added to probation.

After adding weight, eviction tries to fit capacity. If pinned entries prevent
fitting, admission removes the new entry and returns null. Evictions already made
during that attempt are not rolled back. The `resident` marker is set false on
ordinary eviction/invalidation, but is not cleared on this failed-admission removal
and is not consulted by lookup; map membership defines residency here.

### `rebalanceProtected()`

Demotes oldest protected entries into probation until protected weight is at most
capacity minus the probation limit. Pinned entries can be demoted; pins prevent
capacity eviction, not movement between segments. This does not enforce a hard
20% probation cap after every insertion.

### `evictToCapacity()` and `evictOne(entries, isProbation)`

Tries probation first, then protected, repeatedly while overweight. `evictOne`
scans oldest-first for an unpinned entry, removes it, adjusts weights, marks it
nonresident, and increments evictions. It skips pinned entries and returns false
if none is removable. Residency remains bounded through admission rejection when
existing pins make space unavailable; nonresident loaded arrays are not charged.

### `release(entry)`

Synchronized release rejects nonpositive pins, decrements, and retries capacity
eviction. It works for an entry removed by invalidation or cache clear too: a
lease still owns its array reference. Invalidated pins no longer appear in resident
metrics. The callback does not destroy a heap array or revoke previously returned
references.

## Invalidation and Close

### `invalidateFile(fileNumber)` and `invalidate(entries, fileNumber, isProbation)`

The public method validates positive identity and visits every shard, without an
open check. The synchronized shard method calls the private helper on both maps.
Matching entries are removed even while pinned; leases keep their bytes alive.
Weights are subtracted, but evictions are not incremented. In-flight loads remain.

### `close()` and `clear()`

Close sets the volatile flag then clears shards one at a time. Synchronized clear
marks current residents nonresident, clears both maps, zeroes weights, completes
currently registered futures exceptionally with a closed-cache error, and clears
the future map. It does not cancel an executing loader or revoke leases. A future
already completed successfully cannot be changed to exceptional completion.
The post-load admission race means clear is not a universal barrier against a
previously started caller repopulating a shard; serialize lifecycle at the owner.

## Lease Contract

Source: [BlockLease.java](../../modules/aether-cache/src/main/java/io/aetherdb/cache/BlockLease.java).

### `BlockLease(bytes, releaser)`, `rawBytes()`, and `rawLength()`

Package-private construction stores array and callback without validation.
`rawBytes` rejects a closed lease then returns the actual mutable array; callers
must not modify it. `rawLength` returns length even after close. Keeping an array
reference after close is possible in Java, but no longer represents an active pin.
The closed check does not lock against another thread closing the same handle.

### `isClosed()` and `close()`

`isClosed` reads the atomic flag. Close CASes once then invokes the release callback.
A callback failure leaves the lease closed and is not retried. Bypass leases use
a no-op callback; they still enforce the `rawBytes` closed-access check.

## Metrics

Source: [BlockCacheMetrics.java](../../modules/aether-cache/src/main/java/io/aetherdb/cache/BlockCacheMetrics.java).

### `metrics()`, `pinnedCount()`, and `BlockCacheMetrics(...)`

`metrics` locks shards individually to sum resident weight and counts of resident
entries with at least one pin, then samples `LongAdder` counters. `pinnedCount`
counts entries, not leases. This is not one atomic cross-shard snapshot.
The metrics record's implicit accessors expose hits, misses, loads, load failures,
evictions, admission bypasses, resident bytes, and pinned entries. Its constructor
has no independent validation.

Loaded but bypassed arrays, in-flight arrays, caller-held invalidated bytes, and
actual JVM object overhead are not included in resident weight. Close/invalidation
are not capacity evictions, and `fillCache=false` misses are not counted as admission
bypasses. Interpret counters according to these call paths, not as a heap budget.

## Review Boundaries

Trace file identity, verified loader ownership, lease close, and shutdown ordering.
Test pinned capacity, same-key coalescing, loader failures, invalidation races, and
mutable-array misuse. Function-name coverage checks omissions, not integration,
checksum correctness, or a global bound on all live loaded bytes.
