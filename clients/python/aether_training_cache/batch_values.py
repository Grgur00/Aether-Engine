from dataclasses import dataclass


@dataclass
class InlineValueBatch:
    buffer: memoryview
    offsets: list[int]
    lengths: list[int]
    statuses: list[str]

    def value(self, index: int) -> memoryview | None:
        if self.statuses[index] != "HIT_INLINE":
            return None
        start = self.offsets[index]
        return self.buffer[start:start + self.lengths[index]]

    def close(self):
        self.buffer.release()
