# Remote Client Facade and Typed Collection Functions

[Function index](FUNCTION-INDEX.md) | [Routing and retries](REMOTE-ROUTING-FUNCTIONS.md) | [RPC client](RPC-CLIENT-FUNCTIONS.md)

Sources: [aether-client](../../modules/aether-client/src/main/java/io/aetherdb/client).
This page covers **27 explicit declarations** in RemoteAetherClient,
RemoteTypedCollection and the three terminal result records. Together with the
routing reference, all **46 explicit declarations** in this module are covered.
Client API records have a separate [protocol contract reference](CLIENT-PROTOCOL-FUNCTIONS.md);
[message codecs](CLIENT-CODEC-FUNCTIONS.md) remain separate module responsibilities.

## Application Call Architecture

```text
typed collection -> embedded-compatible key/value envelopes -> client request
RemoteAetherClient -> encode once -> first resolver candidate -> pool.callOn
  -> RPC response -> protocol decode / status mapping -> retry decision
  -> same endpoint or write leader redirect -> terminal remote result
typed adapter -> join -> Found / NotFound / Applied / Rejected / Indeterminate
scanAll -> 1024-entry pages until empty token -> materialized immutable list
```

The facade chooses an explicit first resolver candidate rather than pool.call's
least-busy selection. It does not create sockets, install server handlers or prove
the remote server deduplicates writes. Typed envelopes share embedded formats,
not an automatic schema negotiation or authenticated cluster service.

## Facade Entry Functions

Source: [RemoteAetherClient](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteAetherClient.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteAetherClient.RemoteAetherClient(pool, resolver, retryPolicy, writeOptions, maximumAttempts)` | Requires collaborators/options, attempt cap 1..16. Does not verify pool and facade share same resolver or transport supports retry options. No independent closed flag. |
| `RemoteAetherClient.defaultWriteOptions()` | Returns 10-second timeout, automaticRetry=true, FAIL_FAST. This transport retry preference is distinct from facade's attempt loop. |
| `RemoteAetherClient.write(request)` | Requires request, encodes once, chooses DEDUP_REQUIRED descriptor for deduplicated request else NEVER, selects first candidate and calls writeAttempt at attempt 1. Encoding/resolver errors are synchronous before future creation. |
| `RemoteAetherClient.get(request)` | Requires request, encodes point-read body once, selects first candidate and begins getAttempt. Does not use pool's least-busy selector. |
| `RemoteAetherClient.scan(request)` | Chooses SCAN_OPEN for empty page token, SCAN_NEXT otherwise, encodes once and starts on first candidate. Each call fetches one page, not an entire scan. |
| `RemoteAetherClient.close()` | Delegates to pool.close; no own idempotency or close coordination. |

Static descriptors: writes use request bound 32 MiB plus write header, response
1 MiB, STORAGE_WRITE and default 10 seconds. GET uses request 65536+64 bytes,
response 16 MiB+64, IDEMPOTENT/STORAGE_READ and default 5 seconds. Scan open/next
use request 140000, response 16 MiB+128, IDEMPOTENT/STORAGE_READ and default 10
seconds. Actual submissions use the supplied writeOptions for **all** operations,
so GET descriptor's 5-second default is not automatically selected.

## Attempt and Conversion Functions

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteAetherClient.writeAttempt(request, operation, body, endpoint, attempt)` | Submits via callOn. Any exceptional completion becomes DEADLINE_EXCEEDED outcome with failure message. Nonempty RPC OK body decodes ClientWriteResponse, including client status/leader hint/count/sequences; otherwise maps RPC status. Policy can recurse same endpoint or observe/retry hinted leader. Terminal result preserves request command ID, encoded response body and facade attempt number. |
| `RemoteAetherClient.getAttempt(request, body, endpoint, attempt)` | Submits GET using writeOptions. Failure becomes DEADLINE_EXCEEDED; nonempty OK body decodes read status/detail/value, other RPC response maps status with empty value. Policy receives no leader hint and can retry same endpoint only. Returns terminal RemoteReadResult. |
| `RemoteAetherClient.scanAttempt(request, operation, body, endpoint, attempt)` | Submits selected scan operation using writeOptions. Failure becomes deadline outcome with empty page/token; nonempty OK body decodes page; other responses map status to empty page/token. Policy receives no hint and can retry same endpoint. Returns RemoteScanResult. |
| `RemoteAetherClient.leaderHint(response)` | Converts optional client endpoint hint to RpcEndpoint including expected-node pin. Null remains null; endpoint validation can throw. Does not authenticate hint or add it to resolver here. |
| `RemoteAetherClient.mapStatus(status)` | Maps RPC to ClientStatus: OK->OK; INVALID_ARGUMENT/PROTOCOL_ERROR->INVALID_ARGUMENT; DEADLINE_EXCEEDED/CANCELLED->DEADLINE_EXCEEDED; NOT_FOUND->NOT_FOUND; RESOURCE_EXHAUSTED unchanged; FAILED_PRECONDITION->INDETERMINATE; UNAVAILABLE->NO_LEADER_KNOWN; INTERNAL/ALREADY_EXISTS->ENGINE_FAILED; UNAUTHENTICATED unchanged. Null fails switch. |

Attempts retain the same encoded request body and write command ID. Retries have
no backoff, shared absolute deadline or endpoint failover for NO_LEADER_KNOWN;
policy retries the same endpoint. Read/scan protocol responses do not supply the
write leader-hint path. Resolver.observeLeader can fail at capacity; a facade
resolver different from pool resolver can yield a hint rejected by pool.callOn.

Every exceptional transport outcome is labelled deadline, including local
admission, closed pool or authentication/connect failures. Protocol decoding errors
inside handle instead propagate exceptionally through the future chain; they are
not converted by that same failure branch. An empty RPC OK body is accepted as OK
without protocol decode: writes can have zero counts/sequences, reads empty value,
and scans empty entries/token. Do not infer a fully validated application response
from the outer RPC status alone.

Default automaticRetry=true is incompatible with PlaintextDevelopmentRpc's
requirement that transport automatic retries use IDEMPOTENT: both write descriptors
are NEVER or DEDUP_REQUIRED. That transport rejects them before submission; the
facade then labels the failure DEADLINE_EXCEEDED and may repeat it for deduplicated
writes up to its attempt cap. Existing facade tests use doubles that accept options;
they do not certify this default integration. Reads/scans can use both transport
and facade retry layers, so result.attempts counts facade attempts, not all wire
attempts. This guide records behavior without changing defaults/runtime.

Private `AttemptOutcome`, `ReadAttemptOutcome` and `ScanAttemptOutcome` records have only
generated methods. Future chaining does not automatically propagate cancellation
through every dependent stage to underlying RPC work.

## Typed Adapter Functions

Source: [RemoteTypedCollection](../../modules/aether-client/src/main/java/io/aetherdb/client/RemoteTypedCollection.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteTypedCollection.RemoteTypedCollection(client, definition)` | Requires client/definition; stores references without opening collection, contacting server or validating remote schema compatibility. |
| `RemoteTypedCollection.definition()` | Returns configured collection definition. |
| `RemoteTypedCollection.get(key)` | Encodes typed key and epoch-zero ClientGetRequest, blocks on join. OK decodes value into Found; NOT_FOUND becomes NotFound; other statuses throw detail-or-status IllegalStateException. Does not explicitly enforce POINT_READ capability here. Source comment saying read contract is unimplemented is stale relative to this method's body. |
| `RemoteTypedCollection.put(key, value)` | Requires POINT_WRITE, encodes typed key/value envelopes and submits one PUT operation. Encoding can throw before network work. |
| `RemoteTypedCollection.delete(key)` | Requires POINT_WRITE, encodes typed key and empty DELETE value, submits one operation. |
| `RemoteTypedCollection.scanAll()` | Requires RANGE_SCAN; repeatedly submits epoch-zero prefix/prefixEnd range with page size 1024 and previous token, joins, requires OK and decodes entries. Stops at empty next token; returns immutable fully materialized list. No repeated-token detection, page/byte total cap or verification of global order/snapshot continuity. |
| `RemoteTypedCollection.submit(operation)` | Generates fresh command UUID per logical call, submits epoch-zero deduplicated single-operation request and joins. OK maps response counts/sequences to Applied; INDETERMINATE returns command-bearing Indeterminate; others return Rejected with retryability. Re-invoking put/delete later generates another command ID. |
| `RemoteTypedCollection.ensurePointWrite()` | Throws UnsupportedOperationException unless definition declares POINT_WRITE. Does not perform authorization. |
| `RemoteTypedCollection.rejectionReason(result)` | Uses status name when detail blank, otherwise detail as supplied. No sanitization. |
| `RemoteTypedCollection.retryable(status)` | True for RESOURCE_EXHAUSTED, DEADLINE_EXCEEDED, NO_LEADER_KNOWN or LEADER_NOT_READY only. This is output classification, not a performed retry or verified safe resubmission. |

join can throw CompletionException for exceptional completion; synchronous typed
API callers therefore block despite facade returning futures. scanAll holds all
decoded values in memory and exposes no cursor close/cancellation mechanism.
Typed methods use epoch zero; collection definition/envelopes provide identity,
but do not implement routing-epoch refresh or prove scan token stability across
revisions. Indeterminate command ID is retained for outcome investigation; retrying
the typed method is not automatically the same deduplicated command.

## Terminal Result Functions

| Function | Behavior and boundaries |
| --- | --- |
| `RemoteReadResult.RemoteReadResult(status, attempts, value, detail)` | Requires status, attempts>0, value <=16 MiB and detail <=4096 characters; non-OK must have empty value. Copies value. Does not cap attempts at facade max or sanitize detail. |
| `RemoteReadResult.value()` | Returns fresh defensive byte copy. |
| `RemoteScanResult.RemoteScanResult(status, attempts, entries, nextPageToken, detail)` | Requires status/positive attempts, entries <=10000, token <=4096 bytes and detail <=4096 characters. List.copyOf rejects null entries; copies token. Does not require empty page/token for non-OK or validate sorted/unique entries. |
| `RemoteScanResult.nextPageToken()` | Returns fresh token copy. Generated entries accessor exposes immutable copied list; entry byte ownership belongs to ClientScanEntry. |
| `RemoteWriteResult.RemoteWriteResult(status, commandId, attempts, operationCount, firstSequence, lastSequence, body, detail)` | Requires status/UUID, positive attempts, nonnegative counts/sequences and ordered sequence range, nonnull body/detail and detail <=4096 characters. Copies body, with no body-size bound here. Zero command UUID is accepted; status-specific count/sequence consistency is not checked. |
| `RemoteWriteResult.body()` | Returns fresh response-body copy. |

Record-generated equality is not deep byte-array equality. Array ownership is
defensive at construction/getter, not zero-copy. Body/detail limits and status
invariants differ between these result records and application wire records.

## Coverage and Verification

Compiler-tree checks cover **27/27 declarations in these five files** and together
with routing prove **46/46 (100%; 0% remaining)** explicit declarations across all
11 aether-client implementation files. Generated methods/lambda bodies are excluded.
The client API and codec modules remain outside this inventory.

[RemoteAetherClientTest](../../modules/aether-client/src/test/java/io/aetherdb/client/RemoteAetherClientTest.java)
and [RemoteTypedCollectionTest](../../modules/aether-client/src/test/java/io/aetherdb/client/RemoteTypedCollectionTest.java)
check write retry/redirect, read/scan decoding and typed mappings using transport
doubles. They do not establish production authentication, server deduplication,
scan snapshot consistency or compatibility of default options with plaintext RPC.
