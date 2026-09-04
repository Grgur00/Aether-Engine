import mmap
from pathlib import Path


class PlainMmapBaseline:
    """Static packed-segment control backend with an explicit key-to-range index."""

    def __init__(self, segment_path, index):
        self._path = Path(segment_path)
        self._index = dict(index)
        self._file = self._path.open("rb")
        self._mapping = mmap.mmap(self._file.fileno(), 0, access=mmap.ACCESS_READ)

    def get_view(self, key):
        offset, length = self._index[key]
        if offset < 0 or length < 0 or offset + length > len(self._mapping):
            raise ValueError("baseline range is out of bounds")
        return memoryview(self._mapping)[offset:offset + length]

    def close(self):
        try:
            self._mapping.close()
        except BufferError:
            pass
        self._file.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()
