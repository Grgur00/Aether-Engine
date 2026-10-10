# Compaction Planning Functions

[Persistent orchestration](PERSISTENT-INTERNALS.md) | [Manifest versions](MANIFEST-VERSION-FUNCTIONS.md)

This guide describes current source, including local development changes not
necessarily published on `main`. The picker chooses metadata; it does not open
tables, discard records, write output, commit a manifest, or delete input files.

## Architecture

The engine converts its live version into a `VersionInventory`, calculates scores,
and asks `CompactionPickerV1` for one plan. A range registration can prevent jobs
sharing levels and key ranges. The coordinator still owns input retention,
snapshot boundaries, output creation, stale-plan checks, and durable publication.
A plan is not a lease or proof that its inputs remain live.

There are seven levels, L0 through L6. Only L0 through L5 have downward compaction
scores. L0 files may overlap; nonzero levels require sorted, disjoint inclusive
user-key ranges. All key comparisons here are unsigned lexical comparisons.

## Sizing Policy

Source: [LevelCompactionConfig.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/LevelCompactionConfig.java).

### `LevelCompactionConfig(level1TargetBytes, levelSizeMultiplier)` and `defaults()`

The compact record constructor requires L1 target from 128 MiB through 8 GiB
and multiplier from 2 through 20. `defaults` selects 512 MiB and multiplier 10.
`LEVEL_COUNT` is 7; `MIB` and `GIB` are binary size constants. This policy is
separate from the initial bulk-loader SSTable target used in ML experiments.

### `targetBytes(level)`

For L1 through L6, repeatedly multiplies the L1 target by the growth factor.
Returns `Long.MAX_VALUE` rather than overflowing. This is total target bytes
for a level, not the size of one output file.

### `targetOutputFileBytes(level)` and `requireLevel(level, minimum, maximum)`

Output targets are 64 MiB for L1/L2, 128 MiB for L3, 256 MiB for L4, and 512 MiB
for L5/L6. They do not vary with the two record configuration fields.
Both sizing functions call the private inclusive-range validator and reject
invalid levels with `IllegalArgumentException`.

## File Metadata

Source: [CompactionFile.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionFile.java).

### `CompactionFile(fileNumber, level, smallestUserKey, largestUserKey, fileSize)`

Requires positive number, level 0 through 6, nonnull ordered bounds, and nonnegative
size. Copies both bounds. Zero size and empty user keys are allowed. No file is
read, and declared ranges are not verified against table contents.

### `fileNumber()`, `level()`, `fileSize()`, `smallestUserKey()`, and `largestUserKey()`

Return scalar metadata or defensive bound copies. This class does not override
`equals` or `hashCode`: list membership and equality use object identity, not file
number or byte content.

### `overlaps(smallest, largest)` and `contains(key)`

Test inclusive interval overlap or point membership directly against stored bounds.
Touching endpoints overlap. These helpers do not validate a proposed range or add
custom null guards. They are metadata filters, not Bloom tests or content lookup.

## Inventory Snapshot

Source: [VersionInventory.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/VersionInventory.java).

### `VersionInventory(levels)` and `VersionInventory(levels, pointers)`

The first supplies six null pointers using `emptyPointers`. The second requires
exactly seven level lists and six nullable pointer arrays. Copies the lists,
checks each file's level matches its containing list, and rejects duplicate
file numbers globally. Nonzero levels are validated without sorting them.
Pointer arrays are cloned and their nullable list made unmodifiable.

### `validateNonOverlapping(files)` and `emptyPointers()`

The private validator requires each previous largest key to be strictly below
the next smallest key. Shared endpoints fail. File constructors validate each
individual range; this helper enforces list order and cross-file disjointness.
`emptyPointers` creates a six-element list of nulls for L0 through L5.

### `level(level)` and `pointer(level)`

Return an immutable file list or a cloned pointer, preserving null when absent.
Invalid indices fail through list bounds checks. The inventory never mutates
pointers itself; it represents one snapshot, not a live scheduler.

### `levelBytes(level)`, `overlaps(level, smallest, largest)`, and `containsFile(number)`

`levelBytes` uses checked addition and throws on overflow. `overlaps` filters the
chosen level in its existing order and returns an unmodifiable result list.
`containsFile` scans all levels for number membership. None performs disk I/O.

## Scores and Debt

Sources: [CompactionScoreCalculator.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionScoreCalculator.java)
and [CompactionScores.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionScores.java).

### `CompactionScoreCalculator(config)` and `calculate(version)`

Construction rejects null configuration. `calculate` creates six scores: L0 file
count divided by 4.0, then L1 through L5 bytes divided by configured target bytes.
There is no L6 score. Inventory sum overflow still throws; score calculation does
not pin the version or reserve an execution slot.

### `estimatedDebtBytes(version)`

L0 debt is files above four multiplied by integer average file size. Nonzero
debt sums bytes above target for L1 through L5. This is a backlog estimate, not
predicted physical write amplification or elapsed time. It ignores L6 and the
destination bytes that a selected compaction may also read.

### `saturatingAdd`, `saturatingMultiply`, and `ListMath.average()`

The private arithmetic helpers saturate nonnegative debt terms at `Long.MAX_VALUE`.
The nested `ListMath` record holds count and bytes; `average` returns zero for
zero count, otherwise integer division. Per-level byte sums are computed before
these helpers and can still throw on overflow.

### `CompactionScores(values)`, `values()`, and `score(level)`

Construction requires a nonnull six-element array and clones it. `values` returns
another clone; `score` indexes the stored array. Scores are not checked for finite,
nonnegative, or inventory-consistent values. Callers can supply values that the
calculator would not produce; record equality on the array is not content equality.

## Picker Entry Point

Source: [CompactionPickerV1.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionPickerV1.java).

### `CompactionPickerV1(config)`, `pick(version, scores, oldestSnapshotSequence)`, and `selectedLevel`

Construction requires nonnull configuration. `selectedLevel` forces L0 at 12 or
more files. Otherwise it scans L0 through L5 for scores at least 1, choosing the
highest with lower-level tie priority. Below threshold returns no selection.
`pick` returns an empty optional in that case; otherwise it builds a plan.

Selected L0 at 20 or more files is labelled `WRITE_STOP`, at 12 or more
`URGENT_L0`; other plans use `SCORE`. These are reason labels, not write admission
actions. The picker does not emit `MANUAL`. An externally supplied high score for
an empty level can select it and subsequently fail seed selection; use scores
calculated from the same inventory. A negative snapshot is rejected only when
constructing a selected plan, not when returning empty.

## Input Selection

### `pickL0(version, score, snapshot, reason)`

Seeds with the smallest file number in L0, then repeatedly adds overlapping L0
files and overlapping L1 files. Both sets can widen the inclusive range; widening
causes another pass. This reaches stable overlap closure across the two levels,
including L0 files connected through an L1 range. Primary inputs retain seed-first
and discovery order, not a sorted-by-key or sorted-by-number order.

The overlap loop uses identity membership in the inventory's existing objects.
It does not impose a byte budget on L0 closure or consult active range registrations.

### `pickNonzero(version, level, score, snapshot, reason)`

Starts with the first source file. If a pointer exists, selects the first file
whose largest key is strictly greater than it, wrapping to the first if none is.
Finds destination overlaps. If any exist, widens a candidate range to their bounds
and considers additional source overlaps.

Expansion is accepted only when destination overlap list stays equal and expanded
input bytes are at most `max(512 MiB, 2 * initial input bytes)`. This is a bounded
single expansion, not L0's repeated closure. The stored range becomes the combined
seed/destination range; it is not recomputed from potentially wider outer bounds
of every expanded source file. Review that distinction when consuming plan ranges.

### `plan`, `bytes`, `minimum`, and `maximum`

`plan` chooses output level immediately below input, finds overlapping grandparents
one level below output (none for L6 output), sets configured output target and a
grandparent overlap limit of ten times target, and sums source plus destination
bytes as an estimate. Pointer update is the largest key of the last primary list
element, not a separate maximum over all primary bounds.

`bytes` uses checked accumulation within a list. Some additions combining two list
sums and the expansion's doubled size use ordinary long arithmetic, not saturation.
`minimum` and `maximum` compare unsigned arrays and return one supplied reference;
plan construction subsequently clones stored bounds. Grandparent metadata and
limits do not themselves split an output file.

## Plan Contract

Sources: [CompactionPlan.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionPlan.java)
and [CompactionReason.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionReason.java).

### `CompactionPlan(...)` and defensive accessors

The compact constructor copies all three lists and clones both bounds and pointer.
It requires nonempty primary inputs, adjacent input/output levels, and nonnegative
oldest snapshot. It does not independently validate level bounds, file-level
membership, ordered plan bounds, positive targets, finite score, or nonnull reason.
The picker supplies those under its normal inventory/configuration assumptions.

`smallestUserKey()`, `largestUserKey()`, and `pointerUpdate()` return fresh clones.
Other record accessors expose immutable lists or scalar fields. Array components
retain Java record array-identity equality semantics. Reasons are `SCORE`,
`URGENT_L0`, `WRITE_STOP`, and `MANUAL`; their presence does not execute an action.

## Active Range Registry

Source: [CompactionRangeRegistry.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionRangeRegistry.java).

### `CompactionRangeRegistry()`, `register`, and `activeCount()`

Construction starts empty. Synchronized `register(inputLevel, outputLevel,
smallest, largest)` clones bounds, rejects reversed range, rejects a conflicting
job, then stores the proposed range and returns a registration. It does not
validate level numbers or adjacency. Null bounds fail on cloning.
Synchronized `activeCount` returns current registrations, not worker threads.

### `Range.conflicts(other)` and `release(range)`

Two ranges conflict when either input/output level is shared and inclusive key
ranges overlap. Disjoint keys can run on shared levels; overlapping keys on wholly
distinct levels do not conflict. The private synchronized `release` removes the
registered range and throws if it is missing. Registry ownership does not verify
that selected files remain part of the live manifest.

### `Registration(owner, range)` and `close()`

The package-private constructor stores owner and range. Synchronized close clears
its owner then releases the range once; subsequent closes return. If release
throws, owner is already cleared and another close will not retry. The registration
is a job-conflict guard, not a table-reader lease.

## Review Boundaries

Keep inventory ordering, inclusive endpoints, snapshot retention, and pointer
updates visible when changing selection. Validate plans against the live version
before installation, not merely against their construction snapshot. Pure picker
tests and function-name coverage do not prove durable publication, record-dropping
safety, or conflict-free execution in the coordinator.
