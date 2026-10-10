# Client Message Codec Functions

[Function index](FUNCTION-INDEX.md) | [Protocol contracts](CLIENT-PROTOCOL-FUNCTIONS.md) | [Remote facade](REMOTE-FACADE-FUNCTIONS.md)

Source: [aether-client-codec](../../modules/aether-client-codec/src/main/java/io/aetherdb/client/codec).
This reference covers **27 explicit declarations across all four codec files**,
including private constructors and validation/error helpers.

## Serialization Architecture

```text
validated client record -> new big-endian byte array -> RPC operation payload
received complete payload -> header validation -> bounded field parsing
  -> client record constructor -> application outcome mapping
```

These codecs operate on complete byte arrays, not a stream or incremental parser.
They do not create the outer RPC frame, authenticate senders, register handlers,
implement command deduplication, or guarantee transport integrity. Returned arrays
are newly allocated. Record byte accessors clone; repeated accessor calls during
encoding create additional copies. Decoding usually copies payload slices and
then copies again through record constructors. No codec offers a borrowed view.

Integers, UUID halves and checksums are big-endian. Status is encoded with
ClientStatus.ordinal(); changing enum order changes the wire meaning. A version
field of 1 is checked, but there is no negotiation or fallback here. Detail/host
text uses UTF-8. new String(bytes, UTF_8) replaces malformed sequences rather
than using a strict decoding error action; accepted text need not preserve the
original bytes on re-encoding.

## Integrity Boundaries

| Message | Header | Integrity implemented by this codec |
| --- | --- | --- |
| Get request/response | 64 bytes | Masked CRC32C over bytes 0..59, stored at 60; payload is not covered |
| Scan request/response | 64 bytes | Same header-only CRC policy |
| Write request | 128 bytes | SHA-256 of the complete mutation region plus masked CRC32C over bytes 0..123, stored at 124 |
| Write response | 128 bytes | Header-only CRC over bytes 0..123 |

The checksum range includes otherwise uninterpreted padding, but a decoder does
not necessarily require that padding to be zero after a recomputed checksum.
Checksums and an unkeyed digest are not authentication. Outer RPC integrity is a
separate layer; these tables describe only the client message codec.

## Point-Read Functions

Source: [ClientGetCodecV1.java](../../modules/aether-client-codec/src/main/java/io/aetherdb/client/codec/ClientGetCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientGetCodecV1.ClientGetCodecV1()` | Private empty constructor; static codec cannot normally be instantiated. |
| `ClientGetCodecV1.encodeRequest(request)` | Allocates 64 + key length bytes. Writes request magic, version, header size, configuration version and key length; leaves reserved/padding bytes zero, appends key, then stamps header CRC. Null request raises NullPointerException. |
| `ClientGetCodecV1.decodeRequest(in)` | Checks envelope CRC before magic/version/header size, four zero reserved longs, key length 1..65,536 and exact total length. Copies the key into ClientGetRequest; its constructor rejects a negative configuration version. Structural errors use invalidRequest; constructor errors retain their own message. |
| `ClientGetCodecV1.encodeResponse(response)` | UTF-8 encodes detail and rejects encoded detail over 4,096 bytes even if the record's character count was legal. Allocates header + value + detail, writes status ordinal and lengths, appends both payloads and stamps CRC. Null response raises NullPointerException. |
| `ClientGetCodecV1.decodeResponse(in)` | Validates envelope, response magic/version/header size, ordinal, four reserved longs, value length 0..16 MiB, detail length 0..4,096 and exact total length. Copies slices, replacement-decodes detail, and constructs ClientGetResponse, which rejects non-OK values. No payload checksum is checked here. |
| `ClientGetCodecV1.validEnvelope(in)` | Returns false for null, fewer than 64 bytes, or CRC mismatch. Reads CRC at 60; does not check magic, total payload size or reserved values. |
| `ClientGetCodecV1.putChecksum(out)` | Mutates the supplied array's four-byte field at 60 using CRC of the first 60 bytes. Assumes a sufficiently large array; used only on encoder-owned output. |
| `ClientGetCodecV1.invalidRequest()` | Creates IllegalArgumentException with invalid CLIENT_GET request body. Does not log or attach a cause. |
| `ClientGetCodecV1.invalidResponse()` | Creates IllegalArgumentException with invalid CLIENT_GET response body. |

### Get Wire Layout

Offsets are zero-based; length is in bytes. The common first eight bytes contain
magic (4), version (2), and header size (2). Request magic is 0x41454751;
response magic is 0x41454752.

| Offset | Request field | Response field |
| --- | --- | --- |
| 8 | configuration version, 8 bytes | status ordinal, 4 bytes |
| 12 | part of configuration version | value length, 4 bytes |
| 16 | key length, 4 bytes | detail length, 4 bytes |
| 20 | four reserved zero longs, 32 bytes | four reserved zero longs, 32 bytes |
| 52 | padding, 8 bytes; not required zero by decode | same |
| 60 | header CRC, 4 bytes | same |
| 64 | key | value followed by detail |

Encoders zero-initialize padding. Exact length checks reject trailing bytes, but
a same-length change to the key, value or detail is not detected by header CRC.

## Scan Functions

Source: [ClientScanCodecV1.java](../../modules/aether-client-codec/src/main/java/io/aetherdb/client/codec/ClientScanCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientScanCodecV1.ClientScanCodecV1()` | Private empty constructor. |
| `ClientScanCodecV1.encodeRequest(request)` | Sums start/end/token lengths, allocates 64 + region bytes and writes configuration, maxEntries and lengths. Appends start, end, token in that order and stamps header CRC. Record bounds keep this request-size arithmetic small. |
| `ClientScanCodecV1.decodeRequest(in)` | Checks envelope, magic/version/header, three reserved longs, positive bounds <=65,536 bytes each, token length 0..4,096 and exact total size. Copies slices; ClientScanRequest then validates configuration/maxEntries. It does not compare the two keys or interpret the token. |
| `ClientScanCodecV1.encodeResponse(response)` | UTF-8 encodes detail, accumulates entry sizes with Math.addExact, then appends each length-prefixed key/value, token and detail. Unlike get/write response encoding, it does not reject encoded detail over 4,096 bytes. A valid record containing multibyte detail can produce bytes its own decoder rejects. No aggregate page-size cap is applied; subsequent region/allocation sums use ordinary int arithmetic. |
| `ClientScanCodecV1.decodeResponse(in)` | Checks envelope, magic/version/header, reserved fields, status, entry count 0..10,000, token/detail lengths 0..4,096. Each entry needs an eight-byte length pair, key length 1..65,536, value length 0..16 MiB and enough remaining bytes. Allocates entry arrays/records, then requires exactly token + detail bytes remain. Constructs ClientScanResponse, which rejects page data for non-OK status. No aggregate page-size cap or key order check. |
| `ClientScanCodecV1.validEnvelope(in)` | Null/minimum-size/header-CRC predicate only; same coverage boundary as get. |
| `ClientScanCodecV1.putChecksum(out)` | Writes masked CRC32C at offset 60 over the first 60 bytes of encoder-owned output. |
| `ClientScanCodecV1.invalidRequest()` | Creates IllegalArgumentException with invalid CLIENT_SCAN request body. |
| `ClientScanCodecV1.invalidResponse()` | Creates IllegalArgumentException with invalid CLIENT_SCAN response body. |

### Scan Wire Layout

Magic is 0x41455351 for requests and 0x41455352 for responses. The common first
eight bytes again contain magic, version and header size.

| Offset | Request field | Response field |
| --- | --- | --- |
| 8 | configuration version, 8 bytes | status ordinal, 4 bytes |
| 12 | part of configuration version | entry count, 4 bytes |
| 16 | maxEntries, 4 bytes | token length, 4 bytes |
| 20 | start length, 4 bytes | detail length, 4 bytes |
| 24 | end length, 4 bytes | three reserved zero longs, 24 bytes |
| 28 | token length, 4 bytes | part of reserved region |
| 32 | three reserved zero longs, 24 bytes | part of reserved region |
| 48 | part of reserved region | reserved zero int, 4 bytes |
| 52 | part of reserved region | padding, 8 bytes; not required zero by decode |
| 56 | padding, 4 bytes; not required zero by decode | part of padding |
| 60 | header CRC, 4 bytes | same |
| 64 | start + end + token | repeated entries + token + detail |

Each response entry is key length (4), value length (4), key, then value. Header
lengths describe the tail token/detail, not a separate aggregate entry-byte count.
SCAN_OPEN and SCAN_NEXT use this same request body; the outer operation code
selects the action. These functions do not encode SCAN_CLOSE or maintain cursors.

## Write Request Functions

Source: [ClientWriteCodecV1.java](../../modules/aether-client-codec/src/main/java/io/aetherdb/client/codec/ClientWriteCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientWriteCodecV1.ClientWriteCodecV1()` | Private empty constructor. HEADER_BYTES is publicly exposed as 128. |
| `ClientWriteCodecV1.encode(request)` | Accumulates 16-byte entry headers plus key/value bytes with Math.addExact and rejects mutation region over 32 MiB. Accumulates key/value totals as longs. Writes identity/dedup flag, ordered entries, SHA-256 of a copied mutation region and header CRC. Integer accumulation overflow raises ArithmeticException before the explicit size rejection; null request raises NullPointerException. |
| `ClientWriteCodecV1.decode(in)` | Rejects null, total size outside 128..32 MiB+128, bad header CRC, magic/version/header/reserved int, region-size mismatch, non-1 algorithm marker, unknown dedup bits, nonzero 20-byte reserved tail or mismatched body SHA. Parses ordered entries and verifies zero entry reserved fields, nonnegative lengths and matching ordinal. Requires no trailing bytes and exact key/value totals, then constructs ClientWriteRequest. Record constructors enforce operation types, per-entry bounds, DELETE emptiness, configuration, count and command-ID policy. |
| `ClientWriteCodecV1.sha(value)` | Acquires SHA-256 for each call and returns digest bytes. An unavailable algorithm becomes AssertionError wrapping NoSuchAlgorithmException. Not a keyed hash or reusable digest pool. |
| `ClientWriteCodecV1.invalid()` | Creates IllegalArgumentException with invalid CLIENT_WRITE body. Record-constructor failures and allocation/buffer errors are not normalized through this helper. |

### Write Request Wire Layout

| Offset | Field |
| --- | --- |
| 0 | magic 0x41454357, 4 bytes |
| 4 | version 1 and header size 128, two shorts |
| 8 | reserved zero int |
| 12 | operation count, 4 bytes |
| 16 | mutation-region bytes, 4 bytes |
| 20 | algorithm marker 1, 4 bytes |
| 24 | configuration version, 8 bytes |
| 32 | command UUID, two 8-byte halves |
| 48 | deduplication flags, 8 bytes; only bit 0 is allowed |
| 56 | total key bytes, 8 bytes |
| 64 | total value bytes, 8 bytes |
| 72 | mutation-region SHA-256, 32 bytes |
| 104 | reserved zero bytes, 20 bytes |
| 124 | header CRC, 4 bytes |
| 128 | ordered mutation region |

Each mutation is type (one byte: PUT=1, DELETE=2), zero byte, zero short, key
length (4), value length (4), zero-based ordinal (4), then key and value.
Empty write keys are accepted by the record contract.

Decode does not bound count to 1..10,000 before iterating or bound individual
lengths before allocation; those bounds are enforced later by records. The
remaining-byte test uses kl + vl with ordinary int arithmetic, so adversarial
large lengths can overflow that sum and reach allocation/buffer failures instead
of the intended invalid-body exception. The 32 MiB input cap is not a complete
allocation-safety proof. This is a documented implementation limit, not a runtime
change or an assertion that malformed-input handling is exhaustive.

## Write Response Functions

Source: [ClientWriteResponseCodecV1.java](../../modules/aether-client-codec/src/main/java/io/aetherdb/client/codec/ClientWriteResponseCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ClientWriteResponseCodecV1.ClientWriteResponseCodecV1()` | Private empty constructor; public HEADER_BYTES is 128. |
| `ClientWriteResponseCodecV1.encode(response)` | Encodes optional leader host and detail as UTF-8, rejecting host over 255 bytes or detail over 4,096 bytes. Writes hint flag, status, command UUID, counts/sequences, endpoint fields and tail lengths. Null expected node UUID becomes two zero halves. Appends host/detail and stamps header-only CRC. |
| `ClientWriteResponseCodecV1.decode(in)` | Checks null/minimum size/CRC, magic/version/header, flags (only leader-hint bit), status ordinal, four reserved longs, host/detail lengths and exact total size. Parses payload text with replacement decoding. Hint flag constructs ClientEndpointHint; zero node UUID becomes null. Without a hint, host length, port and node halves must all be zero. ClientWriteResponse then enforces status/hint and sequence rules. |
| `ClientWriteResponseCodecV1.invalid()` | Creates IllegalArgumentException with invalid CLIENT_WRITE response body. Endpoint/response constructor failures propagate separately. |

### Write Response Wire Layout

| Offset | Field |
| --- | --- |
| 0 | magic 0x41454352, 4 bytes |
| 4 | version 1 and header size 128, two shorts |
| 8 | flags, 4 bytes; leader hint is bit 0 |
| 12 | status ordinal, 4 bytes |
| 16 | command UUID, two 8-byte halves |
| 32 | operation count, 4 bytes |
| 36 | first sequence, 8 bytes |
| 44 | last sequence, 8 bytes |
| 52 | leader port, 4 bytes |
| 56 | expected node UUID, two 8-byte halves |
| 72 | host length, 4 bytes |
| 76 | detail length, 4 bytes |
| 80 | four reserved zero longs, 32 bytes |
| 112 | padding, 12 bytes; not required zero by decode |
| 124 | header CRC, 4 bytes |
| 128 | host followed by detail |

The response constructor requires a hint for NOT_LEADER and forbids it for other
statuses. Codec validation does not verify that the response command UUID matches
the caller's request, authenticate the redirect, or compare sequence range length
with operationCount.

## Evidence And Change Boundaries

Compiler-tree coverage checks every explicit declaration. Existing codec tests
exercise get/scan round trips and header corruption, write order/command identity
and body corruption, and write-response sequences/redirects/header corruption.
Those tests do not exhaustively cover payload corruption without an outer frame,
recomputed checksums, padding, multibyte scan detail, malformed UTF-8 or adversarial
allocation lengths. Read the implementations before changing any wire field.
Changes need coordinated API, codec, facade and server compatibility review;
documentation alone is not protocol certification. Frozen storage/ML experiments
and runtime codec implementations are unchanged by this reference.
