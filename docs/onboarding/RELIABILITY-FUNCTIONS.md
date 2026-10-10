# Reliability and Fault Injection Functions

[Testing guide](TESTING-AND-CONTRIBUTING.md) | [WAL formats](WAL-FUNCTIONS.md)

This describes current source in `aether-reliability`. Test hooks and byte mutations
are mechanisms for experiments, not evidence by themselves that recovery passes.
Declared hook names do not prove that every subsystem invokes them.

## Architecture and Failure Models

`CrashPointRegistry` dispatches named events to one process-local active hook.
The default hook does nothing. `TriggeringCrashPoint` throws at a chosen matching
hit count; Java unwinding and cleanup can still execute. A process-death campaign
must use a separate process and a terminating hook or external termination, not
equate this exception with sudden loss of power.

`CorruptionMutator` instead produces a modified byte array from a deterministic
plan. It does not write files, repair checksums, terminate a process, or decide
whether corruption is acceptable. Tests persist the result and exercise the
appropriate parser or recovery path themselves.

## Crash Context and Callback

Sources: [CrashContext.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CrashContext.java)
and [CrashPoint.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CrashPoint.java).

### `CrashContext(attributes)` and `of(key, value)`

Construction requires a nonnull map, nonblank nonnull keys, and nonnull values,
then copies immutably. `EMPTY` is a shared empty context. `of` uses `Map.of` to
create a one-attribute context; null arguments can fail there before the constructor's
custom checks. The implicit `attributes()` accessor exposes the immutable map.
Context does not automatically include sequence, path, or durability metadata.

### `CrashPoint.hit(id, context)`

The functional interface supplies an event callback. It defines no filtering,
thread-affinity, termination, or exception policy. The registry invokes the callback
synchronously on the hitting thread and does not catch callback exceptions.

## Process-Global Dispatcher

Source: [CrashPointRegistry.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CrashPointRegistry.java).

### `CrashPointRegistry()` and `validateId(id)`

Private utility constructor. Validation accepts dot-separated lowercase segments
starting with a letter and continuing with lowercase letters, digits, or underscores.
At least two segments are required. It returns the same validated string or throws
`IllegalArgumentException`. Validation is syntactic, not membership in the standard
ID catalog; custom valid names are allowed.

### `hit(id)` and `hit(id, context)`

The first supplies `CrashContext.EMPTY`. The second validates ID, reads the atomic
active callback, requires nonnull context, and invokes it. Validation still runs
when injection is disabled. A concurrent installation need not affect a hit that
already read its callback. No crash evidence is recorded by the registry itself.

### `install(crashPoint)` and `restore(previous)`

`install` rejects null, atomically replaces the process-wide active callback, and
returns a scope holding the previous callback. Package-private `restore` simply
sets that previous callback; it does not verify current scope ownership. Hooks are
not thread-local and nested installations require coordinated LIFO closing.
Overlapping tests or out-of-order closes can overwrite another test's active hook.

### `ScopedCrashPoint(previous)` and `close()`

Source: [ScopedCrashPoint.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/ScopedCrashPoint.java).

Package-private construction stores the previous callback. Close checks a plain
boolean, marks closed, then restores it. Normally idempotent for one owner, but
not synchronized or CAS-protected for concurrent close. Closing a scope does not
cancel a callback already executing.

## Counted Exception Trigger

Sources: [TriggeringCrashPoint.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/TriggeringCrashPoint.java)
and [CrashPointException.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CrashPointException.java).

### `TriggeringCrashPoint(targetId, triggerOnHit)`

Validates target ID and requires a positive trigger count. Its atomic counter starts
at zero. Construction does not install the hook globally.

### `hit(id, context)` and `hits()`

Requires nonnull context even for an unmatched ID. Unmatched events return without
counting. Matching events increment atomically and throw only when the new count
equals the configured count. Later matching hits normally continue without another
exception. `hits` reads matching-event count; it is not the total number of registry
events. There is no explicit counter overflow protection or reset operation.

### `CrashPointException(crashPointId)` and `crashPointId()`

Construction preserves the supplied ID and formats the exception message; it does
not independently validate ID or halt the JVM. The accessor returns that ID.
An exception reaching storage code may follow its ordinary failure fencing and
cleanup paths, which differs from abrupt process termination.

## Standard ID Catalog

Source: [CrashPointIds.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CrashPointIds.java).

### `CrashPointIds()` and `all()`

Private utility constructor. `all` returns the immutable standard set, with no
promised iteration order. Constants name write sealing/sequence, WAL fragment and
force, memtable application/ack, flush/manifest, compaction/deletion, checkpoint,
repair, Raft vote/log/commit, snapshot publication, and security-key activation
boundaries. The set contains 19 standard IDs and is not an exhaustive inventory
of all custom hooks used by development code. Read each hit call to establish
what has actually happened at that boundary.

## Corruption Plan

Sources: [CorruptionPlan.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CorruptionPlan.java)
and [CorruptionKind.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CorruptionKind.java).

### `CorruptionPlan(kind, offset, length, value, bitIndex)`

Requires nonnull kind, nonnegative offset and length, and bit index 0 through 7.
It cannot check against an input array not yet supplied. It permits zero length
even for mutation kinds that will reject it on application. Some fields are unused
for each kind; the constructor does not require canonical values in those fields.
Implicit accessors expose the plan's scalar fields.

### `flipBit`, `truncate`, `appendGarbage`, `overwriteRange`, and `tornWritePrefix`

Factories select the corresponding kind and conventional unused fields. Flip sets
length one; truncate and torn-prefix use offset as retained prefix length; append
uses length and repeated byte; overwrite uses offset, length, and repeated byte.
Append accepts a zero byte despite the kind comment's nonzero-garbage wording.
Torn-prefix is a semantic label for the same array truncation operation, not a
filesystem torn-write simulation with sector or force semantics.

## Byte Mutation

Source: [CorruptionMutator.java](../../modules/aether-reliability/src/main/java/io/aetherdb/reliability/CorruptionMutator.java).

### `CorruptionMutator()` and `apply(original, plan)`

Private constructor. `apply` requires nonnull original and plan, then dispatches
by kind, treating truncate and torn-prefix identically. Successful mutation returns
a new array and does not modify the caller's original bytes. Concurrent caller
mutation during copying is not synchronized.

### `flipBit(original, offset, bitIndex)` and `requireOffset`

Requires offset within an actual byte, clones the array, and XORs the chosen bit.
The plan guarantees bit range. `requireOffset` rejects negative or past-end offsets;
an empty input cannot be bit-flipped.

### `truncate(original, prefixLength)`

Requires prefix from zero through original length inclusive, then returns a copied
prefix. Keeping the full length still creates a new array; zero keeps no bytes.

### `appendGarbage(original, bytes, value)`

Requires positive byte count, calculates new length using checked addition, copies
the original, and fills the appended region with the supplied repeated byte.
Length overflow raises `ArithmeticException`; allocation can fail separately.

### `overwriteRange(original, offset, length, value)` and `requireRange`

Requires positive overwrite length and a fully in-bounds range, clones, and fills
that range. `requireRange` computes the end using long arithmetic so integer
addition cannot wrap around its bounds check. These operations do not recognize
format fields or recompute checksums; tests choose meaningful offsets themselves.

## Evidence Boundaries

Keep exception-injection tests distinct from subprocess-death tests and persisted
corruption tests. Verify callbacks are restored even on assertion failure. Do not
run overlapping global-hook scopes without explicit coordination. Document the
expected parser/recovery response and inspect the resulting durable state; reaching
a named hook alone is not a successful crash-consistency result. Function-name
coverage checks omissions, not the reliability claim being tested.
