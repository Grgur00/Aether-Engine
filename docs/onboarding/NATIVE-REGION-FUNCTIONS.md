# Native Region and Allocation Functions

This reference describes the current checkout, including development changes that
may not yet be published on `main`. Start with the [native memtable](NATIVE-MEMTABLE-FUNCTIONS.md)
to see which owner freezes and retires these regions. No runtime behavior changes
are made by this guide.

## Ownership Map

`DefaultNativeRegionFactory.create` reserves capacity from a shared budget, opens
a shared FFM arena, and allocates one zero-filled contiguous segment.
`FfmNativeRegion` owns that arena and reservation. Its monotonic allocator returns
integer offsets into the segment; it neither creates independent arenas nor frees
individual records. Retirement releases the entire region after its users finish.

There are two separate accounting boundaries:

| Boundary | What is charged | When it is released |
| --- | --- | --- |
| `NativeMemoryBudget` | Full region capacity, including unused space | Region close or factory rollback |
| `MonotonicNativeAllocator` | Payload and alignment padding after the first 64 bytes | Never individually; region destruction ends validity |

These regions are process-local memory, not a persistent file format. A region
offset is meaningful only with its owning live region. A shared arena permits
cross-thread access; it does not supply application-level reader leases.

## Capacity Rules

Source: [RegionConfig.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/RegionConfig.java).

### `RegionConfig()` and `validateCapacity(capacity)`

The private constructor prevents utility instances. `validateCapacity` accepts
1 MiB through 1 GiB inclusive, in multiples of 4 KiB; everything else raises
`IllegalArgumentException`. Default capacity is 64 MiB, default alignment is 8,
and the first allocatable offset is 64. The reserved prefix keeps zero available
as a null-link sentinel. This validator does not consult the memory budget.

## Shared Capacity Budget

Source: [NativeMemoryBudget.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeMemoryBudget.java).

### `NativeMemoryBudget()` and `NativeMemoryBudget(limitBytes)`

The no-argument constructor uses a 256 MiB limit. The explicit constructor
rejects nonpositive limits and initializes atomic accounting counters at zero.
The budget tracks reservations, not operating-system resident memory or heap use.

### `tryReserve(bytes)`

Rejects nonpositive sizes and sizes larger than the entire limit as invalid
requests. For valid sizes, a CAS loop checks remaining capacity without overflowing
the addition. Exhaustion returns `false` and increments `reservationFailures`;
successful reservation increments `reservedBytes`, raises `peakReservedBytes`
with an atomic maximum, and increments `regionCount`.

CAS contention retries without counting a failure. The reservation and diagnostic
counter updates are separate operations, so simultaneous observers need not see
one coherent snapshot. The budget does not remember reservation identities.

### `release(bytes)`

Rejects nonpositive sizes, subtracts from reserved capacity, and decrements the
region counter. If the subtraction goes negative, it restores the subtraction
and throws `IllegalStateException` before decrementing `regionCount`.

The caller must release exactly one successful reservation once. This is not an
ownership-token API: a positive release of the wrong size can still succeed if
the aggregate balance stays nonnegative. Correct pairing belongs to the factory
and region owner. The subtract-and-restore error path is not a transaction across
other concurrent budget operations.

### Budget observations

`limitBytes()` returns the fixed limit. `reservedBytes()` reads current reserved
capacity; `availableBytes()` subtracts that reading from the limit.
`peakReservedBytes()` reads the high-water mark, which never falls on release.
`regionCount()` counts successful reservations minus releases, not reachable Java
objects. `reservationFailures()` counts valid requests refused for exhaustion,
not invalid arguments or physical arena-allocation failures.

## Region Factory

Sources: [NativeRegionFactory.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRegionFactory.java)
and [DefaultNativeRegionFactory.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/DefaultNativeRegionFactory.java).

### `DefaultNativeRegionFactory(budget)`

Stores the supplied budget without a null check. A null budget fails when `create`
tries to reserve; construction alone does not allocate memory.

### `create(capacityBytes, ownerId)`

Implements `NativeRegionFactory.create`. Validates capacity and a nonnull,
nonblank owner identifier, then reserves the whole capacity. Budget exhaustion
throws `NativeAllocationException` before opening an arena.

After reservation it opens `Arena.ofShared()`, allocates an 8-byte-aligned segment,
zero-fills it, and constructs `FfmNativeRegion`. Zero-fill is real work proportional
to region capacity; it is not postponed until each record is inserted.

On `RuntimeException` or `OutOfMemoryError` in that allocation block, it attempts
arena close, attaches any runtime close failure as suppressed, and releases the
reservation. An original `Error` is rethrown; an original runtime exception is
wrapped in `NativeAllocationException`. The catch does not cover every possible
`Error`, and rollback itself can throw if its ownership assumptions are violated.

## Region Lifetime

Sources: [NativeRegion.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRegion.java)
and [FfmNativeRegion.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/FfmNativeRegion.java).

### `FfmNativeRegion(ownerId, capacity, arena, root, budget)`

Package-private construction stores its owning resources and creates a
`MonotonicNativeAllocator`. Initial state is `OPEN`. The constructor relies on
factory validation; it does not independently check capacities or null resources.

### `capacityBytes()`, `state()`, `allocator()`, and `rootSegment()`

The first two return capacity and atomic state, even after close. `allocator()`
and `rootSegment()` call `ensureAlive()` before returning their existing objects.
They remain available while `FROZEN`, allowing reads but no new allocation.
Returning the root segment does not make it read-only: freezing is an allocation
policy, not a hardware or FFM write-protection operation.

### `freeze()`

Throws if its initial state reading is `CLOSED`, otherwise attempts the
`OPEN` to `FROZEN` CAS. Already-frozen calls normally do nothing. It does not
wait for in-flight allocations or acquire a lock against close. Higher-level
owners must establish the lifetime boundary before freezing.

### `close()`

Already-closed calls return. An `OPEN` region cannot be closed directly and throws
an owner-labelled `IllegalStateException`. One caller wins the `FROZEN` to `CLOSED`
CAS, closes the arena, and releases capacity once in a `finally` block guarded
by `budgetReleased`. Other callers do not repeat that work.

State becomes `CLOSED` before arena destruction completes. Capacity is released
even if arena close throws; close failure is not retried by later calls. There
is no lease count in this class, so the caller must ensure outstanding segment
users have finished. Previously returned segments follow FFM arena lifetime checks.

### `ensureOpenForAllocation()` and `ensureAlive()`

The first requires exactly `OPEN`; the second rejects only `CLOSED`. These are
atomic-state checks, not locks or retained ownership. A successful check does
not guarantee the state stays unchanged for the rest of a caller's operation.

## Monotonic Allocation

Sources: [NativeAllocator.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeAllocator.java)
and [MonotonicNativeAllocator.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/MonotonicNativeAllocator.java).

### `MonotonicNativeAllocator(region)`

Stores its region and initializes the atomic cursor at offset 64. Payload,
padding, allocation, and failure counters start at zero. There is no reset,
free-list, rollback, or individual deallocation operation.

### `tryAllocate(sizeBytes, alignmentBytes)`

Checks the region is open first, then validates positive size no larger than
`Integer.MAX_VALUE` and supported alignment. In a CAS loop it aligns the current
cursor and calculates the end using checked addition. A request beyond capacity
returns `Allocation.full()` and increments the failure counter without moving
the cursor. End overflow is wrapped as `IllegalArgumentException`.

Successful cursor reservation adds payload bytes, alignment padding, and one
allocation to separate atomic counters, then returns `Allocation.at(offset,
length)`. CAS contention recomputes alignment from the new cursor. The region's
1 GiB limit keeps successful offsets representable as integers.

The open-state check precedes the loop and is not repeated inside it. Concurrent
freeze or close is therefore not an allocation barrier by itself. Serialize those
lifetime transitions at the owner, as the native memtable does for insertion
and freeze. Allocation reserves space; it does not initialize a record or publish
a skip-list link.

### `alignUp(value, alignment)` and `supported(alignment)`

`supported` recognizes only 1, 2, 4, 8, 16, 32, and 64. `alignUp` rejects other
alignments, uses checked addition of `alignment - 1`, then clears low bits.
It does not validate a nonnegative input; the allocator supplies its own cursor.
Overflow in this helper propagates as `ArithmeticException`.

### Allocator observations

`usedBytes()` is cursor minus 64, including alignment padding but excluding the
reserved prefix. `remainingBytes()` is capacity minus cursor. They do not check
region liveness and can still report counters after close.
`allocatedPayloadBytes()`, `alignmentPaddingBytes()`, `allocationCount()`, and
`failedAllocationCount()` read their individual counters. Invalid requests and
overflow do not count as capacity failures. Separate readings during allocation
need not satisfy an instantaneous accounting equation.

### `Allocation.full()` and `Allocation.at(offset, length)`

`NativeAllocator.Allocation` is a record with `allocated`, `offset`, and `length`
accessors. `full` returns `(false, 0, 0)`; `at` returns `(true, offset, length)`.
Neither factory nor the implicit record constructor validates supplied offsets
or lengths. A returned allocation is a descriptor, not a lease or memory segment.

## Allocation Errors

Source: [NativeAllocationException.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeAllocationException.java).

`NativeAllocationException(message)` preserves a message;
`NativeAllocationException(message, cause)` also preserves its cause. Budget
exhaustion and physical region creation use this exception; ordinary bump-allocator
capacity exhaustion is a returned `Allocation.full()`, not this exception.

## Reading Checklist

Before changing this layer, trace reservation ownership through factory rollback
and region close; trace lifetime coordination through the memtable's freeze,
retirement, and lease release. Do not infer record validity from a successful
allocation. Record encoding and checked reads form a separate boundary described
in the [native record reference](NATIVE-RECORD-FUNCTIONS.md).
Function-name inventory tests detect missing names, not incorrect descriptions,
race freedom, or allocation correctness.
