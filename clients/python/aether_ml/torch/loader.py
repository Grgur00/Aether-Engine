from typing import Any


class AetherDataLoader:
    """DataLoader facade with batch-aware retrieval for the single-worker path.

    PyTorch 2 detects AetherDataset.__getitems__ and requests an entire index
    batch at once, which routes retrieval through the cache's get_many path.
    With worker processes, the dataset's PID-aware client lifecycle keeps
    connections process-local.
    """

    def __new__(cls, dataset: Any, *args, **kwargs):
        try:
            from torch.utils.data import DataLoader
        except ImportError as error:
            raise ImportError("AetherDataLoader requires PyTorch") from error
        return DataLoader(dataset, *args, **kwargs)