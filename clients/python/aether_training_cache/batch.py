from dataclasses import dataclass
from collections import defaultdict


@dataclass(frozen=True)
class BatchTiming:
    total_nanos: int
    segment_acquire_nanos: int
    slice_create_nanos: int
    lease_nanos: int
    views: int
    segment_groups: int


@dataclass
class ReferenceBatch:
    keys: list
    references: dict

    @property
    def segment_groups(self):
        groups = defaultdict(list)
        for key in self.keys:
            reference = self.references.get(key)
            if reference is not None:
                groups[(reference.segment_id, reference.generation)].append(key)
        return dict(groups)

    @property
    def present(self):
        return [key for key in self.keys if key in self.references]
