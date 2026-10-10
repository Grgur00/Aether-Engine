from typing import Any, Callable

from .transform_cache import AetherTransformCache


class _SelectedTransform:
    def __init__(self, transform: Callable[[Any], Any], selector: Callable[[Any], Any] | None):
        self.transform = transform
        self.selector = selector

    def __call__(self, source: Any):
        return self.transform(self.selector(source) if self.selector is not None else source)


class AetherDataset:
    """Indexable dataset that caches deterministic work before random augmentation."""

    def __init__(self, dataset: Any, deterministic_transform: Callable[[Any], Any], *, namespace: str,
                 client: Any | Callable[[], Any] | None = None, identity_fn: Callable[[Any, int], str | bytes] | None = None,
                 transform_identity: str | bytes | dict | None = None, random_transform: Callable[[Any], Any] | None = None,
                 codec=None, mode: str = "read-write", on_cache_error: str = "raise",
                 cache_selector: Callable[[Any], Any] | None = None,
                 merge_cached_artifact: Callable[[Any, Any], Any] | None = None):
        if not hasattr(dataset, "__len__") or not hasattr(dataset, "__getitem__"):
            raise TypeError("dataset must provide __len__ and __getitem__")
        if identity_fn is None:
            raise ValueError("identity_fn is required to prevent stale artifact reuse")
        if cache_selector is None and merge_cached_artifact is not None:
            raise ValueError("merge_cached_artifact requires cache_selector")
        self.dataset, self.random_transform = dataset, random_transform
        self.cache_selector = cache_selector
        self.merge_cached_artifact = merge_cached_artifact
        cache_transform = _SelectedTransform(deterministic_transform, cache_selector)
        self.cache = AetherTransformCache(client, cache_transform, namespace=namespace,
            identity_fn=identity_fn, transform_identity=transform_identity, codec=codec, mode=mode,
            on_cache_error=on_cache_error)

    def __len__(self) -> int:
        return len(self.dataset)

    def __getitem__(self, index: int):
        source = self.dataset[index]
        value = self.cache(source, source_identity=self.cache.identity_for(source, index))
        if self.merge_cached_artifact is not None:
            value = self.merge_cached_artifact(source, value)
        return self.random_transform(value) if self.random_transform is not None else value

    def __getitems__(self, indices: list[int]) -> list[Any]:
        """PyTorch 2 batch-fetch protocol; preserves caller order via get_batch."""
        return self.get_batch(indices)

    def get_batch(self, indices: list[int]) -> list[Any]:
        sources = [self.dataset[index] for index in indices]
        values = self.cache.get_many_or_compute([
            (source, self.cache.identity_for(source, index)) for source, index in zip(sources, indices)
        ])
        if self.merge_cached_artifact is not None:
            values = [self.merge_cached_artifact(source, value) for source, value in zip(sources, values)]
        return [self.random_transform(value) if self.random_transform is not None else value for value in values]

    def stats(self):
        return self.cache.stats()

    def reproducibility_metadata(self):
        return self.cache.reproducibility_metadata()

    def populate(self, workers: int = 1):
        if workers < 1:
            raise ValueError("workers must be at least one")
        before = self.stats()
        indices = list(range(len(self)))
        def populate_one(index: int) -> None:
            source = self.dataset[index]
            self.cache(source, source_identity=self.cache.identity_for(source, index))
        if workers == 1:
            self.cache.get_many_or_compute([
                (self.dataset[index], self.cache.identity_for(self.dataset[index], index)) for index in indices
            ])
        else:
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=workers) as executor:
                list(executor.map(populate_one, indices))
        stats = self.stats()
        return {"total": len(self), "reusable": stats["hits"] - before.get("hits", 0),
                "computed": stats["misses"] - before.get("misses", 0)}

    def plan(self, *, estimated_compute_seconds_per_sample: float | None = None):
        return self.cache.plan((self.cache.identity_for(self.dataset[index], index) for index in range(len(self))),
                               estimated_compute_seconds_per_sample=estimated_compute_seconds_per_sample)

    def close(self):
        self.cache.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()