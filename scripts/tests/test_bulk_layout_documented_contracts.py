"""CPU-only checks for the layout sampler's documented lifecycle and reports."""
import os

import pytest

import bulk_layout


def test_sampler_combined_peak_is_not_sum_of_individual_peaks(monkeypatch):
    sampler = bulk_layout.MemorySampler(12345)
    samples = iter([(True, 100), (True, 20), (True, 10), (True, 200)])
    calls = []

    def snapshot(pid):
        calls.append(pid)
        available, rss = next(samples)
        if len(calls) == 4:
            sampler.done.set()
        return {"available": available, "rssBytes": rss}

    monkeypatch.setattr(bulk_layout.base, "snapshot", snapshot)
    sampler.start()
    sampler.thread.join(timeout=2)
    assert not sampler.thread.is_alive()
    report = sampler.stop()
    assert calls == [os.getpid(), 12345, os.getpid(), 12345]
    assert report["pythonPeakRssBytes"] == 100
    assert report["javaPeakRssBytes"] == 200
    assert report["combinedPeakRssBytes"] == 210
    assert report["sampleIntervalMs"] == 20


def test_sampler_unavailable_process_has_no_peak(monkeypatch):
    sampler = bulk_layout.MemorySampler(12345)

    def snapshot(pid):
        if pid == 12345:
            sampler.done.set()
            return {"available": False}
        return {"available": True, "rssBytes": 64}

    monkeypatch.setattr(bulk_layout.base, "snapshot", snapshot)
    sampler.start()
    sampler.thread.join(timeout=2)
    assert not sampler.thread.is_alive()
    report = sampler.stop()
    assert report["pythonPeakRssBytes"] == 64
    assert report["javaPeakRssBytes"] is None
    assert report["combinedPeakRssBytes"] is None


def test_sampler_stop_before_start_raises():
    sampler = bulk_layout.MemorySampler(12345)
    with pytest.raises(RuntimeError, match="before it is started"):
        sampler.stop()
    assert sampler.done.is_set()
