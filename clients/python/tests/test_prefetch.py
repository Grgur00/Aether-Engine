"""Prefetch correctness/lifecycle tests; no server or GPU is required."""

import hashlib
import random
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from aether_training_cache.prefetch import (
    BoundedPrefetchIterator, PreparedBatch, merge_prefetch_metrics,
)
from benchmark_gpu_segmentation import prepared_batches


class PrefetchIteratorTests(unittest.TestCase):
    def test_order_sample_boundaries_and_exactly_one_producer(self):
        indices = list(range(23))
        random.Random(713).shuffle(indices)
        schedule = [tuple(indices[start:start + 4]) for start in range(0, len(indices), 4)]
        caller = threading.get_ident()
        for depth in (0, 1, 2, 4, 8):
            with self.subTest(depth=depth):
                preparation_threads = set()
                scheduling_threads = []

                def tasks():
                    for task in schedule:
                        scheduling_threads.append(threading.get_ident())
                        yield task

                def prepare(task):
                    preparation_threads.add(threading.get_ident())
                    return PreparedBatch(tuple(f"sample-{index}" for index in task), 11, 7)

                with BoundedPrefetchIterator(tasks(), prepare, depth) as iterator:
                    values = list(iterator)
                self.assertEqual([item.batchIndex for item in values], list(range(len(schedule))))
                self.assertEqual([item.task for item in values], schedule)
                self.assertEqual([value for item in values for value in item.value],
                                 [f"sample-{index}" for index in indices])
                self.assertEqual(scheduling_threads, [caller] * len(schedule))
                self.assertEqual(len(preparation_threads), 1)
                self.assertEqual(caller in preparation_threads, depth == 0)
                self.assertFalse(iterator.worker_alive)
                metrics = iterator.metrics
                self.assertEqual(metrics["batchesRequested"], len(schedule))
                self.assertEqual(metrics["batchesConsumed"], len(schedule))
                self.assertEqual(metrics["prefetchLookupNs"], 11 * len(schedule))
                self.assertEqual(metrics["prefetchDecodeNs"], 7 * len(schedule))
                self.assertEqual(metrics["consumerWaitNs"], sum(item.consumerWaitNs for item in values))
                self.assertLessEqual(metrics["maxQueueDepth"], depth)
                self.assertEqual(metrics["queueDepthSamples"], 2 * len(schedule) if depth else 0)
                if depth:
                    self.assertEqual(metrics["meanQueueDepth"],
                                     metrics["queueDepthTotal"] / metrics["queueDepthSamples"])
                else:
                    for name in ("queueEmptyWaitNs", "queueFullWaitNs", "maxQueueDepth", "meanQueueDepth"):
                        self.assertEqual(metrics[name], 0)

    def test_raw_payload_and_optional_metrics_callback(self):
        payload = {"data": b"artifact", "lookup": 23, "decode": 5}
        with BoundedPrefetchIterator(
            [[9]], lambda task: payload, 1,
            metrics_callback=lambda result: {"lookup_ns": result["lookup"], "decode_ns": result["decode"]},
        ) as iterator:
            item = next(iterator)
        self.assertIs(item.value, payload)
        self.assertEqual(item.task, [9])
        self.assertGreaterEqual(item.consumerWaitNs, 0)
        self.assertEqual(iterator.metrics["prefetchLookupNs"], 23)
        self.assertEqual(iterator.metrics["prefetchDecodeNs"], 5)
        with BoundedPrefetchIterator([0], lambda task: None, 0) as iterator:
            self.assertIsNone(next(iterator).value)

    def test_depth_zero_is_lazy_and_uses_no_thread(self):
        calls = []
        iterator = BoundedPrefetchIterator([3, 4], lambda task: calls.append(task) or task, 0)
        try:
            self.assertEqual(calls, [])
            self.assertFalse(iterator.worker_alive)
            self.assertEqual(next(iterator).value, 3)
            self.assertEqual(calls, [3])
        finally:
            iterator.close()
        self.assertEqual(calls, [3])

    def test_worker_failure_reaches_consumer_in_order(self):
        for depth in (0, 1, 4):
            with self.subTest(depth=depth):
                failure = LookupError("missing cache artifact")
                calls = []

                def prepare(task):
                    calls.append(task)
                    if task == 1:
                        raise failure
                    return task

                with BoundedPrefetchIterator([0, 1, 2], prepare, depth) as iterator:
                    self.assertEqual(next(iterator).value, 0)
                    with self.assertRaises(LookupError) as caught:
                        next(iterator)
                    self.assertIs(caught.exception, failure)
                    with self.assertRaises(StopIteration):
                        next(iterator)
                self.assertEqual(calls, [0, 1])
                self.assertFalse(iterator.worker_alive)
                self.assertEqual(iterator.metrics["batchesRequested"], 2)
                self.assertEqual(iterator.metrics["batchesConsumed"], 1)

    def test_metrics_callback_failure_reaches_consumer(self):
        def invalid_metrics(value):
            raise ValueError("invalid stage timings")

        with BoundedPrefetchIterator([0], lambda task: b"value", 1,
                                     metrics_callback=invalid_metrics) as iterator:
            with self.assertRaisesRegex(ValueError, "invalid stage timings"):
                next(iterator)
        self.assertFalse(iterator.worker_alive)

    def test_callback_stop_iteration_cannot_silently_truncate_schedule(self):
        def prepare(task):
            raise StopIteration("unexpected callback exhaustion")

        for depth in (0, 1):
            with BoundedPrefetchIterator([0, 1], prepare, depth) as iterator:
                with self.assertRaisesRegex(RuntimeError, "before schedule exhaustion"):
                    list(iterator)
            self.assertFalse(iterator.worker_alive)

    def test_close_cancels_full_queue_and_bounds_lookahead(self):
        for depth in (1, 2, 4, 8):
            with self.subTest(depth=depth):
                lookahead_started = threading.Event()
                calls = []

                def prepare(task):
                    calls.append(task)
                    if task == depth:
                        lookahead_started.set()
                    return task

                iterator = BoundedPrefetchIterator(range(100), prepare, depth)
                try:
                    self.assertTrue(lookahead_started.wait(2), "producer did not fill its queue")
                finally:
                    iterator.close()
                iterator.close()
                self.assertFalse(iterator.worker_alive)
                self.assertEqual(calls, list(range(depth + 1)))
                self.assertEqual(iterator.metrics["maxQueueDepth"], depth)
                self.assertEqual(iterator.metrics["batchesConsumed"], 0)
                with self.assertRaises(StopIteration):
                    next(iterator)

    def test_close_during_callback_joins_before_return(self):
        entered = threading.Event()
        release = threading.Event()
        close_started = threading.Event()
        closed = threading.Event()
        errors = []
        calls = []

        def prepare(task):
            calls.append(task)
            entered.set()
            if not release.wait(2):
                raise TimeoutError("test callback was not released")
            return task

        iterator = BoundedPrefetchIterator(range(10), prepare, 1)

        def close():
            close_started.set()
            try:
                iterator.close()
            except BaseException as error:
                errors.append(error)
            finally:
                closed.set()

        closer = threading.Thread(target=close)
        try:
            self.assertTrue(entered.wait(2))
            closer.start()
            self.assertTrue(close_started.wait(2))
            with iterator._condition:
                self.assertTrue(iterator._condition.wait_for(lambda: iterator._closed, timeout=2))
            self.assertFalse(closed.is_set())
        finally:
            release.set()
            if closer.ident is not None:
                closer.join(2)
            iterator.close()
        self.assertTrue(closed.is_set())
        self.assertEqual(errors, [])
        self.assertFalse(iterator.worker_alive)
        self.assertEqual(calls, [0])

    def test_early_close_cancels_pending_work_once_outside_condition(self):
        entered, release, condition_acquired = (threading.Event() for _ in range(3))
        cancellations = []
        caller = threading.get_ident()

        def prepare(task):
            if task == 1:
                entered.set()
                if not release.wait(2):
                    raise TimeoutError("pending I/O was not cancelled")
                with iterator._condition:
                    condition_acquired.set()
            return task

        def cancel():
            cancellations.append(threading.get_ident())
            release.set()
            self.assertTrue(condition_acquired.wait(2), "cancel callback held the queue condition")

        iterator = BoundedPrefetchIterator([0, 1, 2], prepare, 1, cancel_callback=cancel)
        try:
            self.assertEqual(next(iterator).value, 0)
            self.assertTrue(entered.wait(2))
            iterator.close()
            self.assertFalse(iterator.worker_alive)
        finally:
            release.set()
            iterator.close()
        self.assertEqual(cancellations, [caller])
        self.assertEqual(iterator.metrics["batchesConsumed"], 1)

    def test_complete_consumption_never_cancels_even_with_live_worker(self):
        for count in (0, 1, 3):
            with self.subTest(count=count):
                tail_entered, release = threading.Event(), threading.Event()
                cancellations = []

                class TailWorkerIterator(BoundedPrefetchIterator):
                    def _produce(self):
                        super()._produce()
                        tail_entered.set()
                        release.wait(2)

                iterator = TailWorkerIterator(range(count), lambda task: task, 4,
                                              cancel_callback=lambda: cancellations.append(True))

                def release_after_close_starts():
                    with iterator._condition:
                        iterator._condition.wait_for(lambda: iterator._closed, timeout=2)
                    release.set()

                releaser = threading.Thread(target=release_after_close_starts)
                try:
                    self.assertTrue(tail_entered.wait(2))
                    self.assertTrue(iterator.worker_alive)
                    releaser.start()
                    self.assertEqual([next(iterator).value for _ in range(count)], list(range(count)))
                    # No terminal next() is required: consuming the final batch
                    # is enough to preserve the persistent client on close.
                    iterator.close()
                    self.assertFalse(iterator.worker_alive)
                finally:
                    release.set()
                    if releaser.ident is not None:
                        releaser.join(2)
                    iterator.close()
                self.assertEqual(cancellations, [])

    def test_cancel_callback_skipped_without_live_positive_depth_worker(self):
        for depth in (0, 1):
            with self.subTest(depth=depth):
                cancellations = []
                iterator = BoundedPrefetchIterator([0], lambda task: task, depth,
                    cancel_callback=lambda: cancellations.append(True))
                try:
                    if depth:
                        iterator._thread.join(2)
                        self.assertFalse(iterator.worker_alive)
                    # Leave the schedule unconsumed: no live producer means
                    # there is no pending request to abort.
                finally:
                    iterator.close()
                self.assertEqual(cancellations, [])

    def test_cancellation_error_still_joins_worker(self):
        entered, release = threading.Event(), threading.Event()

        def prepare(task):
            entered.set()
            release.wait(2)
            return task

        def cancel():
            release.set()
            raise OSError("cancellation failure")

        iterator = BoundedPrefetchIterator([0, 1], prepare, 1, cancel_callback=cancel)
        try:
            self.assertTrue(entered.wait(2))
            with self.assertRaisesRegex(OSError, "cancellation failure"):
                iterator.close()
            self.assertFalse(iterator.worker_alive)
        finally:
            release.set()
            iterator.close()

    def test_join_timeout_is_explicit_and_close_can_be_retried(self):
        entered, release = threading.Event(), threading.Event()

        def prepare(task):
            entered.set()
            release.wait(2)
            return task

        iterator = BoundedPrefetchIterator([0], prepare, 1, join_timeout=0.01)
        try:
            self.assertTrue(entered.wait(2))
            with self.assertRaisesRegex(RuntimeError, "did not stop"):
                iterator.close()
            self.assertTrue(iterator.worker_alive)
        finally:
            release.set()
            # Allow a bounded callback to finish before retrying the short join.
            iterator._thread.join(2)
            iterator.close()
        self.assertFalse(iterator.worker_alive)

    def test_empty_schedule_and_invalid_configuration(self):
        for depth in (0, 1, 8):
            with BoundedPrefetchIterator([], lambda task: self.fail("empty schedule invoked callback"), depth) as iterator:
                self.assertEqual(list(iterator), [])
            self.assertEqual(iterator.metrics["batchesRequested"], 0)
            self.assertEqual(iterator.metrics["meanQueueDepth"], 0)
        for depth in (-1, 1.5, True):
            with self.assertRaises(ValueError):
                BoundedPrefetchIterator([], lambda task: task, depth)

    def test_occupancy_aggregation_is_sample_weighted(self):
        metrics = merge_prefetch_metrics([
            {"queueDepthTotal": 8, "queueDepthSamples": 4, "maxQueueDepth": 4},
            {"queueDepthTotal": 1, "queueDepthSamples": 2, "maxQueueDepth": 1},
        ], 4)
        self.assertEqual(metrics["meanQueueDepth"], 1.5)
        self.assertEqual(metrics["maxQueueDepth"], 4)
        self.assertIn("enqueue", metrics["meanQueueDepthDefinition"])


class BenchmarkPrefetchTests(unittest.TestCase):
    def test_sync_wrapper_still_defers_preparation_to_train_step(self):
        context = SimpleNamespace(args=SimpleNamespace(workers=0, prefetch_batches=0),
                                  batch=lambda *args: self.fail("wrapper prepared a synchronous batch"))
        metrics = {}
        self.assertEqual(list(prepared_batches(context, "TEST", [([2, 0], 0), ([1], 0)], metrics=metrics)),
                         [([2, 0], 0, None, 0.0), ([1], 0, None, 0.0)])
        self.assertEqual(metrics["batchesConsumed"], 2)

    def test_epoch_transition_joins_old_worker_and_resets_batch_index(self):
        old_epoch_consumed = threading.Event()
        caller = threading.get_ident()
        iterators = []
        schedule_threads = []

        def schedule():
            for task in (([0], 0), ([1], 0), ([10], 1), ([11], 1)):
                schedule_threads.append(threading.get_ident())
                yield task

        def batch(backend, indices):
            if indices[0] >= 10:
                self.assertTrue(old_epoch_consumed.is_set(), "next epoch started before old epoch was consumed")
            return {"indices": indices}, {"aetherLookupMs": 0.001, "artifactDecodeMs": 0.002}

        def construct(*args, **kwargs):
            if iterators:
                self.assertFalse(iterators[-1].worker_alive)
            iterator = BoundedPrefetchIterator(*args, **kwargs)
            iterators.append(iterator)
            return iterator

        client = SimpleNamespace(cancel_pending_requests=lambda: self.fail("normal epoch cancelled the client"))
        context = SimpleNamespace(args=SimpleNamespace(workers=0, prefetch_batches=8, aether_engine="java"),
                                  batch=batch, store=SimpleNamespace(client=client))
        metrics = {}
        with patch("benchmark_gpu_segmentation.BoundedPrefetchIterator", side_effect=construct):
            stream = prepared_batches(context, "AETHER_CACHE", schedule(), metrics=metrics)
            try:
                self.assertEqual(next(stream)[:2], ([0], 0))
                self.assertEqual(next(stream)[:2], ([1], 0))
                old_epoch_consumed.set()
                self.assertEqual([value[:2] for value in stream], [([10], 1), ([11], 1)])
            finally:
                stream.close()
        self.assertEqual(len(iterators), 2)
        self.assertEqual([it.metrics["batchesConsumed"] for it in iterators], [2, 2])
        self.assertEqual(schedule_threads, [caller] * 4)
        self.assertEqual(metrics["batchesRequested"], 4)
        self.assertEqual(metrics["batchesConsumed"], 4)
        self.assertEqual(metrics["prefetchLookupNs"], 4000)
        self.assertEqual(metrics["prefetchDecodeNs"], 8000)
        self.assertEqual(metrics["queueDepthSamples"], 8)

    def test_java_aether_early_close_aborts_pending_request(self):
        entered, release = threading.Event(), threading.Event()
        cancellations = []
        calls = []

        def batch(backend, indices):
            calls.extend(indices)
            if indices == [1]:
                entered.set()
                if not release.wait(2):
                    raise TimeoutError("pending Java request was not cancelled")
            return {"indices": indices}, {}

        def cancel():
            cancellations.append(True)
            release.set()

        client = SimpleNamespace(cancel_pending_requests=cancel)
        context = SimpleNamespace(args=SimpleNamespace(workers=0, prefetch_batches=1, aether_engine="java"),
                                  batch=batch, store=SimpleNamespace(client=client))
        stream = prepared_batches(context, "AETHER_CACHE", [([0], 0), ([1], 0), ([2], 1)])
        try:
            self.assertEqual(next(stream)[:2], ([0], 0))
            self.assertTrue(entered.wait(2))
            stream.close()
        finally:
            release.set()
            stream.close()
        self.assertEqual(cancellations, [True])
        self.assertEqual(calls, [0, 1])

    def test_only_java_aether_receives_client_cancellation_callback(self):
        for backend, engine in (("RAW_RECOMPUTE", "java"), ("STATIC_PREPROCESSED_MMAP", "java"),
                                ("RAM_READY", "java"), ("AETHER_CACHE", "python")):
            with self.subTest(backend=backend, engine=engine):
                context = SimpleNamespace(
                    args=SimpleNamespace(workers=0, prefetch_batches=1, aether_engine=engine),
                    batch=lambda backend, indices: ({"indices": indices}, {}),
                )
                with patch("benchmark_gpu_segmentation.BoundedPrefetchIterator",
                           wraps=BoundedPrefetchIterator) as constructor:
                    list(prepared_batches(context, backend, [([0], 0)]))
                self.assertIsNone(constructor.call_args.kwargs["cancel_callback"])

    def test_early_epoch_close_discards_stale_work(self):
        calls = []

        def batch(backend, indices):
            calls.extend(indices)
            return {"indices": indices}, {}

        context = SimpleNamespace(args=SimpleNamespace(workers=0, prefetch_batches=4), batch=batch)
        metrics = {}
        stream = prepared_batches(context, "TEST", [([0], 0), ([1], 0), ([2], 1)], metrics=metrics)
        try:
            self.assertEqual(next(stream)[:2], ([0], 0))
        finally:
            stream.close()
        self.assertNotIn(2, calls)
        self.assertEqual(metrics["batchesConsumed"], 1)
        # A new wrapper has a fresh epoch queue and fresh counters.
        result = list(prepared_batches(context, "TEST", [([9], 2)], metrics=metrics))
        self.assertEqual([item[:2] for item in result], [([9], 2)])
        self.assertEqual(result[0][2][0]["indices"], [9])
        self.assertEqual(metrics["batchesRequested"], 1)
        self.assertEqual(metrics["batchesConsumed"], 1)

    def test_fixed_artifacts_labels_hashes_and_array_parity(self):
        self._assert_fixed_artifact_parity()

    def test_fixed_artifact_input_tensor_parity(self):
        try:
            import torch
        except ImportError:
            self.skipTest("CPU PyTorch required for input tensor parity")
        self._assert_fixed_artifact_parity(torch)

    def _assert_fixed_artifact_parity(self, torch=None):
        import numpy as np
        from benchmark_gpu_segmentation import normalize_cpu_batch, pack_payload, stack_values, unpack_payload

        payloads = {
            index: pack_payload({"sample_id": f"id-{index}",
                                 "image": np.arange(16, dtype=np.float32).reshape(1, 4, 4) / (index + 1),
                                 "mask": np.full((1, 4, 4), index % 2, dtype=np.float32)})
            for index in range(7)
        }
        schedule = [([4, 1, 6], 0), ([0, 5, 2], 0), ([3], 0)]
        baseline = None
        for depth in (0, 1, 2, 4, 8):
            def batch(backend, indices):
                values = [unpack_payload(payloads[index], np) for index in indices]
                tensors = stack_values(values, np)
                tensors["ids"] = [value["sample_id"] for value in values]
                tensors["hashes"] = [hashlib.sha256(payloads[index]).hexdigest() for index in indices]
                return tensors, {}

            context = SimpleNamespace(args=SimpleNamespace(workers=0, prefetch_batches=depth), batch=batch)
            observed = []
            stream = prepared_batches(context, "TEST", schedule)
            try:
                for indices, epoch, prepared, _ in stream:
                    value = context.batch("TEST", indices)[0] if prepared is None else prepared[0]
                    tensors = normalize_cpu_batch(torch, value)[0] if torch is not None else value
                    observed.append((indices, epoch, value["ids"], value["hashes"], tensors))
            finally:
                stream.close()
            self.assertEqual(sum(len(row[0]) for row in observed), 7)
            if baseline is None:
                baseline = observed
            else:
                self.assertEqual([row[:4] for row in observed], [row[:4] for row in baseline])
                for actual, expected in zip(observed, baseline):
                    for key in ("images", "masks"):
                        if torch is None:
                            np.testing.assert_array_equal(actual[4][key], expected[4][key])
                        else:
                            self.assertTrue(torch.equal(actual[4][key], expected[4][key]))


if __name__ == "__main__":
    unittest.main()
