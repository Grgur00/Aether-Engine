# Aether ML

`aether_ml` persists deterministic preprocessing results in the Aether daemon.
It keeps random augmentation outside the cache boundary and requires explicit
sample and transform identities to prevent stale artifact reuse.

```python
from aether_ml import AetherDataset, TensorCodec
from aether_ml.torch import AetherDataLoader

dataset = AetherDataset(
    raw_dataset,
    deterministic_transform=preprocess,
    random_transform=augment,
    namespace="retina-segmentation/v4",
    identity_fn=lambda sample, index: sample["revision"],
    transform_identity="retina-preprocess-v4",
    codec=TensorCodec(),
)
loader = AetherDataLoader(dataset, batch_size=16, num_workers=4)
```

The default connection uses `AETHER_HOST`, `AETHER_PORT`, and `AETHER_TIMEOUT`.
Pass a client factory when custom TLS or socket settings are needed. Each worker
process lazily obtains its own client. PyTorch 2 uses `__getitems__`, so each
ordinary DataLoader batch is retrieved by one Aether `get_many` request.

For MONAI dictionaries, use `AetherPersistentDataset` with explicit deterministic
and random `Compose` objects. The default `TensorDictCodec` stores dictionaries
of arrays or tensors without pickle. Use `cache_selector` and
`merge_cached_artifact` on `AetherDataset` when metadata should remain outside
the cached artifact.

```bash
aether-ml plan training_dataset.py
aether-ml populate training_dataset.py --workers 4
aether-ml stats training_dataset.py
```

The configuration file must expose its configured dataset as `dataset`.