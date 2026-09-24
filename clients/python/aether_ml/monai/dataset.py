from typing import Any, Callable, Iterable

from ..codecs import TensorDictCodec
from ..dataset import AetherDataset


class AetherPersistentDataset(AetherDataset):
    """MONAI-compatible dictionary dataset with an explicit cache boundary.

    MONAI is intentionally not imported: its Compose objects are ordinary callables,
    and this wrapper can therefore be constructed in minimal environments.
    """

    def __init__(self, data: Iterable[Any], deterministic_transform: Callable[[Any], Any], *, namespace: str,
                 client: Any | Callable[[], Any] | None = None, identity_fn: Callable[[Any, int], str | bytes] | None = None,
                 transform_identity: str | bytes | dict | None = None, random_transform: Callable[[Any], Any] | None = None,
                 codec=None, mode: str = "read-write", on_cache_error: str = "raise"):
        super().__init__(list(data), deterministic_transform, namespace=namespace, client=client,
                         identity_fn=identity_fn, transform_identity=transform_identity,
                         random_transform=random_transform, codec=codec or TensorDictCodec(), mode=mode,
                         on_cache_error=on_cache_error)