# Resource Admission Functions

[Write pressure](WRITE-PRESSURE-FUNCTIONS.md) | [Module guide](MODULE-GUIDE.md)

This reference describes current source in `aether-admission`. It is a reusable
pure evaluator, not an automatic global limiter. The local engine and training
cache currently do not reference `AdmissionController`, `AdmissionPolicy`, or
`AdmissionRequest` in their main Java sources. Their own admission paths must be
read separately; similar terminology does not establish integration.

## Architecture and Limits

A policy supplies resource thresholds; a snapshot supplies current measurements;
a request supplies projected charges and draining state. Evaluation returns a
decision with reasons and optional delay. It does not reserve resources, apply
charges, measure the host, sleep, mutate the WAL, transfer leadership, mark the
node read-only, or send an acknowledgement.

Two concurrent accepted decisions can oversubscribe a resource if their caller
does not serialize measurement and reservation. Acceptance means only that the
supplied projected values passed this evaluation. Rejection-before-ack semantics
require the caller to evaluate before crossing its actual uncertainty boundary.

## Resource Models

Sources: [ResourceLimit.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/ResourceLimit.java)
and [ResourceMeasurement.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/ResourceMeasurement.java).

### `ResourceLimit(resource, softLimit, hardLimit, emergencyLimit)`

Requires a resource, nonnegative soft limit, positive hard/emergency limits, and
`soft <= hard <= emergency`. Equal adjacent thresholds are allowed, unlike write
pressure's strictly separated slow/stop pairs. The record provides scalar accessors
for its fields. Thresholds are quantities in the chosen dimension; the model does
not convert units or collect measurements.

### `ResourceMeasurement(resource, currentValue)`

Requires nonnull resource and nonnegative current value. Its accessors expose
those fields. There is no sampling timestamp, freshness check, rate window, or
coherence guarantee with measurements of other dimensions.

## Admission Policy

Source: [AdmissionPolicy.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionPolicy.java).

### `AdmissionPolicy(limits, maximumSlowdownDelay)`

Requires a nonnull map and nonnegative nonnull duration, validates each map key
matches its limit's resource, copies through an enum map, then exposes an immutable
map. Null map entries can fail by dereference rather than a custom validation
message. Empty policies are valid. Very large durations are accepted here even
if converting to nanoseconds during evaluation will overflow.

### `of(limit, more)` and `empty()`

`of` collects the first limit plus varargs into an enum map and selects 100 ms
maximum slowdown. Repeated resources overwrite earlier limits; it does not reject
duplicates. `empty` creates an empty policy with zero delay. Empty policy accepts
a nondraining request even if its charge map contains large quantities.

### `orderedLimits()`

Sorts configured limits alphabetically by resource enum name and returns an
unmodifiable list. This makes reason order deterministic within the chosen outcome
category, not declaration order or numeric severity order. The policy's implicit
`limits()` accessor exposes its immutable map; `maximumSlowdownDelay()` exposes
the duration.

## Snapshot and Request

Sources: [AdmissionSnapshot.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionSnapshot.java)
and [AdmissionRequest.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionRequest.java).

### `AdmissionSnapshot(measurements)` and `value(resource)`

Construction validates nonnull map and key/resource correspondence, copying into
an immutable map. The `measurements()` accessor returns that map. `value` returns
the current measured value, or zero when the dimension is missing. Missing is not
treated as unknown or unsafe; callers needing conservative admission must supply
measurements for configured dimensions.

### `AdmissionRequest(charges, draining)`

Requires a nonnull map, rejects null resource keys and negative charges, omits
zero charges, and copies positive charges into an immutable map. A null boxed
charge fails on unboxing. Its `charges()` and `draining()` accessors expose immutable
deltas and the caller's node-state flag. Charges for dimensions absent from the
policy are not checked by the controller.

## Evaluation

Source: [AdmissionController.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionController.java).

### `evaluate(policy, snapshot, request)`

If draining, immediately returns `DRAINING_REJECTED`, reason `node is draining`,
and zero delay. This path does not consult policy or snapshot. Otherwise visits
ordered policy limits and adds current plus requested charge with saturation.
Each resource contributes to only one bucket, using inclusive thresholds:

1. At or above emergency: emergency reason.
2. Otherwise at or above hard: hard reason.
3. Otherwise at or above soft: soft reason and a normalized slowdown ratio.

If any emergency reasons exist, returns `RESOURCE_EXHAUSTED` with only those
reasons. Otherwise hard reasons produce the same outcome with only hard reasons.
Otherwise soft reasons produce `REJECTED_BEFORE_ACK` with a suggested delay.
No reasons produces `ACCEPTED` and zero delay. Lower-priority categories are not
included in the returned reasons when a higher one wins.

Soft pressure here is a rejection with retry-delay advice, not the storage
controller's `SLOWDOWN` state admitting after a delay. Emergency evaluation does
not itself switch the node to read-only. The controller never emits `UNCERTAIN`.
There are no explicit null guards for the three evaluation arguments.

### `addSaturated(left, right)`

Adds nonnegative current and charge values; a negative result signals signed
overflow and becomes `Long.MAX_VALUE`. Model validation supplies the nonnegative
domain. Saturation still compares against configured thresholds, including limits
equal to `Long.MAX_VALUE`.

### `ratio(value, range)`

For positive range, divides as double and clamps to zero through one. Nonpositive
range returns one. In the soft branch, value is projected minus soft and range
is hard minus soft. Equal soft/hard thresholds are caught by the hard branch,
so that degenerate range does not normally reach slowdown computation.

### `slowdown(maximum, ratio)`

Zero maximum returns zero. Otherwise converts maximum to nanoseconds, multiplies
by at least 1% of maximum, rounds with a minimum of one nanosecond, and caps at
the maximum. At exactly soft threshold the ratio is zero, but advice is still
1% of maximum rather than zero. With `of` policy that is 1 ms of its 100 ms limit.
An oversized duration can throw `ArithmeticException` during `toNanos`; there is
no saturation for that conversion. This function only computes advice.

## Decision Contract

Sources: [AdmissionDecision.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionDecision.java)
and [AdmissionOutcome.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionOutcome.java).

### `AdmissionDecision(outcome, reasons, suggestedDelay)` and `accepted()`

Requires nonnull outcome, reasons, and delay, copies reasons immutably, rejects
negative delay, and forbids nonempty reasons for accepted decisions. It does not
require rejection reasons for rejected outcomes or forbid delay on acceptance.
`accepted` tests outcome equals `ACCEPTED`; other accessors return stored fields.

Outcome meanings are acceptance, pre-ack rejection, uncertain final state,
draining rejection, or resource exhaustion. `UNCERTAIN` exists for callers to
represent operations beyond an uncertainty boundary; this evaluator cannot derive
it from a resource snapshot. An outcome taxonomy alone does not implement
idempotency, read-back, acknowledgement ordering, or leadership transfer.

## Resource Dimensions

Source: [AdmissionResource.java](../../modules/aether-admission/src/main/java/io/aetherdb/admission/AdmissionResource.java).

Dimensions cover heap/native/cache/immutable bytes; active and retained WAL;
live SSTables and compaction debt; open files; RPC inbound/outbound bytes and
inflight streams; unapplied Raft entries; snapshot-transfer rate; backup bytes,
objects, and rate; thread count and virtual-thread inflight work. Each enum value
is an identifier, not a sensor or automatic hook into the named subsystem.

## Review Boundaries

Audit configured dimensions against actual measurements and reservation ownership.
Test equal thresholds, missing measurements, ignored charges, saturated addition,
draining precedence, category-specific reasons, and duration overflow. Function-name
coverage checks documentation omissions, not resource safety or runtime integration.
