import struct
from typing import Any, Protocol

from .exceptions import ArtifactDecodeError


class ArtifactCodec(Protocol):
    def encode(self, value: Any) -> bytes: ...

    def decode(self, payload: bytes | memoryview) -> Any: ...


class BytesCodec:
    def encode(self, value: bytes | bytearray | memoryview) -> bytes:
        try:
            return bytes(value)
        except TypeError as error:
            raise TypeError("BytesCodec accepts bytes-like values") from error

    def decode(self, payload: bytes | memoryview) -> bytes:
        return bytes(payload)


class NumPyCodec:
    """Versioned, non-pickle codec for a single contiguous NumPy array."""

    _MAGIC = b"AENP1"

    def encode(self, value: Any) -> bytes:
        try:
            import numpy as np
        except ImportError as error:
            raise ImportError("NumPyCodec requires numpy") from error
        array = np.ascontiguousarray(value)
        header = {"dtype": array.dtype.str, "shape": list(array.shape)}
        encoded = __import__("json").dumps(header, sort_keys=True, separators=(",", ":")).encode("ascii")
        return self._MAGIC + struct.pack(">I", len(encoded)) + encoded + array.tobytes()

    def decode(self, payload: bytes | memoryview) -> Any:
        try:
            import numpy as np
        except ImportError as error:
            raise ImportError("NumPyCodec requires numpy") from error
        payload = memoryview(payload)
        if len(payload) < 9 or bytes(payload[:5]) != self._MAGIC:
            raise ArtifactDecodeError("invalid NumPy artifact header")
        header_size = struct.unpack(">I", payload[5:9])[0]
        if header_size > len(payload) - 9:
            raise ArtifactDecodeError("invalid NumPy artifact header size")
        try:
            header = __import__("json").loads(bytes(payload[9:9 + header_size]))
            array = np.frombuffer(payload[9 + header_size:], dtype=np.dtype(header["dtype"]))
            return array.reshape(header["shape"]).copy()
        except (KeyError, TypeError, ValueError) as error:
            raise ArtifactDecodeError("invalid NumPy artifact") from error


class TensorCodec:
    def __init__(self):
        self._numpy = NumPyCodec()

    def encode(self, value: Any) -> bytes:
        try:
            return self._numpy.encode(value.detach().cpu().numpy())
        except AttributeError as error:
            raise TypeError("TensorCodec accepts PyTorch tensors") from error

    def decode(self, payload: bytes | memoryview) -> Any:
        try:
            import torch
        except ImportError as error:
            raise ImportError("TensorCodec requires PyTorch") from error
        return torch.from_numpy(self._numpy.decode(payload))


class TensorDictCodec:
    """Versioned non-pickle codec for dictionaries of NumPy arrays or tensors."""

    _MAGIC = b"AETD1"

    def encode(self, value: Any) -> bytes:
        if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
            raise TypeError("TensorDictCodec accepts dictionaries with string keys")
        try:
            import numpy as np
        except ImportError as error:
            raise ImportError("TensorDictCodec requires numpy") from error
        arrays, entries, offset = [], [], 0
        for key in sorted(value):
            item = value[key]
            tensor = hasattr(item, "detach")
            if tensor:
                item = item.detach().cpu().numpy()
            try:
                array = np.ascontiguousarray(item)
            except Exception as error:
                raise TypeError(f"TensorDictCodec value {key!r} is not an array") from error
            payload = array.tobytes()
            entries.append({"key": key, "dtype": array.dtype.str, "shape": list(array.shape),
                            "offset": offset, "size": len(payload), "tensor": tensor})
            arrays.append(payload)
            offset += len(payload)
        header = __import__("json").dumps(entries, sort_keys=True, separators=(",", ":")).encode("ascii")
        return self._MAGIC + struct.pack(">I", len(header)) + header + b"".join(arrays)

    def decode(self, payload: bytes | memoryview) -> Any:
        try:
            import numpy as np
        except ImportError as error:
            raise ImportError("TensorDictCodec requires numpy") from error
        payload = memoryview(payload)
        if len(payload) < 9 or bytes(payload[:5]) != self._MAGIC:
            raise ArtifactDecodeError("invalid tensor dictionary artifact header")
        header_size = struct.unpack(">I", payload[5:9])[0]
        if header_size > len(payload) - 9:
            raise ArtifactDecodeError("invalid tensor dictionary artifact header size")
        try:
            entries = __import__("json").loads(bytes(payload[9:9 + header_size]))
            data = payload[9 + header_size:]
            result = {}
            for entry in entries:
                end = entry["offset"] + entry["size"]
                if entry["offset"] < 0 or end > len(data):
                    raise ValueError("payload bounds")
                array = np.frombuffer(data[entry["offset"]:end], dtype=np.dtype(entry["dtype"])).reshape(entry["shape"]).copy()
                if entry["tensor"]:
                    import torch
                    result[entry["key"]] = torch.from_numpy(array)
                else:
                    result[entry["key"]] = array
            return result
        except (KeyError, TypeError, ValueError) as error:
            raise ArtifactDecodeError("invalid tensor dictionary artifact") from error


class AutoCodec:
    """Tagged non-pickle codec for common ML artifact values."""

    _CODECS = ((b"B", BytesCodec), (b"N", NumPyCodec), (b"T", TensorCodec), (b"D", TensorDictCodec))

    def encode(self, value: Any) -> bytes:
        if isinstance(value, (bytes, bytearray, memoryview)):
            tag, codec = self._CODECS[0]
        elif isinstance(value, dict):
            tag, codec = self._CODECS[3]
        elif hasattr(value, "detach"):
            tag, codec = self._CODECS[2]
        else:
            try:
                import numpy as np
            except ImportError:
                np = None
            if np is not None and isinstance(value, np.ndarray):
                tag, codec = self._CODECS[1]
            else:
                raise TypeError("AutoCodec supports bytes, NumPy arrays, PyTorch tensors, and tensor dictionaries")
        return tag + codec().encode(value)

    def decode(self, payload: bytes | memoryview) -> Any:
        payload = memoryview(payload)
        for tag, codec in self._CODECS:
            if payload[:1] == tag:
                return codec().decode(payload[1:])
        raise ArtifactDecodeError("unknown automatic artifact codec tag")