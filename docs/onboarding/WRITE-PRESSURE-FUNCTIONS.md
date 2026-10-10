# Write Pressure Functions

[Persistent internals](PERSISTENT-INTERNALS.md) | [Compaction planning](COMPACTION-PLANNING-FUNCTIONS.md)

This reference describes the current checkout, including development changes not
necessarily published on `main`. Pressure evaluation is a pure decision step.
The engine owns measurement, waiting, retry, admission deadlines, sequence
reservation, and WAL submission.

## Architecture and Decision Order

One `WritePressureInput` packages storage measurements. `WritePressureController`
evaluates them under a `WritePressurePolicy` and returns a `WritePressureSnapshot`.
It has no previous-state memory, hysteresis, worker wakeup, filesystem query,
sleep, or write mutation. Consistent measurements must be gathered by its caller.

Decision precedence is failure, retryable stop, normal, then slowdown. Reasons
collect all applicable conditions rather than just the winning trigger. Admission
must act on this result before consuming sequence or modifying WAL if it is to
preserve that engine ordering contract; evaluation alone does not enforce it.

## Input Measurements

Source: [WritePressureInput.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure/WritePressureInput.java).

### `WritePressureInput(...)`

The compact record constructor requires nonnegative immutable count, retained WAL
bytes, L0 count, compaction debt, usable disk bytes, and total disk bytes. It does
not ensure usable space is below total space or that all fields came from one
instant. Disk sizes must be nonnegative even when disk measurements are unavailable.

Implicit accessors expose `immutableMemTables`, `nativeCapacityAvailable`,
`retainedWalBytes`, `levelZeroFiles`, `compactionDebtBytes`, `usableDiskBytes`,
`totalDiskBytes`, `diskMeasurementAvailable`, `backgroundFailed`, and
`administrativelyPaused`. Availability/failure flags are caller-supplied facts,
not independently measured by the record.

## Threshold Policy

Source: [WritePressurePolicy.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure/WritePressurePolicy.java).

### `WritePressurePolicy(...)` and `defaults()`

Each slow threshold must be at least one; each stop threshold must be strictly
larger than its corresponding slow threshold. Default pairs are:

| Measurement | Slow at or above | Stop at or above |
| --- | --- | --- |
| Immutable memtables | 2 | 4 |
| L0 files | 12 | 20 |
| Retained WAL | 512 MiB | 2 GiB |
| Compaction debt | 2 GiB | 8 GiB |

Record accessors return these configured thresholds. Native capacity, disk space,
administrative pause, and background failure are not adjustable fields in this
policy. These values are separate from compaction scoring thresholds and bulk
loader table sizing.

### `forImmutableLimit(immutableMemtableStop)`

Derives immutable slow threshold as `max(1, stop / 2)` using integer division,
sets the requested stop, and obtains other thresholds from defaults. A stop of
one or less fails policy validation; this helper does not clamp it to a valid
stop. It creates several default record instances while reading default fields,
not one cached singleton.

## Controller Entry Point

Source: [WritePressureController.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure/WritePressureController.java).

### `WritePressureController()` and `WritePressureController(policy)`

The no-argument constructor delegates to defaults. The explicit constructor
requires nonnull policy. The controller retains no mutable state from evaluations.

### `evaluate(input)`

Creates an enum reason set. Retryable stop is triggered by immutable/WAL/L0/debt
at or above stop, unavailable native capacity, or administrative pause. Reasons
for those numeric measurements begin at their slow thresholds; unavailable native
capacity, failure, and pause have dedicated reasons.

When disk measurements are available, slow space is
`max(10 GiB, floor(total * 15 / 100))`; stop space is
`max(2 GiB, floor(total * 5 / 100))`. Usable space strictly below slow adds
`DISK_SPACE`; strictly below stop sets the stop decision. Equality differs from
the at-or-above numeric backlog thresholds. Unavailable disk measurements do not
contribute any disk reason or disk severity.

Returns in this order:

1. Background failure: `FAILED`, delay 0, severity 1.
2. Any stop condition: `STOPPED_RETRYABLE`, delay 0, severity 1.
3. No reasons: `NORMAL`, delay 0, severity 0.
4. Otherwise: `SLOWDOWN`, normalized severity, and a recommended delay.

All collected reasons are preserved even for failure or stop. Delay zero on a
stopped result means no artificial slowdown recommendation, not permission to
submit immediately. Null input has no explicit guard and fails on dereference.

## Severity and Delay Helpers

### `maximumSeverity(input, policy)`

Computes normalized severity for immutable count, WAL bytes, L0 count, and debt,
then takes their maximum. Available disk contributes reversed free-space severity:
zero at or above slow free space, one at or below stop free space. Final severity
is clamped to zero through one. Native shortage, pause, and failure bypass this
slowdown calculation through higher-priority return paths.

### `ratio(value, start, stop)`

Calculates `(value - start) / (stop - start)` as a double and clamps to zero through
one. Validated thresholds keep denominator positive and measured quantities are
nonnegative. At the slow threshold severity is zero; at stop it is one.

### `percentage(total, percent)`

Computes integer percentage as quotient times percent plus remainder times percent
divided by 100. For this function's actual 5% and 15% call sites, splitting avoids
the overflow of multiplying the whole total first. It is a private helper, not a
general validated percentage API.

### Delay calculation in `evaluate`

Slowdown delay in microseconds is
`min(10000, round(100 + severity * severity * 9900))`.
A just-reached slow threshold therefore recommends 100 microseconds, not zero.
Delay rises quadratically toward 10 milliseconds, but stop/failure returns replace
that recommendation with their own state. The controller never executes the delay.

## Result and Enumerations

Sources: [WritePressureSnapshot.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure/WritePressureSnapshot.java),
[WritePressureState.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure/WritePressureState.java),
and [WritePressureReason.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure/WritePressureReason.java).

### `WritePressureSnapshot(state, reasons, delayMicros, severity)`

Copies reasons into an immutable set. It does not validate nonnull state,
nonnegative delay, finite/clamped severity, or consistency between those fields.
The controller produces conventional combinations; directly constructed snapshots
can violate them. Its implicit accessors expose state, immutable reasons, delay,
and severity without side effects.

### States and reasons

`NORMAL` represents no artificial delay, `SLOWDOWN` recommends bounded delay,
`STOPPED_RETRYABLE` represents recoverable pressure, and `FAILED` represents
required background failure. Whether a caller waits or rejects a stopped write
is an admission-layer choice, not an enum implementation.

Reasons are `IMMUTABLE_MEMTABLES`, `NATIVE_CAPACITY`, `WAL_BYTES`,
`LEVEL_ZERO_FILES`, `COMPACTION_DEBT`, `DISK_SPACE`, `BACKGROUND_FAILURE`, and
`ADMINISTRATIVE_PAUSE`. The immutable reason set has no promised iteration order;
do not use its order as priority.

## Review Boundaries

Test exact threshold equality, failure precedence, unavailable disk measurements,
and delay units. Trace the engine's measurement lock and admission loop before
claiming the controller enforces write ordering or deadlines. It cannot prevent
pressure changing between evaluation and actual submission. Function-name coverage
does not prove that integration or a coherent measurement snapshot.
