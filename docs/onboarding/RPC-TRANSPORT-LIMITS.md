# RPC Transport Limits and Identity Functions

[Function index](FUNCTION-INDEX.md) | [RPC API](RPC-API-FUNCTIONS.md) | [RPC codecs](RPC-FRAME-FUNCTIONS.md)

Source package: [aether-rpc-transport](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport).
This reference covers **11 explicit declarations** in RpcTransportConfiguration,
RpcIdentity and RpcFlowController, plus the method-free RpcConnectionState enum.
The [server](RPC-SERVER-FUNCTIONS.md) and [client](RPC-CLIENT-FUNCTIONS.md) references
cover the concrete PlaintextDevelopmentRpc implementation separately.

## Configuration Architecture

```text
AetherConfiguration + registry defaults
  -> full AetherConfigValidator validation
  -> exact long-to-int transport conversion
  -> RpcTransportConfiguration bounds
  -> development transport HELLO / decoder / semaphores / admission snapshots
RpcFlowController and RpcConnectionState -> standalone foundations
  (not used by PlaintextDevelopmentRpc in this checkout)
```

This transport is explicitly development-only plaintext. Identity validation and
CRC are not certificate authentication, confidentiality or authorization. Limits
are runtime snapshots: constructing this record does not watch configuration for
updates or apply changes to existing connections.

## Configuration Functions

Source: [RpcTransportConfiguration](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/RpcTransportConfiguration.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RpcTransportConfiguration.RpcTransportConfiguration(frameBytes, messageBytes, streams, inboundBytes, outboundPermits, permitBytes)` | Requires frame 1024..1 MiB, message >=frame and <=64 MiB, positive streams/permits/permitBytes and inbound >=frame. Throws IllegalArgumentException for violations. Does not cap streams at HELLO's 1024 or inbound at HELLO's 256 MiB; does not enforce registry minima for arbitrary direct construction. |
| `RpcTransportConfiguration.defaults()` | Builds AetherConfiguration with development security profile, then resolves through from(). Static registry supplies missing RPC values. Does not open a transport or choose production TLS. |
| `RpcTransportConfiguration.from(configuration)` | Requires nonnull config and runs the full default validator, not just RPC fields. Resolves frame/message/stream/inbound/outbound limits using intValue(). Converts outbound bytes to max(1, floor(bytes/1024)) permits, with permitBytes=1024, then validated record. Invalid unrelated configuration can fail this call too. |
| `RpcTransportConfiguration.admissionPolicies()` | Package-private builder returns fresh outbound/inbound policy pair. Outbound limits VIRTUAL_THREAD_INFLIGHT to outboundPermits; inbound limits RPC_INBOUND_BYTES and RPC_INFLIGHT_STREAMS to record limits, all thresholds equal and inbound wait Duration.ZERO. Does not acquire resources, reserve semaphore permits or impose an RPC_OUTBOUND_BYTES limit itself. |
| `RpcTransportConfiguration.intValue(configuration, name)` | Private helper gets required setting/default from static registry, parses long and converts via Math.toIntExact. Invalid numeric text or overflow raises parsing/arithmetic failure rather than truncation. |

AdmissionPolicyPair is a nested record with no explicit constructor or methods;
generated accessors expose immutable policy references. The outbound resource name
is VIRTUAL_THREAD_INFLIGHT, but the development connection uses these units as
byte-derived semaphore permits. Do not describe this as simply one thread per
permit without inspecting the caller.

Registry defaults resolve to frame **1 MiB**, message **8 MiB**, streams **256**,
inbound **64 MiB**, outbound **64 MiB**, hence **65,536 permits of 1024 bytes**.
Non-multiple outbound byte limits round capacity down. Call charges round body
bytes up to permits, with at least one even for an empty body. Capacity and charge
rounding are therefore distinct.

## Limits Across Layers

The registry permits inbound/outbound settings up to 8 GiB, while from() converts
them to int; values beyond Integer.MAX_VALUE fail exact conversion even if registry
validation accepts them. The registry permits 65,535 streams, while RpcHelloV1
permits at most 1024. Direct record construction can likewise accept inbound
values beyond HELLO's 256 MiB receive-window limit. Configuration acceptance alone
does not prove handshake acceptance.

PlaintextDevelopmentRpc uses configuration.frameBytes() for decoding, advertises
limits in HELLO, constructs a fair outbound semaphore and uses admission policies
with current usage snapshots. Server inbound reservations compare against
configuration.inboundBytes(); client response reservations instead compare aggregate
inbound response bytes against configuration.messageBytes(). These are different
budgets in current code, not one universal receive-window implementation.

Follow concrete use sites in
[PlaintextDevelopmentRpc](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/PlaintextDevelopmentRpc.java):
hello, FrameInput, Connection.permits, reserveInbound, reserveClientInbound and
outboundAdmission/inboundAdmission. This guide documents these relationships;
the complete socket lifecycle is covered in the separate server/client references.

## Identity Functions

Source: [RpcIdentity](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/RpcIdentity.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RpcIdentity.RpcIdentity(clusterId, nodeId, sessionId)` | Requires three nonnull, nonzero UUIDs, otherwise IllegalArgumentException. Despite the source comment saying distinct identities, it does not compare them against each other; equal nonzero values are accepted. Record stores immutable UUID references. |
| `RpcIdentity.start(clusterId, nodeId)` | Generates UUID.randomUUID() for session and invokes validating constructor. Does not persist node/cluster IDs, register membership or start a process/socket. |

Cluster/node identity is caller-owned; session is intended to distinguish process
lifetimes. Random session generation is not peer authentication. Development peer
validation checks role, matching cluster, different local/remote node and optional
expected node, but a plaintext peer can claim those values.

## Byte-Credit Functions

Source: [RpcFlowController](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/RpcFlowController.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RpcFlowController.RpcFlowController(initialCredit)` | Requires initial capacity 1..256 MiB and stores it as long. Does not allocate that many bytes or retain original capacity separately. |
| `RpcFlowController.tryReserve(bytes)` | Synchronized atomic reservation: rejects negative int, returns false without mutation if insufficient credit, otherwise subtracts and returns true. Zero succeeds and leaves credit unchanged. No waiting, timeout or reservation handle. |
| `RpcFlowController.update(delta)` | Synchronized positive increment, rejecting zero/negative or credit beyond global 256 MiB using subtraction guard. Can raise available credit above its original initial value; does not verify matching prior reservation. |
| `RpcFlowController.availableCredit()` | Synchronized snapshot of current unreserved credit. Another thread can reserve immediately after return; it is not a lease. |

Per-instance synchronization protects arithmetic, not correct external accounting.
The controller does not send WINDOW_UPDATE, track per-stream resources, close a
connection, schedule reads or automatically return credit on errors. No main-source
usage of this class occurs in PlaintextDevelopmentRpc; its unit test alone does
not prove network-level byte-credit enforcement.

## Connection State Vocabulary

RpcConnectionState declares NEW, CONNECTING, TLS_HANDSHAKING, HELLO_EXCHANGE,
DUPLICATE_RESOLUTION, READY, DRAINING, CLOSING, CLOSED and FAILED. There are no
explicit functions or transition guards. In particular, TLS_HANDSHAKING's presence
does not mean the development plaintext transport performs TLS, and the enum is
not wired into that implementation's lifecycle. A documented vocabulary is not
an implemented connection state machine.

## Coverage and Verification

Compiler-tree coverage checks **11/11 explicit declarations (100%; 0% remaining)**
in these four files, excluding generated record/enum methods.
[RpcFlowControllerTest](../../modules/aether-rpc-transport/src/test/java/io/aetherdb/rpc/transport/RpcFlowControllerTest.java)
checks insufficient credit, credit return and global overflow. It does not test
all direct-constructor or configuration/HELLO mismatches described above.
The separate server/client references cover concrete transport declarations;
no production RPC service or TLS claim follows from this coverage.
