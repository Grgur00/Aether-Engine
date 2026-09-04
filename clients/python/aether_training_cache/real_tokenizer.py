import hashlib
import unicodedata

from .tokens import encode_tokens


class RealTokenizer:
    """Pinned tiktoken-backed deterministic tokenizer with explicit transform identity."""

    def __init__(self, encoding_name="cl100k_base", max_length=512, normalization="NFKC",
                 special_tokens="none", truncation="tail", packing="none"):
        import tiktoken
        if max_length < 1:
            raise ValueError("max_length must be positive")
        self.encoding_name = encoding_name
        self.max_length = max_length
        self.normalization = normalization
        self.special_tokens = special_tokens
        self.truncation = truncation
        self.packing = packing
        self._encoding = tiktoken.get_encoding(encoding_name)
        self.vocabulary_digest = hashlib.sha256(
            (encoding_name + "|" + str(self._encoding.n_vocab)).encode("utf-8")
        ).hexdigest()

    @property
    def fingerprint_descriptor(self):
        return {
            "tokenizerName": "tiktoken",
            "tokenizerVersion": self._version(),
            "encodingName": self.encoding_name,
            "vocabularyDigest": self.vocabulary_digest,
            "normalizationVersion": self.normalization,
            "specialTokenPolicy": self.special_tokens,
            "maxSequenceLength": self.max_length,
            "truncationPolicy": self.truncation,
            "packingConfiguration": self.packing,
            "outputEncodingVersion": "AETK-1",
        }

    def tokenize(self, text):
        normalized = unicodedata.normalize(self.normalization, text)
        tokens = self._encoding.encode(normalized, disallowed_special=())
        if len(tokens) > self.max_length:
            tokens = tokens[:self.max_length] if self.truncation == "tail" else tokens[-self.max_length:]
        mask = [1] * len(tokens)
        return encode_tokens(tokens, mask)

    def _version(self):
        import tiktoken
        return getattr(tiktoken, "__version__", "unknown")


def document_checksum(document_id, encoded_tokens):
    return hashlib.sha256(document_id.encode("utf-8") + encoded_tokens).hexdigest()
