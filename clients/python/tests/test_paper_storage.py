import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
import threading

import numpy as np

from aether_training_cache.persistent_mmap import PersistentMmapStore
from benchmark_gpu_segmentation import (BackendContext, artifact_to_tensor_sample,
    cache_dynamics, effective_measured_steps, load_sources, parse_args,
    preprocess_sample, scheduled_batches, warm_backend)
from benchmark_gpu_segmentation import pack_payload, unpack_payload, prepared_batches


class MmapStorageTests(unittest.TestCase):
    def test_prefetch_thread_stops_when_consumer_closes_early(self):
        context = SimpleNamespace(args=SimpleNamespace(workers=0, prefetch_batches=1), batch=lambda backend, indices: ({"indices": indices}, {}))
        iterator = prepared_batches(context, "CANCELLATION_TEST", (([i], 0) for i in range(100)))
        next(iterator)
        iterator.close()
        self.assertFalse(any(thread.name == "cancellation_test-prefetch" and thread.is_alive() for thread in threading.enumerate()))

    def test_codec_evolution_changes_bytes_but_preserves_tensor_values(self):
        sample = {"sample_id": "codec-test", "image": np.linspace(0, 1, 256).reshape(1, 16, 16),
                  "mask": np.ones((1, 16, 16))}
        plain = pack_payload(sample)
        compressed = pack_payload({**sample, "artifactCodec": "zlib"})
        self.assertNotEqual(plain, compressed)
        decoded = unpack_payload(compressed, np)
        for key in ("image", "mask"):
            np.testing.assert_array_equal(decoded[key], unpack_payload(plain, np)[key])
        self.assertEqual(pack_payload(decoded), compressed)
        with self.assertRaises((ValueError, __import__("zlib").error)):
            unpack_payload(compressed[:-1], np)

    def test_journal_recovers_torn_tail_without_losing_acknowledged_batch(self):
        with tempfile.TemporaryDirectory() as root:
            store = PersistentMmapStore(root, durable=True)
            store.put_many([("a", b"alpha"), ("b", b"beta")])
            journal_size = store.journal_path.stat().st_size
            store.close()
            with store.journal_path.open("ab") as stream:
                stream.write(struct.pack("<I", 100) + b"partial")
            reopened = PersistentMmapStore(root, durable=True)
            try:
                self.assertEqual(set(reopened.index), {"a", "b"})
                self.assertEqual(reopened._journal_offset, journal_size)
                reopened.put("c", b"gamma")
                for key, expected in (("a", b"alpha"), ("b", b"beta"), ("c", b"gamma")):
                    self.assertEqual(reopened.get(key)[4:], expected)
            finally:
                reopened.close()
            final = PersistentMmapStore(root)
            self.assertEqual(set(final.index), {"a", "b", "c"})
            final.close()

    def test_immutable_conflict_does_not_partially_publish(self):
        with tempfile.TemporaryDirectory() as root:
            store = PersistentMmapStore(root)
            try:
                store.put("a", b"alpha")
                with self.assertRaisesRegex(ValueError, "immutable"):
                    store.put_many([("b", b"beta"), ("a", b"changed")])
                self.assertFalse(store.contains("b"))
                self.assertEqual(store.get("a")[4:], b"alpha")
                before = store.journal_path.stat().st_size
                store.put("a", b"alpha")
                self.assertEqual(store.journal_path.stat().st_size, before)
            finally:
                store.close()

    def test_shared_handles_replay_only_appended_batches(self):
        with tempfile.TemporaryDirectory() as root:
            first, second = PersistentMmapStore(root, shared=True), PersistentMmapStore(root, shared=True)
            try:
                first.put("a", b"alpha")
                self.assertEqual(second.get("a")[4:], b"alpha")
                offset = second._journal_offset
                first.put("b", b"beta")
                self.assertTrue(second.contains("b"))
                self.assertGreater(second._journal_offset, offset)
                self.assertEqual(second.get("a")[4:], b"alpha")
            finally:
                first.close()
                second.close()

    def test_complete_corrupt_journal_batch_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            store = PersistentMmapStore(root)
            store.put("a", b"alpha")
            store.close()
            with store.journal_path.open("r+b") as stream:
                stream.seek(5)
                stream.write(b"X")
            with self.assertRaisesRegex(ValueError, "checksum"):
                PersistentMmapStore(root)

    def test_mapping_reopens_after_append_and_detects_corruption(self):
        with tempfile.TemporaryDirectory() as root:
            store = PersistentMmapStore(root)
            store.put("a", b"alpha")
            self.assertEqual(store.get("a"), struct.pack("<I", 5) + b"alpha")
            self.assertIsNotNone(store._mapping)
            store.put("b", b"beta")
            self.assertEqual(hashlib.sha256(store.get("b")[4:]).digest(), hashlib.sha256(b"beta").digest())
            self.assertEqual(store.metrics["mmapReads"], 2)
            store.close()
            reopened = PersistentMmapStore(root)
            self.assertEqual(reopened.get("a")[4:], b"alpha")
            reopened.close()
            with store.data_path.open("r+b") as stream:
                stream.seek(4)
                stream.write(b"X")
            try:
                with self.assertRaisesRegex(ValueError, "checksum"):
                    reopened.get("a")
            finally:
                reopened.close()

    def test_invalid_index_is_not_silently_treated_as_empty(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "index.json").write_text("{invalid", encoding="utf-8")
            with self.assertRaises(json.JSONDecodeError):
                PersistentMmapStore(root)

    def test_paired_reuse_dynamics_and_warmup_do_not_publish(self):
        args = parse_args(["--samples", "12", "--height", "8", "--width", "8",
                           "--resize", "8", "--batch-size", "4", "--epochs", "2",
                           "--initial-cache-hit-ratio", "50"])
        sources = load_sources(args)
        reference = [artifact_to_tensor_sample(preprocess_sample(source, args, np), np) for source in sources]
        context = BackendContext(args, reference, sources, np)
        try:
            warm_backend(context, "STATIC_PREPROCESSED_MMAP")
            self.assertEqual(context.mmap_dynamics()["lookups"], 0)
            for indices, epoch in scheduled_batches(args, effective_measured_steps(args)):
                raw, _ = context.batch("RAW_RECOMPUTE", indices)
                for backend in ("AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"):
                    value, _ = context.batch(backend, indices)
                    for field in ("images", "masks"):
                        np.testing.assert_array_equal(value[field], raw[field])
            mmap = context.mmap_dynamics()
            aether = cache_dynamics(context.protocol_counters(), args)
            for report in (mmap, aether):
                self.assertEqual((report["lookups"], report["hits"], report["misses"]), (24, 18, 6))
                self.assertTrue(report["invariants"]["passed"])
            self.assertGreater(mmap["bytesRead"], 0)
            self.assertEqual(mmap["entriesAppended"], 6)
        finally:
            context.close()


if __name__ == "__main__":
    unittest.main()
