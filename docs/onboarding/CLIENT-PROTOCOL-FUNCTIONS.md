# Client Protocol Contract Functions

[Function index](FUNCTION-INDEX.md) | [Message codecs](CLIENT-CODEC-FUNCTIONS.md) | [Remote facade](REMOTE-FACADE-FUNCTIONS.md) | [RPC API](RPC-API-FUNCTIONS.md)

Source: [aether-client-api](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api).
All **22 explicit declarations across 11 files** are covered below. ClientStatus
and the nested mutation Type enum have no explicit methods. Compiler-generated
record accessors, equality and enum helpers are not included in that count.

## Protocol Architecture

```text
application -> request records -> client codecs -> successful RPC envelope
  -> application response record -> RemoteAetherClient outcome/retry mapping
```

These records own bounded physical key/value bytes and describe application
outcomes. They do not open sockets, enforce authorization, apply writes, compare
configuration versions with a live cluster, or implement deduplication. A valid
record is not evidence that any server supports the requested operation.
Application ClientStatus is distinct from the outer RpcStatus: an RPC envelope
can succeed while its decoded application response reports failure.

Every explicitly overridden byte-array accessor returns a fresh copy. Constructors
also copy arrays; mutating caller buffers cannot change a validated request.
Record-generated equality/hashCode still use array identity, not byte-content
equality. List.copyOf freezes list membership and rejects null elements; entries
are immutable records whose byte accessors copy their internal buffers.

## Point Reads

Sources: [ClientGetRequest.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientGetRequest.java),
[ClientGetResponse.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientGetResponse.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientGetRequest.ClientGetRequest(configurationVersion, key)` | Requires nonnegative version and a nonnull physical key of 1..65,536 bytes, otherwise IllegalArgumentException. Copies the key. Does not validate namespace/key encoding or establish a read consistency mode. |
| `ClientGetRequest.key()` | Returns a new array copy, including on repeated calls. |
| `ClientGetResponse.ClientGetResponse(status, value, detail)` | Requires nonnull status/value/detail, value at most 16 MiB and detail at most 4,096 Java String characters. A non-OK status must have an empty value. OK may carry an empty value. Copies the value; invalid bounds/consistency raise IllegalArgumentException. |
| `ClientGetResponse.value()` | Returns a new value copy. No buffer borrowing or zero-copy view. |

The version field is a claim by the caller. The constructor accepts zero and does
not inspect any cluster state. Detail bounds count UTF-16 code units, not encoded
UTF-8 bytes; message codecs have separate wire-size responsibilities.

## Scan Pages

Sources: [ClientScanEntry.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientScanEntry.java),
[ClientScanRequest.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientScanRequest.java),
[ClientScanResponse.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientScanResponse.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientScanEntry.ClientScanEntry(key, value)` | Requires nonnull key/value, key length 1..65,536 and value length 0..16 MiB. Copies both arrays; invalid input raises IllegalArgumentException. |
| `ClientScanEntry.key()` | Returns a new physical-key copy. |
| `ClientScanEntry.value()` | Returns a new value copy. |
| `ClientScanRequest.ClientScanRequest(configurationVersion, startInclusive, endExclusive, maxEntries, pageToken)` | Requires nonnegative version, nonnull/nonempty bounds each at most 65,536 bytes, maxEntries 1..10,000 and nonnull token at most 4,096 bytes. Copies all three arrays. Does not compare start/end, validate a token, or require a nonempty token for SCAN_NEXT. |
| `ClientScanRequest.startInclusive()` | Returns a new lower-bound copy. |
| `ClientScanRequest.endExclusive()` | Returns a new upper-bound copy. |
| `ClientScanRequest.pageToken()` | Returns a new opaque token copy. An empty token is allowed. |
| `ClientScanResponse.ClientScanResponse(status, entries, nextPageToken, detail)` | Requires nonnull fields, at most 10,000 entries, token at most 4,096 bytes and detail at most 4,096 characters. Copies list membership and token. Non-OK responses must have no entries and no token. Null list elements raise NullPointerException through List.copyOf; other explicit validation failures raise IllegalArgumentException. |
| `ClientScanResponse.nextPageToken()` | Returns a new continuation-token copy. |

A page's per-entry limits do not impose a 16 MiB aggregate page limit here. The
constructor also does not enforce sorted entries, unique keys, a common snapshot,
progress between tokens or a relationship between page size and the original
request. Those are higher-level protocol/implementation responsibilities.
Generated entries() returns the immutable list, not a fresh list each time.

## Write Identity And Mutation Ownership

Sources: [ClientWriteOperation.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientWriteOperation.java),
[ClientWriteRequest.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientWriteRequest.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientWriteOperation.ClientWriteOperation(type, key, value)` | Requires nonnull type/key/value, key at most 65,536 bytes and value at most 16 MiB. DELETE requires an empty value; PUT permits empty values. Unlike get/scan keys, an empty write key is accepted. Clones both arrays; invalid input raises IllegalArgumentException. |
| `ClientWriteOperation.key()` | Returns a fresh key clone. |
| `ClientWriteOperation.value()` | Returns a fresh value clone, including an empty DELETE value. |
| `ClientWriteRequest.ClientWriteRequest(configurationVersion, operations)` | Delegates to the canonical constructor with NO_COMMAND_ID (zero UUID) and deduplicated=false. Does not generate an identity. |
| `ClientWriteRequest.ClientWriteRequest(configurationVersion, commandId, deduplicated, operations)` | Rejects null commandId, copies operations with List.copyOf, then requires nonnegative version and 1..10,000 operations. deduplicated=true requires a nonzero UUID; false requires exactly the zero UUID. Null list/elements fail through List.copyOf with NullPointerException; other explicit validation failures raise IllegalArgumentException. |
| `ClientWriteRequest.deduplicated(configurationVersion, commandId, operations)` | Constructs the canonical record with deduplicated=true. Caller supplies the nonzero command ID; this helper neither generates IDs nor stores retry history. |

Type is PUT or DELETE. Operation order and duplicate keys are preserved; the
record does not normalize repeated mutations. The list bound and per-value bounds
do not impose a total batch byte limit. Generated operations() returns the
immutable list. Server-side command/content conflict detection is not implemented
by this record, and the word deduplicated does not itself guarantee retry safety.

## Write Outcomes And Redirects

Sources: [ClientWriteResponse.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientWriteResponse.java),
[ClientEndpointHint.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientEndpointHint.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientWriteResponse.ClientWriteResponse(status, commandId, operationCount, firstSequence, lastSequence, leaderHint, detail)` | Requires nonnull status/UUID/detail, nonnegative operation count/sequences, firstSequence <= lastSequence and detail at most 4,096 characters. OK additionally requires positive count and both sequences nonzero. NOT_LEADER requires a hint; every other status forbids a hint. Invalid input raises IllegalArgumentException. Zero command UUID remains allowed. |
| `ClientEndpointHint.ClientEndpointHint(host, port, expectedNodeId)` | Rejects null/blank host, NUL/control characters, slash, @, ://, port outside 1..65,535 and an explicitly zero expected UUID. Strips outer host whitespace. Null expectedNodeId is allowed. Does not resolve DNS, verify node identity, authenticate a redirect or fully validate host syntax. All explicit rejection uses IllegalArgumentException. |

Non-OK responses may still carry nonzero operation counts and sequence numbers;
the constructor does not enforce status-specific outcome certainty beyond the
listed rules. It does not require operationCount to match sequence-range length,
cap it at 10,000, or check a response ID against its request. A hint is metadata,
not a trusted leader proof. The remote routing layer separately decides whether
and how to follow it.

## Codes And Status Vocabulary

Source: [ClientProtocol.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientProtocol.java).
`ClientProtocol.ClientProtocol()` is a private empty constructor that prevents
normal instantiation; this class contains constants rather than runtime routing.

| Constant | Numeric operation code | Declared purpose |
| --- | --- | --- |
| LEADER_QUERY | 16384 | Discover leader |
| CLIENT_WRITE | 16385 | Submit write |
| CLIENT_GET | 16386 | Point read |
| SCAN_OPEN | 16387 | Open scan |
| SCAN_NEXT | 16388 | Next page |
| SCAN_CLOSE | 16389 | Close scan |
| READ_INDEX | 8195 | Linearizable read barrier |

Constants do not register handlers. The current RemoteAetherClient facade exposes
write/get/scan operations, not a public method for every code above.

[ClientStatus.java](../../modules/aether-client-api/src/main/java/io/aetherdb/client/api/ClientStatus.java)
defines 20 application outcomes:

| Group | Status values | Meaning at this contract boundary |
| --- | --- | --- |
| Success/absence | OK, NOT_FOUND | Completed operation or absent key/resource |
| Leadership | NOT_LEADER, LEADER_NOT_READY, NO_LEADER_KNOWN | Redirect, readiness incomplete, or unknown leader |
| Request/configuration | INVALID_ARGUMENT, CONFIGURATION_MISMATCH, COMMAND_ID_CONFLICT | Invalid arguments, incompatible configuration, or reused command ID with different content |
| Capacity/time/uncertainty | RESOURCE_EXHAUSTED, DEADLINE_EXCEEDED, INDETERMINATE | Bound exhausted, elapsed deadline, or unknown final write outcome |
| Engine/read policy | ENGINE_FAILED, READ_MODE_DISABLED | Local engine failed or requested read mode disabled |
| Scan state | SCAN_NOT_FOUND, SCAN_EXPIRED, SCAN_PAGE_MISMATCH | Unknown cursor, expired lease, or mismatched token |
| Integrity/size | RESULT_TOO_LARGE, DATA_LOSS | Response bound exceeded or integrity validation failed |
| Access control | UNAUTHENTICATED, PERMISSION_DENIED | Missing authentication or insufficient permission |

These names do not prescribe a universal retry policy. Read the
[remote facade](REMOTE-FACADE-FUNCTIONS.md) and [routing policy](REMOTE-ROUTING-FUNCTIONS.md)
for actual mapping and retry decisions. In particular, INDETERMINATE is not a
promise that no write was applied.

## Verification And Ownership

The compiler-tree coverage test checks all 22 declarations and both constructor
overloads. Existing module tests cover write identity and write-response rules;
they do not exhaustively test get/scan validation or every host form. Codec tests
and facade tests provide separate evidence, not a replacement for these contracts.
Use disposable tests rather than a live database when changing byte bounds,
status rules or command identity behavior. Documentation does not certify a
production distributed service or change the frozen ML experiment implementation.
