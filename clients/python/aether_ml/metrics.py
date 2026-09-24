from typing import Callable


def export_metrics(stats: dict, emit: Callable[[str, float], None], *, prefix: str = "aether") -> None:
    """Emit numeric cache metrics to TensorBoard, W&B, or another metric sink."""
    names = {
        "lookups": "cache_lookups", "hits": "cache_hits", "misses": "cache_misses",
        "publishes": "cache_publishes", "hitRate": "cache_hit_rate",
        "bytesRead": "bytes_read", "bytesPublished": "bytes_written", "cacheErrors": "cache_errors",
    }
    for name, metric in names.items():
        if name in stats:
            emit(f"{prefix}/{metric}", float(stats[name]))