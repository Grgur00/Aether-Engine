def aether_worker_init(worker_id: int) -> None:
    """Optionally open the worker-local client before the first batch."""
    try:
        from torch.utils.data import get_worker_info
    except ImportError as error:
        raise ImportError("aether_worker_init requires PyTorch") from error
    info = get_worker_info()
    if info is not None and hasattr(info.dataset, "cache"):
        info.dataset.cache._client_for_process()