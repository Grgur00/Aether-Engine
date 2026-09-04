import hashlib
import mmap
import time
from collections import OrderedDict


class CacheView:
    def __init__(self, view, registry):
        self._view = view
        self._registry = registry
        self._closed = False

    @property
    def buffer(self):
        if self._closed:
            raise RuntimeError("cache view is closed")
        return self._view

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def close(self):
        if not self._closed:
            self._view.release()
            self._closed = True


class MappedSegmentRegistry:
    def __init__(self, segment_directory: str, maximum_open: int = 32):
        if maximum_open < 1:
            raise ValueError("maximum_open must be positive")
        self._directory = segment_directory
        self._maximum_open = maximum_open
        self._maps = OrderedDict()
        self._retired = []
        self.map_open_count = 0
        self.map_reuse_count = 0
        self.map_close_count = 0

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def view(self, reference):
        map_key = (reference.segment_id, reference.generation)
        mapped = self._maps.pop(map_key, None)
        if mapped is None:
            self.map_open_count += 1
            path = f"{self._directory}/{reference.segment_id}"
            handle = open(path, "rb")
            mapped = (handle, mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ))
        else:
            self.map_reuse_count += 1
        self._maps[map_key] = mapped
        if reference.offset < 0 or reference.length < 0 or reference.offset + reference.length > len(mapped[1]):
            raise ValueError("segment reference is out of bounds")
        view = memoryview(mapped[1])[reference.offset:reference.offset + reference.length]
        if hashlib.sha256(view).digest() != reference.checksum:
            view.release()
            raise ValueError("mapped segment checksum mismatch")
        while len(self._maps) > self._maximum_open:
            self._retired.append(self._maps.popitem(last=False)[1])
        return view

    def cache_view(self, reference):
        return CacheView(self.view(reference), self)

    def close(self):
        for handle, mapped in list(self._maps.values()) + self._retired:
            try:
                mapped.close()
                handle.close()
                self.map_close_count += 1
            except BufferError:
                pass
        self._maps.clear()
        self._retired.clear()
