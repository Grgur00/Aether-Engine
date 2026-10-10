# Remote Client Routing and Retry Functions

[Function index](FUNCTION-INDEX.md) | [RPC client](RPC-CLIENT-FUNCTIONS.md) | [Module guide](MODULE-GUIDE.md)

Sources: [aether-client](../../modules/aether-client/src/main/java/io/aetherdb/client).
This reference covers **19 explicit declarations** in RemoteEndpointResolver,
RemoteConnectionPool, RemoteRetryPolicy, RemoteRetryDecision and RemoteClientIdentity,
plus method-free RetryAction. The [facade reference](REMOTE-FACADE-FUNCTIONS.md)
covers RemoteAetherClient, typed collections and result types separately.

## Routing Architecture

```text
configured endpoints + observed leader -> bounded ordered resolver snapshot
pool.call -> least-busy candidate + per-endpoint/total inflight reservation
  -> supplied RpcClient.call -> underlying completion -> release reservation
pool.callOn -> explicit resolver candidate (leader redirect caller)
ClientStatus + retry class + attempt budget -> RemoteRetryDecision
  (caller owns delay, next attempt, request identity and dedup evidence)
```

The pool does not create sockets or authenticate principal names. A supplied
transport owns concrete channels, while this layer selects endpoints and tracks
inflight calls. Leader hints are observations, not consensus or peer authentication.
The retry policy is distinct from plaintext RpcClient's one exceptional retry.

## Endpoint Resolver Functions

Source: [RemoteEndpointResolver](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteEndpointResolver.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteEndpointResolver.RemoteEndpointResolver(initialEndpoints, maximumEndpoints)` | Requires positive maximum, stores it and delegates to replacement validation. No network discovery or endpoint health probe. |
| `RemoteEndpointResolver.replaceEndpoints(replacements)` | Synchronized, requires nonnull list, deduplicates in insertion order and requires nonempty set within maximum before replacing current set. Clears preferred leader if absent from replacement. Does not explicitly reject null elements; malformed lists can fail later in candidates(). |
| `RemoteEndpointResolver.observeLeader(leader)` | Synchronized, requires nonnull leader. Adds missing leader if capacity permits, then sets preference. Full set rejects new leader without evicting an endpoint. Existing leader consumes no new capacity. |
| `RemoteEndpointResolver.clearLeader(leader)` | Synchronized, clears preference only if equal to current preferred leader. Does not remove endpoint from set or mark unhealthy; null argument simply fails equality against a nonnull preferred value. |
| `RemoteEndpointResolver.candidates()` | Synchronized immutable list snapshot: leader first if present, then remaining insertion-order endpoints. Does not load-balance or contact nodes. Null set member can cause NullPointerException while comparing/copying. |

Endpoint identity uses RpcEndpoint record equality, including expectedNodeId, not
only canonical host:port. Endpoints with the same address but different pins can
therefore count separately here, while the concrete plaintext transport caches
by canonical address alone. Replacing resolver candidates does not close old
transport channels or clear the pool's accounting for already-running calls.

## Pool and Ownership Functions

Source: [RemoteConnectionPool](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteConnectionPool.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteConnectionPool.RemoteConnectionPool(transport, resolver, identity, maximumInflightPerEndpoint, maximumInflightTotal)` | Requires nonnull collaborators/identity, positive limits and total >=per-endpoint. Owns HashMap counters protected by synchronized methods. No identity credential verification or binding to supplied RpcClient occurs. |
| `RemoteConnectionPool.call(operation, body, options)` | Null arguments throw synchronously before reservation. Reservation failure returns failed future. Calls supplied transport; RuntimeException or null returned future releases endpoint and returns failure. Otherwise returns dependent whenComplete future whose underlying completion releases reservation. Does not copy body or independently check operation byte limits. |
| `RemoteConnectionPool.callOn(endpoint, operation, body, options)` | Requires nonnull arguments, reserves exact candidate and delegates like call. Does not observe/add leader automatically or fall back to another candidate when selected one is full. |
| `RemoteConnectionPool.identity()` | Returns immutable principal metadata supplied at construction; not proof of authentication or channel identity. |
| `RemoteConnectionPool.inflight(endpoint)` | Synchronized current count, default zero for unknown endpoint. Snapshot is not a reservation. |
| `RemoteConnectionPool.totalInflight()` | Synchronized total running-call count; not socket count or byte capacity. |
| `RemoteConnectionPool.close()` | Sets closed under monitor, then invokes transport.close outside lock. Repeated calls invoke underlying close repeatedly. Does not wait itself, clear counts, cancel futures or guarantee graceful drain beyond transport behavior. |
| `RemoteConnectionPool.reserveEndpoint()` | Synchronized closed/total-limit checks, then chooses candidate below per-endpoint cap with smallest inflight count. Strictly-lower comparison preserves resolver order on ties. Increments chosen and total counters atomically under pool monitor; no candidate yields IllegalStateException. |
| `RemoteConnectionPool.reserveEndpoint(endpoint)` | Synchronized closed check, requires endpoint in current resolver snapshot, then enforces total/per-endpoint bounds and increments counts. Throws for noncandidate/full/closed; no waiting queue. |
| `RemoteConnectionPool.releaseEndpoint(endpoint)` | Synchronized decrements/removes endpoint count and decrements positive total. Does not assert matched reservation; missing endpoint still can decrement positive total. Callback discipline is responsible for pairing. |

Leader preference is a tie-breaker, not unconditional routing: a less-busy follower
can beat a busy preferred leader. The pool holds its monitor while asking resolver
for synchronized snapshots. There is no independent endpoint-health state,
backpressure wait or retry loop in this class.

The returned future is a dependent future, not necessarily the original transport
future. Cancelling it does not automatically cancel transport work; reservations
are released when the underlying response completes. A transport future that never
completes can retain capacity indefinitely. Error (rather than RuntimeException)
from transport is not caught by the release-on-failure block. close can race with
an already-reserved call before transport invocation; the pool does not serialize
that whole interval with shutdown. These follow source ordering, not test claims.

## Retry Decision Functions

Sources: [RemoteRetryPolicy](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteRetryPolicy.java),
[RemoteRetryDecision](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteRetryDecision.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteRetryPolicy.decide(status, retryClass, current, leaderHint, commandDeduplicated, attempt, maximumAttempts)` | Requires status/class/current and 1 <=attempt <=maximumAttempts. Ordered decisions: OK completes; INDETERMINATE stops uncertain; exhausted budget completes; NOT_LEADER with hint redirects; otherwise unsafe class completes; safe transient statuses retry current endpoint; remaining statuses complete. Does not sleep, mutate resolver or perform another call. |
| `RemoteRetryPolicy.retrySafe(retryClass, commandDeduplicated)` | Private helper true for IDEMPOTENT, or DEDUP_REQUIRED with caller-supplied true flag. NEVER false. Boolean is an assertion from caller, not verified durable deduplication. |
| `RemoteRetryDecision.RemoteRetryDecision(action, nextEndpoint, reason)` | Requires nonnull action, normalizes null reason to empty string. Retry actions require endpoint; COMPLETE/STOP_UNCERTAIN forbid it. No reason length/sanitization or membership check. |

Transient statuses are RESOURCE_EXHAUSTED, DEADLINE_EXCEEDED, NO_LEADER_KNOWN and
LEADER_NOT_READY. INDETERMINATE stops even when class/flag would otherwise permit
retry and even at the attempt budget boundary. NOT_LEADER with hint is checked
**before retry safety**, so it can redirect NEVER or nondeduplicated operations
when attempts remain. The policy assumes the redirect response is a safe rejection;
it does not itself prove the server crossed no mutation/uncertainty boundary.
Without a hint, NOT_LEADER falls through to terminal completion.

RetryAction has COMPLETE, RETRY_SAME_ENDPOINT, RETRY_PREFERRED_LEADER and
STOP_UNCERTAIN, with no explicit methods. RemoteRetryPolicy has an implicit default
constructor, excluded from the explicit-declaration inventory. Policy uses
ClientStatus, not RpcStatus; status conversion and actual loop ownership must be
traced in the higher-level client.

## Principal Identity Function

Source: [RemoteClientIdentity](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteClientIdentity.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteClientIdentity.RemoteClientIdentity(principal)` | Rejects null/blank, length >256 Java characters, NUL or ISO control characters, then strips outer whitespace. Validation length is before stripping. Stores immutable text; no credential verification, case normalization or authorization lookup. |

The comment's authenticated-identity wording describes intended use. A caller can
construct this record with any accepted text; this pool does not share a global
identity-indexed channel registry or transmit the principal to RpcClient.call.
Avoid describing metadata validation as a security boundary without concrete
transport/security integration evidence.

## Coverage and Verification

Compiler-tree checks cover **19/19 explicit declarations (100%; 0% remaining)**
in these six selected files, including both reservation overloads. This is not full
aether-client/API/codec coverage. Resolver tests check preference/discovery/bounds;
pool tests check least-busy selection, completion release, exhaustion, shutdown and
explicit candidates; policy tests check redirect ordering, uncertainty, dedup flag
and attempt cap. They use transport doubles, not authenticated network services,
and do not exercise every cancellation/close race or malformed resolver list.

See [RemoteEndpointResolverTest](../../modules/aether-client/src/test/java/io/aetherdb/client/RemoteEndpointResolverTest.java),
[RemoteConnectionPoolTest](../../modules/aether-client/src/test/java/io/aetherdb/client/RemoteConnectionPoolTest.java)
and [RemoteRetryPolicyTest](../../modules/aether-client/src/test/java/io/aetherdb/client/RemoteRetryPolicyTest.java).
