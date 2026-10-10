import unittest
from unittest.mock import patch

from aether_ml import (AetherDataset, AetherTransformCache, AutoCodec, BytesCodec, CacheMissError, NumPyCodec, TensorCodec, TensorDictCodec,
                       ProtocolCompatibilityError, TransformIdentityError, artifact_key, export_metrics, fingerprint_transform,
                       validate_capabilities)


class MemoryClient:
    def __init__(self):
        self.values = {}
        self.get_batches = 0
        self.put_batches = 0
        self.closed = False

    def get_many(self, keys):
        self.get_batches += 1
        return {key: self.values[key] for key in keys if key in self.values}

    def put_many(self, values):
        self.put_batches += 1
        self.values.update(values)

    def contains_many(self, keys):
        return {key for key in keys if key in self.values}

    def close(self):
        self.closed = True


def tensor_transform(value):
    import torch
    return torch.tensor(value)


def integer_identity(value, index):
    return str(value)


class AetherMLTests(unittest.TestCase):
    def test_artifact_key_changes_for_all_deterministic_identity_components(self):
        baseline = artifact_key("images", "source-a", "resize-v1", "bytes-v1")
        self.assertNotEqual(baseline, artifact_key("other", "source-a", "resize-v1", "bytes-v1"))
        self.assertNotEqual(baseline, artifact_key("images", "source-b", "resize-v1", "bytes-v1"))
        self.assertNotEqual(baseline, artifact_key("images", "source-a", "resize-v2", "bytes-v1"))
        self.assertNotEqual(baseline, artifact_key("images", "source-a", "resize-v1", "bytes-v2"))

    def test_recognized_transform_fingerprint_is_stable_and_unknown_transform_is_rejected(self):
        class Resize:
            def __init__(self, size):
                self.size = size
        Resize.__module__ = "torchvision.transforms.transforms"
        self.assertEqual(fingerprint_transform(Resize((256, 256))), fingerprint_transform(Resize((256, 256))))
        self.assertNotEqual(fingerprint_transform(Resize((256, 256))), fingerprint_transform(Resize((512, 512))))
        with self.assertRaises(TransformIdentityError):
            fingerprint_transform(lambda value: value)

    def test_capability_validation_requires_batched_daemon_features(self):
        class CompatibleClient:
            def engine_info(self):
                return {"protocol": 1, "features": ["get-many", "put-many", "contains-many"]}
        self.assertEqual(validate_capabilities(CompatibleClient())["protocol"], 1)
        class LegacyClient:
            def engine_info(self):
                return {"protocol": 1, "features": ["get-many"]}
        with self.assertRaises(ProtocolCompatibilityError):
            validate_capabilities(LegacyClient())

    def test_transform_cache_batches_misses_then_reuses_hits(self):
        client, calls = MemoryClient(), []
        cache = AetherTransformCache(client, lambda value: calls.append(value) or f"prepared:{value}".encode(),
                                    namespace="images", transform_identity="resize-v1", codec=BytesCodec())
        self.assertEqual(cache.get_many_or_compute([("a", "a"), ("b", "b"), ("a", "a")]),
                         [b"prepared:a", b"prepared:b", b"prepared:a"])
        self.assertEqual(calls, ["a", "b"])
        self.assertEqual((client.get_batches, client.put_batches), (1, 1))
        self.assertEqual(cache.get_many_or_compute([("a", "a"), ("b", "b")]), [b"prepared:a", b"prepared:b"])
        self.assertEqual(calls, ["a", "b"])
        self.assertAlmostEqual(cache.stats()["hitRate"], 2 / 5)

    def test_dataset_applies_random_transform_after_cached_deterministic_value(self):
        client = MemoryClient()
        random_calls = {"count": 0}
        def random_transform(value):
            random_calls["count"] += 1
            return value + str(random_calls["count"]).encode()
        dataset = AetherDataset(["a", "b"], lambda value: f"fixed:{value}".encode(), client=client,
                                namespace="images", identity_fn=lambda value, index: value,
                                transform_identity="fixed-v1", codec=BytesCodec(), random_transform=random_transform)
        self.assertEqual(dataset.get_batch([1, 0]), [b"fixed:b1", b"fixed:a2"])
        self.assertEqual(dataset[0], b"fixed:a3")
        self.assertEqual(dataset.stats()["hits"], 1)

    def test_read_only_cache_fails_on_missing_artifact(self):
        cache = AetherTransformCache(MemoryClient(), lambda value: value, namespace="images",
                                    transform_identity="v1", mode="read-only")
        with self.assertRaises(CacheMissError):
            cache("value", source_identity="value")

    def test_dataset_plan_and_partial_sample_cache_preserve_metadata(self):
        client = MemoryClient()
        dataset = AetherDataset(
            [{"image": b"a", "label": 1}, {"image": b"b", "label": 2}],
            lambda image: image.upper(), client=client, namespace="images",
            identity_fn=lambda sample, index: sample["image"], transform_identity="upper-v1", codec=BytesCodec(),
            cache_selector=lambda sample: sample["image"],
            merge_cached_artifact=lambda sample, image: {**sample, "image": image},
        )
        self.assertEqual(dataset.plan(), {"total": 2, "reusable": 0, "missing": 2, "reuseRatio": 0.0})
        self.assertEqual(dataset[0], {"image": b"A", "label": 1})
        self.assertEqual(dataset.plan()["reusable"], 1)

    def test_dataset_batch_fetch_and_population_use_cache_without_augmentation(self):
        client = MemoryClient()
        dataset = AetherDataset([b"a", b"b"], lambda value: value.upper(), client=client,
                                namespace="images", identity_fn=lambda value, index: value,
                                transform_identity="upper-v1", codec=BytesCodec(),
                                random_transform=lambda value: value + b"-random")
        self.assertEqual(dataset.__getitems__([1, 0]), [b"B-random", b"A-random"])
        report = dataset.populate()
        self.assertEqual(report, {"total": 2, "reusable": 2, "computed": 0})
        self.assertEqual(dataset.cache.get_or_compute(b"a", source_identity=b"a"), b"A")

    def test_plan_estimates_reused_work(self):
        client = MemoryClient()
        cache = AetherTransformCache(client, lambda value: value, namespace="images", transform_identity="v1")
        cache(b"a", source_identity="a")
        plan = cache.plan(["a", "b"], estimated_compute_seconds_per_sample=3)
        self.assertEqual(plan["estimatedWorkAvoidedSeconds"], 3)
        self.assertEqual(plan["estimatedRemainingWorkSeconds"], 3)

    def test_fallback_logs_cache_failure(self):
        class BrokenClient(MemoryClient):
            def get_many(self, keys):
                raise OSError("offline")
        cache = AetherTransformCache(BrokenClient(), lambda value: value.upper(), namespace="images",
                                    transform_identity="v1", codec=BytesCodec(), on_cache_error="fallback")
        with self.assertLogs("aether_ml.transform_cache", "WARNING"):
            self.assertEqual(cache(b"value", source_identity="value"), b"VALUE")

    def test_metrics_and_reproducibility_metadata_are_exportable(self):
        cache = AetherTransformCache(MemoryClient(), lambda value: value, namespace="org/project/v1",
                                    transform_identity="transform-v1")
        cache(b"artifact", source_identity="artifact")
        emitted = {}
        export_metrics(cache.stats(), lambda name, value: emitted.setdefault(name, value))
        self.assertEqual(emitted["aether/cache_hit_rate"], 0.0)
        self.assertEqual(cache.stats()["latency"]["lookup"]["count"], 1)
        self.assertEqual(cache.reproducibility_metadata()["namespace"], "org/project/v1")

    def test_numpy_codec_round_trips_without_pickle(self):
        import numpy as np
        codec = NumPyCodec()
        original = np.arange(6, dtype=np.float32).reshape(2, 3)
        decoded = codec.decode(codec.encode(original))
        np.testing.assert_array_equal(decoded, original)

    def test_tensor_dict_codec_round_trips_array_dictionary(self):
        import numpy as np
        codec = TensorDictCodec()
        original = {"image": np.arange(4, dtype=np.float32).reshape(1, 2, 2), "mask": np.array([1], dtype=np.int64)}
        decoded = codec.decode(codec.encode(original))
        np.testing.assert_array_equal(decoded["image"], original["image"])
        np.testing.assert_array_equal(decoded["mask"], original["mask"])

    def test_auto_codec_round_trips_bytes_and_arrays(self):
        import numpy as np
        codec = AutoCodec()
        self.assertEqual(codec.decode(codec.encode(b"artifact")), b"artifact")
        np.testing.assert_array_equal(codec.decode(codec.encode(np.array([1, 2]))), np.array([1, 2]))

    def test_client_factory_replaces_inherited_connection_on_pid_change(self):
        clients = []
        def create_client():
            client = MemoryClient()
            clients.append(client)
            return client
        cache = AetherTransformCache(create_client, lambda value: value.encode(), namespace="images",
                                    transform_identity="v1", codec=BytesCodec())
        with patch("aether_ml.transform_cache.os.getpid", return_value=100):
            self.assertEqual(cache("first", source_identity="first"), b"first")
        with patch("aether_ml.transform_cache.os.getpid", return_value=101):
            self.assertEqual(cache("second", source_identity="second"), b"second")
        self.assertEqual(len(clients), 2)
        self.assertTrue(clients[0].closed)

    def test_dataset_requires_explicit_source_identity(self):
        with self.assertRaisesRegex(ValueError, "identity_fn"):
            AetherDataset([], lambda value: value, client=MemoryClient(), namespace="images", transform_identity="v1")

    def test_dataset_accepts_single_argument_identity_callback(self):
        dataset = AetherDataset([b"a"], lambda value: value.upper(), client=MemoryClient(), namespace="images",
                                identity_fn=lambda value: value, transform_identity="upper-v1", codec=BytesCodec())
        self.assertEqual(dataset[0], b"A")

    @unittest.skipUnless(__import__("importlib").util.find_spec("torch"), "PyTorch not installed")
    def test_pytorch_dataloader_uses_one_batched_cache_request(self):
        import torch
        from aether_ml.torch import AetherDataLoader
        client = MemoryClient()
        dataset = AetherDataset([1, 2], lambda value: torch.tensor([value]), client=client, namespace="images",
                                identity_fn=lambda value, index: str(value), transform_identity="tensor-v1",
                                codec=TensorCodec())
        batch = next(iter(AetherDataLoader(dataset, batch_size=2, num_workers=0)))
        self.assertEqual(batch.squeeze().tolist(), [1, 2])
        self.assertEqual((client.get_batches, client.put_batches), (1, 1))

    @unittest.skipUnless(__import__("importlib").util.find_spec("torch"), "PyTorch not installed")
    def test_pytorch_worker_matrix_preserves_samples(self):
        from aether_ml.torch import AetherDataLoader
        for workers in (0, 1, 2, 4, 8):
            dataset = AetherDataset(list(range(8)), tensor_transform, client=MemoryClient, namespace="workers",
                                    identity_fn=integer_identity, transform_identity="tensor-v1", codec=TensorCodec())
            values = []
            for batch in AetherDataLoader(dataset, batch_size=2, num_workers=workers, shuffle=False):
                values.extend(batch.reshape(-1).tolist())
            self.assertEqual(values, list(range(8)))


if __name__ == "__main__":
    unittest.main()