# RPC API and Admission Functions

[Function index](FUNCTION-INDEX.md) | [RPC codecs](RPC-FRAME-FUNCTIONS.md) | [Module guide](MODULE-GUIDE.md)

Source package: [aether-rpc-api](../../modules/aether-rpc-api/src/main/java/io/aetherdb/rpc/api).
This guide covers all **33 explicit declarations** in the package, including
abstract interface methods. Generated record accessors/equality and enum
values/valueOf are outside that count. Three policy enums have no explicit methods.

## Contract Architecture

```text
caller -> endpoint + operation descriptor + call options + body
  -> RpcClient.call -> asynchronous RpcResponse
server -> operation registry -> admitted, assembled RpcServerRequest
  -> RpcHandler.handle -> RpcResponder.success / fail
admission helpers -> resource charges / outcome mapping
  (controller acquisition, lease release and dispatch belong to implementations)
```

The API separates immutable metadata/byte ownership from transport execution.
It does not open sockets, authenticate a node, schedule retries, deduplicate
mutations, enforce an executor policy or acquire admission resources by itself.
This is the general Java RPC path, not the Python training-cache wire protocol.

## Endpoints and Call Policy

| Function | Behavior and boundaries |
| --- | --- |
| `RpcEndpoint.RpcEndpoint(host, port, expectedNodeId)` | Rejects null/blank host, NUL/control characters, slash, @ or ://, port outside 1..65535 and an explicitly zero expected UUID. Strips outer whitespace before storing. Null expected identity means no node pin. Does not resolve DNS, normalize case or fully parse an IPv6/domain literal. |
| `RpcEndpoint.of(host, port)` | Constructs validated endpoint with null expectedNodeId; no authentication guarantee. |
| `RpcEndpoint.canonical()` | Returns host:port, surrounding host with brackets whenever it contains a colon. Pure formatting: already-bracketed input can receive another pair and distinct accepted spellings need not produce one canonical identity. |
| `RpcOperationDescriptor.RpcOperationDescriptor(operationCode, requestLimit, responseLimit, retryClass, executionPolicy, defaultTimeout)` | Requires positive operation code, request/response limits 0..64 MiB, nonnull policy/class/timeout and positive timeout. Invalid code/limits/duration raise IllegalArgumentException; null metadata raises NullPointerException. Does not cap timeout at one hour or check registry uniqueness. |
| `RpcCallOptions.RpcCallOptions(timeout, automaticRetry, backpressureMode)` | Requires nonnull, positive duration <=1 hour and nonnull backpressure mode, otherwise IllegalArgumentException. Stores retry preference without checking operation safety. Does not start a timer or convert duration to an absolute deadline. |
| `RpcCallOptions.defaults(operation)` | Rejects null operation, takes declared defaultTimeout, disables automatic retries and selects FAIL_FAST. A descriptor with a positive timeout >1 hour is valid itself but fails here through call-options validation. |

RpcBackpressureMode has FAIL_FAST and WAIT_UNTIL_DEADLINE. These are caller policy
labels, not a queue implementation. RpcRetryClass has NEVER, IDEMPOTENT and
DEDUP_REQUIRED: server deduplication must actually exist before that last label
can make retries safe. RpcExecutionPolicy has CONTROL, STORAGE_READ and
STORAGE_WRITE; executor separation and write admission are transport/server work.
The operation-specific limits can be stricter than the global record limits.

## Client, Registry and Handler Interfaces

| Function | Behavior and boundaries |
| --- | --- |
| `RpcClient.call(peer, operation, body, options)` | Abstract unary asynchronous call returning CompletableFuture<RpcResponse>. Implementation owns input validation, body retention/copying, connection selection, deadlines, admission, retry and terminal completion. Interface supplies no default algorithm or cancellation mapping. |
| `RpcClient.close()` | Abstract AutoCloseable lifecycle contract to drain/release owned connections. Interface does not specify a concrete wait duration, idempotency or fate of outstanding futures. |
| `RpcServer.register(operation, handler)` | Abstract contract to register a unique operation before or while serving. Duplicate handling, concurrency and descriptor/handler validation must be inspected in implementation. |
| `RpcServer.endpoint()` | Abstract getter for actual bound endpoint, including any assigned ephemeral port. RpcEndpoint itself requires a positive port; ephemeral binding therefore precedes constructing this result. |
| `RpcServer.close()` | Abstract contract to stop admission gracefully and release listener; no transport shutdown algorithm here. |
| `RpcHandler.handle(request, responder)` | Functional-interface callback for an admitted request, contractually completing responder once. No return value or built-in exception handling; handler blocking/cancellation/error mapping depends on server execution. |
| `RpcResponder.success(body)` | Abstract bounded successful completion. Interface does not copy bytes or guard duplicate completion itself. |
| `RpcResponder.fail(status, detail)` | Abstract completion with non-OK status and bounded safe detail. Enforcement of non-OK, detail sanitization and exactly-once behavior belongs to concrete responder. |

Do not infer persistence or exactly-once mutation execution from exactly-once
response completion. A handler can mutate storage and then lose its response;
retry safety requires operation semantics and, where applicable, durable dedup.

## Request and Response Ownership

| Function | Behavior and boundaries |
| --- | --- |
| `RpcServerRequest.RpcServerRequest(operationCode, invocationId, body, deadline, cancellation)` | Requires positive code, nonzero UUID, nonnull body <=64 MiB and nonnull deadline/token. Invalid fields raise IllegalArgumentException. Copies body. Does not require deadline to be future, compare body to registered operation limit or authenticate invocation identity. |
| `RpcServerRequest.body()` | Returns a new array copy on every call; no borrowed buffer. |
| `RpcResponse.RpcResponse(status, invocationId, body, errorDetail)` | Requires status, nonzero UUID, nonnull body <=64 MiB and nonnull detail <=4096 Java characters, then copies body. Does not enforce operation response limit, restrict body/detail by status or sanitize detail. |
| `RpcResponse.body()` | Returns a fresh copy of exact stored bytes each time. |
| `RpcResponse.ok(invocationId, body)` | Constructs validated OK response with empty detail; constructor copies payload and enforces global bounds. |

Record byte copies isolate later caller mutation, but the cancellation token is a
shared behavior object and can change state. Instant/UUID/Duration metadata are
immutable. The 4096-character detail bound is not a UTF-8 byte bound or proof that
secrets/control characters were removed. Generated record equality compares array
components by their usual reference equality, not deep byte content; use explicit
byte comparisons where content equality matters.

## Cooperative Cancellation

| Function | Behavior and boundaries |
| --- | --- |
| `RpcCancellationToken.isCancelled()` | Abstract observation of requested cancellation. Memory visibility and synchronization depend on implementation. |
| `RpcCancellationToken.onCancel(callback)` | Abstract registration contract, calling immediately if already cancelled. Interface does not prescribe null handling, callback execution thread, ordering or exception policy. |
| `RpcCancellationToken.throwIfCancelled()` | Default method observes isCancelled once and throws RpcCancelledException with fixed message if true. Otherwise returns normally; does not interrupt a thread, roll back storage or prevent later cancellation. |
| `RpcCancelledException.RpcCancelledException(message)` | Delegates to unchecked RuntimeException, with fixed serialVersionUID=1. Does not itself complete a future or send CANCELLED on wire. |

Cancellation is best effort and cooperative. A non-cancelled observation can race
with a subsequent request; concrete handlers must choose safe interruption points.

## Admission Charges and Outcome Mapping

| Function | Behavior and boundaries |
| --- | --- |
| `RpcAdmission.RpcAdmission()` | Private constructor for static charge-builder utility. |
| `RpcAdmission.outboundRequest(operation, bodyBytes, outboundPermits, draining)` | Rejects null operation, negative byte/permit counts or bytes above requestLimit. Creates charges for outbound bytes, one inflight stream and optional positive virtual-thread permits, then AdmissionRequest with supplied draining flag. Does not charge frame headers, responses or connection count. |
| `RpcAdmission.inboundRequest(operation, bodyBytes, draining)` | Checks nonnull operation and nonnegative bytes within requestLimit, then delegates to the byte-only overload. |
| `RpcAdmission.inboundRequest(bodyBytes, draining)` | Rejects negative bytes; creates inbound-byte and one-inflight-stream charges. Has no operation/global message-size check, so caller must validate length separately. |
| `RpcAdmissionMapper.RpcAdmissionMapper()` | Private constructor for static outcome mapper. |
| `RpcAdmissionMapper.status(decision)` | Rejects null. Maps ACCEPTED to OK, REJECTED_BEFORE_ACK/RESOURCE_EXHAUSTED to RESOURCE_EXHAUSTED, DRAINING_REJECTED to UNAVAILABLE and UNCERTAIN to FAILED_PRECONDITION. Reasons and retry-after are not encoded by this function. |
| `RpcAdmissionMapper.retryable(decision)` | Rejects null. True only for REJECTED_BEFORE_ACK, DRAINING_REJECTED or RESOURCE_EXHAUSTED; false for ACCEPTED/UNCERTAIN. This is admission classification, not permission to retry a particular operation or evidence of deduplication. |

Builders create accounting requests, not acquired leases. A controller must accept
the request and the owner must release resources on every completion/failure path.
The caller supplies draining rather than these helpers reading server state.
UNCERTAIN is deliberately not classified retryable: it can reflect an outcome
that cannot safely be treated as a clean rejection.

## Stable Status Functions

| Function | Behavior and boundaries |
| --- | --- |
| `RpcStatus.RpcStatus(code)` | Private enum constructor stores frozen wire code. |
| `RpcStatus.code()` | Returns code without encoding, mapping an exception or emitting a response. |
| `RpcStatus.fromCode(code)` | Scans statuses for exact code and returns match; unknown code raises IllegalArgumentException, not codec RpcProtocolException. |

Codes are OK=0, CANCELLED=1, INVALID_ARGUMENT=2, DEADLINE_EXCEEDED=3, NOT_FOUND=4,
ALREADY_EXISTS=5, RESOURCE_EXHAUSTED=6, FAILED_PRECONDITION=7, UNAVAILABLE=8,
INTERNAL=9, UNAUTHENTICATED=10 and PROTOCOL_ERROR=11. Status names describe outcomes;
they do not implement authentication, error sanitation or retry schedules.

## Coverage and Verification

Compiler-tree coverage checks all **33/33 explicit declarations (100%; 0%
remaining)** across all 17 implementation files, including both inbound overloads.
[RpcAdmissionTest](../../modules/aether-rpc-api/src/test/java/io/aetherdb/rpc/api/RpcAdmissionTest.java)
checks charges, operation bounds, status mapping and retry classification. Those
five tests do not exercise a network server, handler execution or all record
constructor boundaries. Concrete transport behavior is documented separately in
the [server](RPC-SERVER-FUNCTIONS.md) and [client](RPC-CLIENT-FUNCTIONS.md) references;
this guide is not proof of a production cluster.
