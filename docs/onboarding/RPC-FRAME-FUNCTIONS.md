# RPC Frame and Handshake Functions

[Function index](FUNCTION-INDEX.md) | [Architecture](ARCHITECTURE.md) | [Module guide](MODULE-GUIDE.md)

Source package: [aether-rpc-codec](../../modules/aether-rpc-codec/src/main/java/io/aetherdb/rpc/codec).
This reference covers **38 explicit constructors/methods** across all ten
implementation files in the codec package, including framing, HELLO,
fragmentation, assembly, stream allocation and protocol exceptions.
Compiler-generated record accessors/equality and enum values/valueOf are not part
of that explicit inventory. Transport and connection policy are separate
responsibilities, not supplied by these codecs.

## Wire Boundary Architecture

```text
semantic header + defensive payload -> RpcFrameCodecV1.encode -> wire bytes
transport reads -> RpcFrameDecoder.feed (split/coalesced chunks)
  -> header envelope + negotiated length guard -> bounded payload allocation
  -> complete frame CRC + semantic header validation -> RpcFrame
HELLO payload -> fixed envelope/features/identities/limits/fingerprint/CRC
```

This is the general RPC development path, **not** TrainingCacheProtocol used by
Python ML workloads. Codecs provide format checks, not TLS, certificate validation,
authorization, Raft integration or durable writes. CRC detects accidental
corruption, not malicious tampering. HELLO identities and compatibility hashes are
not cryptographic peer authentication.

## Frame Values and Types

| Function | Behavior and boundaries |
| --- | --- |
| `RpcFrame.RpcFrame(header, payload)` | Compact constructor rejects null header/payload or length mismatch with header.payloadLength, then clones the array. Caller mutation cannot change stored payload. Header is an immutable record. |
| `RpcFrame.payload()` | Returns a new payload clone on every call, not a borrowed view. Encoding/assembly callers can therefore incur copies even for immutable frames. |
| `RpcFrameType.RpcFrameType(code, control)` | Private enum construction stores stable wire code and stream-zero control classification. Constants map HELLO=1, REQUEST=2, RESPONSE=3, CANCEL=4, WINDOW_UPDATE=5, PING=6, PONG=7, GOAWAY=8, CONNECTION_ERROR=9. |
| `RpcFrameType.code()` | Returns stored wire type code; no encoding or validation. |
| `RpcFrameType.control()` | Returns whether the type is connection control. REQUEST/RESPONSE/CANCEL are non-control; CANCEL still requires a nonzero stream. |
| `RpcFrameType.fromCode(code)` | Scans enum values for exact code and returns the type; unknown code raises RpcProtocolException. No masking or coercion. |
| `RpcFrameHeaderV1.RpcFrameHeaderV1(type, flags, streamId, code, payloadLength, messageLength, fragmentOffset, timeoutMillis, creditDelta, invocationId)` | Compact constructor requires nonnull type/UUID, known BEGIN/END flags, nonnegative stream/length/offset/timeout/credit, payload <=1 MiB and message <=64 MiB. Enforces stream-zero control versus application streams, nonzero CANCEL stream, BEGIN-only declared message length, request-only timeout and WINDOW_UPDATE-only positive credit. Certain controls require ZERO_INVOCATION. |
| `RpcFrameHeaderV1.beginsMessage()` | Tests BEGIN bit (1) without changing state. |
| `RpcFrameHeaderV1.endsMessage()` | Tests END bit (2) without changing state. |

Header validation does not assert fragmentOffset+payloadLength <= messageLength,
contiguous fragments, BEGIN at offset zero, END at the declared final byte,
stream parity or unique stream IDs. These need assembler/connection validation.
code can be any signed int. Timeout is allowed on any REQUEST frame here, not
explicitly restricted to BEGIN despite the field description. Non-BEGIN
messageLength must be zero, not a repeated total.

HELLO, GOAWAY and CONNECTION_ERROR are exempt from the control UUID-zero check.
PING/PONG/WINDOW_UPDATE require zero UUID; application/CANCEL UUIDs are not required
nonzero by this constructor. Null requirements throw NullPointerException through
Objects.requireNonNull; invalid semantic numeric combinations throw
IllegalArgumentException.

## Complete Frame Codec

| Function | Behavior and boundaries |
| --- | --- |
| `RpcFrameCodecV1.RpcFrameCodecV1()` | Private empty constructor prevents utility instantiation. |
| `RpcFrameCodecV1.encode(frame)` | Gets defensive payload copy, allocates header+payload, writes AERP/version 1.0/type/flags and semantic fields big-endian. Writes zero reserved word and masked CRC32C over first 60 header bytes concatenated with payload, excluding stored CRC. Returns complete wire bytes; no I/O or authentication. |
| `RpcFrameCodecV1.decode(encoded)` | Requires >=64 bytes, correct magic/version, known type and zero reserved field. Requires legal payload length and exact total size, copies payload and checksum input, verifies CRC, then constructs validated header/frame. Semantic IllegalArgumentException becomes RpcProtocolException. Rejects extra trailing bytes: decode handles exactly one complete frame. |

Wire offsets: magic 0..3, major/minor 4..5, type/flags 6..7, stream 8..15,
code 16..19, payload length 20..23, message length 24..27, offset 28..31,
timeout 32..35, credit 36..39, UUID 40..55, reserved 56..59, CRC 60..63,
payload from 64. The checksum covers type/length/UUID/reserved and payload, but
not its own four-byte slot. encode derives payload length from the copied array.

decode validates some envelope/length fields before CRC; corruption can therefore
raise a structural error rather than specifically checksum mismatch. RpcFrame's
constructor copies the already copied payload again. This codec prioritizes bounded
format correctness, not zero-copy transport or exact allocation minimization.

## Incremental Decoder

| Function | Behavior and boundaries |
| --- | --- |
| `RpcFrameDecoder.RpcFrameDecoder(maximumFramePayload)` | Requires negotiated payload bound 1..global 1 MiB, retains it and a fixed 64-byte header buffer. No negotiation or peer identity validation occurs here. |
| `RpcFrameDecoder.feed(input)` | Rejects null input, consumes its available bytes into partial header/payload state and returns immutable list of complete verified frames in order. Handles arbitrary splits and coalescing, including zero-payload completion directly after header. Mutates ByteBuffer position. Does not limit total frames returned from a large input or expose frames preceding a later error in that call. |
| `RpcFrameDecoder.hasPartialFrame()` | Returns headerBytes!=0; complete header plus unfinished payload counts as partial. Does not distinguish valid partial state from a rejected header awaiting reset. |
| `RpcFrameDecoder.retainedBytes()` | Returns filled header+payload byte counts, not actual allocated memory. A full declared payload array is allocated after header even when none of it has arrived, so this number can understate resident capacity. |
| `RpcFrameDecoder.reset()` | Clears counters and drops payload reference; retains/reuses fixed header array without zeroing it. Intended for terminal connection cleanup; does not emit a partial frame. |
| `RpcFrameDecoder.validateHeaderAndPayloadLength()` | Checks AERP/version and reads big-endian payload length at offset 20. Rejects negative or negotiated-oversize lengths before payload allocation. Other type/flags/reserved/CRC/semantic checks wait until complete decode. |
| `RpcFrameDecoder.complete()` | Allocates contiguous encoded bytes, copies header/payload and delegates full decode. finally clears partial state even if CRC/semantic decode fails. Allocation/copy before try is not covered by that finally. |

A header-validation failure occurs before complete(), leaving headerBytes=64 and
no payload allocated. Callers should close/reset the failed connection rather than
continue feeding that state. By contrast full-frame decode failure clears counters.
The decoder is mutable and not synchronized; give it one ordered transport reader.
Bounds are per frame, not a connection-wide memory reservation or reassembled
message quota. No EOF/truncated-frame exception is generated automatically;
connection code must inspect partial state on terminal read.

## HELLO Handshake Functions

| Function | Behavior and boundaries |
| --- | --- |
| `RpcHelloV1.Role.Role(code)` | Private nested enum constructor stores DIALER=1 or ACCEPTOR=2. |
| `RpcHelloV1.Role.code()` | Package-private getter used by HELLO encoding. |
| `RpcHelloV1.RpcHelloV1(role, clusterId, nodeId, sessionId, connectionNonce, maximumFramePayload, maximumMessageBytes, maximumConcurrentStreams, initialReceiveWindowBytes, keepaliveIdleMillis, keepaliveTimeoutMillis, engineMajor, engineMinor, enginePatch, javaMajor)` | Requires role/nonzero UUIDs and nonzero nonce; frame 1..1 MiB, message 0..64 MiB, streams 1..1024, receive window 1..256 MiB, positive keepalive durations, nonnegative engine versions and Java >=21. Does not compare peer/local cluster, roles, versions or negotiate minima. |
| `RpcHelloV1.encode()` | Allocates zero-filled 192 bytes, writes AEHL/version/length/fixed capability marker/role and zero unsupported features, UUIDs/nonce/limits/runtime versions, compatibility fingerprint, then masked CRC32C over first 188 bytes. Reserved tail remains zero. |
| `RpcHelloV1.decode(encoded)` | Requires exactly 192 bytes and expected envelope/capability marker, valid role and zero feature/reserved fields. Reads identities/limits, verifies reserved tail and CRC, constructs validated HELLO, then compares compatibility fingerprint using MessageDigest.isEqual. Semantic IllegalArgumentException becomes RpcProtocolException. |
| `RpcHelloV1.compatibilityFingerprint()` | Computes a newly allocated SHA-256 of the frozen ASCII RPC v1 compatibility descriptor, including frame size/types/flags/status/HELLO/checksum identifiers. Missing SHA-256 is treated as AssertionError. This is a format fingerprint, not a source commit or security identity. |
| `RpcHelloV1.isZero(value)` | Returns true for null UUID or both UUID halves zero. Used to reject missing/zero identities. |
| `RpcHelloV1.putUuid(bytes, value)` | Writes most/least significant UUID longs using supplied buffer order; no independent null/space check. |
| `RpcHelloV1.getUuid(bytes)` | Reads two longs from current position and constructs UUID; semantic zero check occurs in constructor. |

HELLO offsets: envelope 0..7, fixed marker/role 8..9, zero flags/features 10..31,
cluster/node/session UUIDs 32..79, nonce 80..87, limits/keepalive/runtime fields
88..127, fingerprint 128..159, reserved tail 160..187, CRC 188..191.
Negative nonce is allowed as long as nonzero. Zero maximumMessageBytes is allowed;
the constructor does not require a window large enough for a configured message.
Engine version fields are recorded but not compared for exact peer compatibility;
the frozen fingerprint is the codec-level compatibility gate. Transport/connection
policy must authenticate identity, match roles/cluster and negotiate capabilities.

## Message Fragmentation Functions

Source: [RpcMessageFragmenter](../../modules/aether-rpc-codec/src/main/java/io/aetherdb/rpc/codec/RpcMessageFragmenter.java).

```text
one complete REQUEST/RESPONSE byte array
  -> ordered fragments with stable type/stream/code/invocation
  -> BEGIN declares total length and request timeout
  -> continuation frames declare zero message length/timeout
  -> END marks final bytes (empty message has BEGIN + END)
```

| Function | Behavior and boundaries |
| --- | --- |
| `RpcMessageFragmenter.RpcMessageFragmenter()` | Private empty constructor prevents normal instantiation of this static utility. |
| `RpcMessageFragmenter.fragment(type, streamId, code, invocationId, timeoutMillis, message, maximumPayload)` | Accepts REQUEST or RESPONSE only, nonnull message up to the global 64 MiB limit and payload limit 1..1 MiB. Empty input yields one zero-payload BEGIN+END frame. Otherwise copies consecutive ranges, creates frames and returns an immutable ordered list. Header construction rejects invalid stream/UUID or negative BEGIN request timeout. Response timeout is ignored, including negative values. Does not apply a negotiated message-size limit or send bytes. |
| `RpcMessageFragmenter.frame(type, stream, code, invocation, timeout, payload, total, offset, begin, end)` | Private helper forms flags, declares total only on BEGIN, sets timeout only for a BEGIN REQUEST and zero credit, then constructs validated header and defensively copied RpcFrame. Does not validate connection parity or reserve a stream. |

Nonempty messages incur a range copy and the RpcFrame constructor's payload copy
for every fragment. The utility materializes the entire list before returning;
it is not a streaming sender or a receive-window scheduler. The number of frames
can be large when maximumPayload is small. Concurrent mutation of the input during
fragmentation is not a supported snapshot guarantee, although returned frame
payloads cannot be changed by later caller mutation. Allocation failure can stop
construction; nothing is published to a transport by this utility.

## Message Assembly Functions

Source: [RpcMessageAssembler](../../modules/aether-rpc-codec/src/main/java/io/aetherdb/rpc/codec/RpcMessageAssembler.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RpcMessageAssembler.RpcMessageAssembler(maximumMessageBytes)` | Stores negotiated message bound 0..64 MiB; invalid bounds raise IllegalArgumentException. Starts idle with no output allocation. Zero permits empty messages only. |
| `RpcMessageAssembler.accept(frame)` | BEGIN requires idle state and declared size within negotiated bound before creating ByteArrayOutputStream with initial capacity min(total, 64 KiB). Saves type/stream/code/invocation. Continuations require active state and unchanged identity. Every offset must equal bytes already assembled and payload must fit declared length, using long addition for the bound. Appends a cloned payload. Returns null until END; END requires exact total, copies completed bytes, resets state and returns them. Protocol violations raise RpcProtocolException; null frame instead produces NullPointerException. |
| `RpcMessageAssembler.reset()` | Drops output reference and clears saved identity, declared length, stream and code. Does not overwrite retained byte storage or notify transport/flow-control accounting. Safe to call while idle. |
| `RpcMessageAssembler.isAssembling()` | Tests output != null; reports state rather than completeness of bytes. Remains true if all bytes arrived without END or if an error left an active assembly. |

One instance assembles **one message at a time**, not interleaved streams. Connection
code must route frames to the appropriate assembler and bound aggregate memory
across streams. This class has no synchronization, deadline, cancellation,
flow-control credit, peer-role checks or EOF handling. It does not restrict frame
type to REQUEST/RESPONSE; frame headers and connection policy impose other rules.

Failures do **not** automatically reset assembly. A bad initial offset can leave
the new output active; early END leaves appended bytes in that state. An identity
mismatch leaves the prior assembly intact. Callers must choose reset/discard or
connection failure rather than assume transactional rollback. Zero-length
continuations and a zero-payload END after all bytes are already present can be
accepted when identity/offset rules hold. BEGIN+END with a zero declared length
returns an empty byte array and resets successfully.

The initial 64 KiB cap is not a cap on final buffering: output grows with the
bounded message. Each append obtains RpcFrame.payload()'s defensive clone and
copies into the output; completion copies once more via toByteArray(). These are
ownership guarantees, not zero-copy assembly.

## Stream Identity and Failure Functions

Sources: [RpcStreamIdAllocator](../../modules/aether-rpc-codec/src/main/java/io/aetherdb/rpc/codec/RpcStreamIdAllocator.java),
[RpcProtocolException](../../modules/aether-rpc-codec/src/main/java/io/aetherdb/rpc/codec/RpcProtocolException.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RpcStreamIdAllocator.RpcStreamIdAllocator(role)` | Starts at 1 for DIALER and 2 for every other value, including null. Role enum has DIALER/ACCEPTOR and no explicit methods. Allocator state is local to this instance; it does not discover or negotiate peer role. |
| `RpcStreamIdAllocator.nextId()` | Synchronized method returns next positive ID and increments by two. Rejects next <=0 or next > Long.MAX_VALUE-2 with IllegalStateException before overflow. IDs are never reused within this instance; exhaustion does not advance state. No active-stream quota, release operation or connection lifecycle is implemented. |
| `RpcProtocolException.RpcProtocolException(message)` | Delegates message to unchecked RuntimeException; no cause-taking constructor, status code, wire response or transport close. serialVersionUID is fixed at 1. Null message is accepted by the superclass. |

The conservative exhaustion guard leaves Long.MAX_VALUE unused for dialers and
Long.MAX_VALUE-1 unused for acceptors. Synchronization protects one allocator, not
uniqueness across separate allocators. A connection should own one role-correct
allocator and independently enforce incoming parity and stream reuse rules.
Protocol exceptions signal invalid data; their class alone does not prove a
GOAWAY/CONNECTION_ERROR frame was emitted or the socket was closed.

## Coverage and Verification

Compiler-tree inventory checks all 38 explicit declarations in the ten implementation
files and rejects additions not included in the inventory.
[RpcFrameCodecV1Test](../../modules/aether-rpc-codec/src/test/java/io/aetherdb/rpc/codec/RpcFrameCodecV1Test.java)
covers golden frame/HELLO bytes, CRC corruption, byte-at-a-time/coalesced decoding,
negotiated length rejection, fragmentation/assembly and stream parity. These tests
are focused codec evidence, not a transport concurrency or authentication suite.

This codec package has **38/38 declarations covered (100%; 0% remaining)**.
It is not full RPC API/transport or end-to-end distributed-service coverage.
