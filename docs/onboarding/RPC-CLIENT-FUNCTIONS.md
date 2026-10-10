# Development RPC Client and Admission Functions

[Function index](FUNCTION-INDEX.md) | [Server and sockets](RPC-SERVER-FUNCTIONS.md) | [Transport limits](RPC-TRANSPORT-LIMITS.md)

Source: [PlaintextDevelopmentRpc](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/PlaintextDevelopmentRpc.java).
This page covers the remaining **22 explicit declarations** in DevelopmentClient,
ClientConnection, PendingCall and admission helpers. Together with the server
reference, all **54 explicit declarations** in this file are documented.

## Client Architecture

```text
call -> validate arguments / retry class
  -> canonical host:port connection cache -> blocking connect / HELLO if needed
  -> admission snapshot -> semaphore permits -> stream + invocation + pending call
  -> write request fragments synchronously -> schedule timeout -> return future
reader -> RESPONSE identity / bytes / assembly -> remove pending / release -> future
exceptional first attempt + IDEMPOTENT -> evict connection -> one fresh attempt
timeout / cancellation / socket failure -> release accounting + best-effort CANCEL
```

This development implementation is plaintext. Claimed HELLO identity is not
authenticated. The future is asynchronous terminal completion, but call submission
can block on connection/HELLO, semaphore waiting and socket writes before returning.
Remote HELLO limits are validated as a record but are not retained to negotiate
the local frame/message/stream limits downward.

## Client Ownership and Retry Functions

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.DevelopmentClient.DevelopmentClient(identity, configuration)` | Requires nonnull identity/configuration; owns concurrent connection map, atomic closed flag and single-thread scheduled deadline executor using a virtual-thread factory. No socket is opened here. |
| `PlaintextDevelopmentRpc.DevelopmentClient.call(peer, operation, body, options)` | Closed/null/oversized request validation returns failed futures. Automatic retry requires IDEMPOTENT; NEVER and DEDUP_REQUIRED are rejected. Calls once; if retry enabled, wraps first future and retries once only on exceptional completion, closing/removing cached connection. Retry gets same relative timeout and backpressure mode with retry disabled. Any normal response, including non-OK, is returned without retry. |
| `PlaintextDevelopmentRpc.DevelopmentClient.callOnce(peer, operation, body, options)` | Concurrent-map compute by peer.canonical() reuses nonclosed connection or connects, then submits. RuntimeException becomes failed future; Error is not caught. Connect work occurs within compute for that key. No repeated closed-client check here. |
| `PlaintextDevelopmentRpc.DevelopmentClient.connect(endpoint)` | Creates configured socket, connects with fixed 3000 ms connect timeout, writes DIALER HELLO, blocks for first HELLO, validates ACCEPTOR/cluster/optional node pin, starts ClientConnection. IOException/RuntimeException closes partially owned socket, adds cleanup failure as suppressed and wraps in IllegalStateException. No socket read timeout bounds HELLO wait. |
| `PlaintextDevelopmentRpc.DevelopmentClient.close()` | Atomic idempotent flag, closes cached connections, clears map and shutdownNow deadline scheduler. No graceful drain/wait. Map iteration and in-flight compute/retry are not coordinated by one lifecycle lock. |

The connection cache key is canonical host:port, **not expectedNodeId**. Pinning is
checked on connect only; a later endpoint with another expected node can reuse a
nonclosed cached connection without checking that pin again. Different accepted
host spellings can yield different keys for the same actual peer.

Retry removes the key without compare-and-remove of the failed attempt's connection;
concurrent calls can be affected by closing the cached connection. Retries create
new stream/invocation identities and have no durable deduplication path. They
reuse caller body rather than a frozen call-level copy, so caller mutation between
attempts can change the retried bytes. Cancelling the retry wrapper future does
not cancel the underlying attempt automatically. These are source-backed control
flow boundaries, not claims that the existing tests exercise every race.

## Connection Submission Functions

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.ClientConnection.ClientConnection(socket, input, writeLock, peerNodeId, configuration)` | Stores connection resources, requires configuration, creates fair semaphore with outboundPermits, obtains output and starts named virtual reader thread. Owns dialer odd-ID allocator, pending map, inbound counter and closed flag. No dedicated max-stream semaphore or RpcFlowController. |
| `PlaintextDevelopmentRpc.ClientConnection.submit(operation, body, options, deadlines)` | Rejects closed connection via failed future; computes permits and evaluates outbound admission. Rejection fails immediately. Otherwise FAIL_FAST tryAcquire or timed acquisition using full options timeout. Interrupt restores thread flag and returns failed future. After acquisition allocates stream/UUID, assembler bound max(responseLimit,4096) and PendingCall, writes all fragments, then schedules timeout and cancellation callback. Write IOException/RuntimeException removes call/releases permits/fails future/closes connection. |
| `PlaintextDevelopmentRpc.ClientConnection.permits(bytes)` | Computes ceil(bytes/permitBytes) using long intermediate and exact int conversion, minimum one. Internal caller supplies nonnegative body length; no independent input validation. |
| `PlaintextDevelopmentRpc.PendingCall.PendingCall(invocation, assembler, permits)` | Stores invocation/assembler/permit count; field initializers create zero reserved-byte atomic and fresh completion future. Private constructor does not independently validate arguments. |

Admission snapshot can reject before WAIT_UNTIL_DEADLINE reaches timed semaphore
acquisition, so that mode does not wait through every capacity rejection. Fair
Semaphore construction does not make untimed tryAcquire obey waiting order.
Permits remain held until response, timeout, cancellation or failure, not merely
until request bytes have been written. The outbound policy checks permit usage;
the client does not separately enforce configuration.streams() on pending calls.

The timeout task is scheduled **after request writes**, using the full relative
timeout again. Connection/HELLO time, admission waiting and write time are not
deducted. A retry restarts the relative timeout rather than sharing one total
deadline. Wire timeout is clamped to at least 1 ms; submillisecond caller timeouts
therefore have different wire and scheduled durations. Scheduled tasks are not
cancelled on successful completion; later tasks return if pending removal fails.

Stream allocation/PendingCall construction occur after permit acquisition but
outside the write-failure cleanup block. Scheduling occurs after writes and outside
that block too. Exhaustion/allocation/scheduler failure can therefore lack the
usual per-call release path. The guide records actual control flow; it does not
assert that these uncommon failure paths are covered by loopback tests.

## Response and Terminal-State Functions

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.ClientConnection.readLoop()` | Repeatedly obtains/apply frames until closed. IOException/RuntimeException calls failAll then close; Error is not caught. Socket read has no application idle timeout here. |
| `PlaintextDevelopmentRpc.ClientConnection.apply(frame)` | Requires RESPONSE; unknown stream is ignored as late. Validates invocation, reserves declared response bytes on BEGIN via atomic reservation marker, then assembles. Completion removes pending entry, releases permits/inbound, resolves status and creates RpcResponse (non-OK payload becomes UTF-8 detail and empty body). No OK-specific operation-limit check beyond assembler's max(responseLimit,4096). |
| `PlaintextDevelopmentRpc.ClientConnection.reserveClientInbound(bytes)` | CAS adds declared bytes, rejecting aggregate above configuration.messageBytes(), not inboundBytes(). Internal validated length is assumed; no negative check. |
| `PlaintextDevelopmentRpc.ClientConnection.releaseClientInbound(call)` | getAndSet(0) returns reservation once; subtracts nonzero amount from connection counter. Does not reset assembler or erase payload bytes. |
| `PlaintextDevelopmentRpc.ClientConnection.timeout(stream, call)` | Conditional exact pending removal; only winner releases permits/bytes, completes normally with DEADLINE_EXCEEDED response and sends CANCEL. Normal timeout response is not an exceptional retry trigger. |
| `PlaintextDevelopmentRpc.ClientConnection.cancel(stream, call)` | Conditional removal, releases permits/bytes and sends CANCEL. Future is already cancelled by the callback's caller; this helper does not complete another response. |
| `PlaintextDevelopmentRpc.ClientConnection.sendCancel(stream, invocation)` | Writes zero-body CANCEL frame with invocation/stream. IOException closes connection. Synchronous write can block caller or the single deadline scheduler; no separate write timeout. Runtime codec failures are not caught here. |
| `PlaintextDevelopmentRpc.ClientConnection.failAll(failure)` | Snapshots pending values, clears map, then releases resources and completes snapshot futures exceptionally. Snapshot/clear/release is not a transaction with concurrent submit/response/cancel operations. |
| `PlaintextDevelopmentRpc.ClientConnection.close()` | Atomic idempotent closed flag, closes socket ignoring IOException, then failAll with peer-labelled IOException. Does not explicitly join reader or send GOAWAY. |

Assembler bound max(responseLimit,4096) permits error payloads but also allows an OK
payload up to 4096 bytes when operation response limit is smaller. Remote limit
metadata is not used to enforce that distinction after assembly. UTF-8 errors are
decoded with Java replacement behavior, not a strict malformed-text decoder.

apply removes a completed call and releases resources **before** parsing status
and constructing response. Unknown status or invalid detail can throw after that
removal; readLoop's failAll cannot find that call to complete it, and its timeout
also finds no entry. This follows the current ordering, not a tested guarantee
that malformed responses always resolve every future. Likewise, response removal
is unconditional while timeout/cancel use conditional removal; source inspection
does not establish exactly-once accounting under all races.

Zero declared length leaves the reservation marker at zero, so it is not an
independent BEGIN-seen flag; assembler still governs fragment validity. Unknown
stream responses are ignored only after frame-level checks and type validation.
No PING/PONG/WINDOW_UPDATE processing occurs in the client apply path.

## Admission Snapshot Functions

| Function | Behavior and boundaries |
| --- | --- |
| `PlaintextDevelopmentRpc.outboundAdmission(operation, bodyBytes, permits, usedPermits, draining)` | Delegates to configuration overload with static development defaults. |
| `PlaintextDevelopmentRpc.outboundAdmission(operation, bodyBytes, permits, usedPermits, draining, configuration)` | Evaluates fresh outbound policy against snapshot VIRTUAL_THREAD_INFLIGHT=max(0,usedPermits) and RpcAdmission outbound request. Does not atomically reserve semaphore or acquire a lease. |
| `PlaintextDevelopmentRpc.inboundAdmission(operation, bodyBytes, usedBytes, activeStreams, draining)` | Delegates to configuration overload with static defaults. |
| `PlaintextDevelopmentRpc.inboundAdmission(operation, bodyBytes, usedBytes, activeStreams, draining, configuration)` | Evaluates inbound-byte/inflight-stream policy against clamped nonnegative usage. Null operation uses byte-only request (unknown operation), otherwise operation-specific bounds. No resource acquisition; caller subsequently reserves bytes/installs stream. |

AdmissionController is shared static evaluator, but snapshots/budgets here are
per connection. Clamping measured usage to zero is not validation of the supplied
measurements. Request builders still reject negative charges and known-operation
oversize. Race-safe resource ownership requires subsequent semaphore/CAS/map logic,
not an accepted snapshot decision alone.

## Coverage and Verification

Compiler-tree checks cover **22/22 declarations in this partition** and verify that
server and client partitions are disjoint and together cover **54/54 (100%; 0%
remaining)** in PlaintextDevelopmentRpc. The four transport-support files add 11
declarations: transport main-source total is **65/65 explicit declarations**.
Generated record/enum methods and lambda bodies are outside this count.

[PlaintextDevelopmentRpcTest](../../modules/aether-rpc-transport/src/test/java/io/aetherdb/rpc/transport/PlaintextDevelopmentRpcTest.java)
checks multiplexing, cancellation, identity rejection, unsafe retry rejection and
admission limits. Passing those checks is not evidence that all cleanup races,
malformed responses, pin changes or scheduler failures above are exercised.
This reference changes documentation, not transport behavior or production status.
