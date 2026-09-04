import struct

MAGIC = b"AETK"
VERSION = 1
HEADER = ">4sHHII"


def encode_tokens(token_ids, attention_mask=()):
    token_ids = list(token_ids)
    attention_mask = list(attention_mask)
    if attention_mask and len(attention_mask) != len(token_ids):
        raise ValueError("attention mask length must match token count")
    header = struct.pack(HEADER, MAGIC, VERSION, 0, len(token_ids), len(attention_mask))
    body = b"".join(struct.pack("<i", token) for token in token_ids)
    body += bytes(attention_mask)
    return header + body


def decode_tokens(encoded):
    header_size = struct.calcsize(HEADER)
    if len(encoded) < header_size:
        raise ValueError("truncated token payload")
    magic, version, flags, token_count, mask_count = struct.unpack(HEADER, encoded[:header_size])
    if magic != MAGIC or version != VERSION or flags != 0:
        raise ValueError("unsupported token payload")
    token_bytes = token_count * 4
    end = header_size + token_bytes + mask_count
    if end != len(encoded):
        raise ValueError("invalid token payload length")
    tokens = [struct.unpack("<i", encoded[offset:offset + 4])[0]
              for offset in range(header_size, header_size + token_bytes, 4)]
    mask = list(encoded[header_size + token_bytes:end])
    return tokens, mask
