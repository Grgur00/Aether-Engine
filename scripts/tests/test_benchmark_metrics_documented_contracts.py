"""Injected observations for documentation; no GPU command execution."""
import sys
from types import SimpleNamespace

import pytest

import benchmark_gpu_segmentation as workload


@pytest.mark.parametrize("value,expected", [(True, 1.), ("42 %", 42.),
    ("1e3 bytes", 1.), (".5", 5.), ("-3.25 percent", -3.25), ("N/A", None), (None, None)])
def test_numeric_extraction_is_not_strict_units_or_scientific_notation(value, expected):
    assert workload.numeric_value(value) == expected


def test_flatten_and_key_heuristics_do_not_validate_units():
    fields = dict(workload.flatten_json({"Devices": [{"GPU Usage": "20%", "VRAM Allocated": "4096 bytes"}],
                                         "other": "N/A"}))
    assert fields == {"devices[0].gpu usage": 20., "devices[0].vram allocated": 4096., "other": None}
    assert workload.is_gpu_utilization_key("devices[0].gpu usage")
    assert not workload.is_gpu_utilization_key("GPU_USAGE")
    assert workload.is_memory_utilization_key("devices[0].vram allocated")
    report = workload.GpuUtilizationSampler(250)._sample_from_payload({"VRAM Allocated": "4096 bytes"})
    assert report["gpuUtilizationPercent"] is None and report["memoryUtilizationPercent"] == 4096


def test_nvidia_partial_rows_average_independent_device_columns():
    sampler = workload.GpuUtilizationSampler(250)
    result = sampler._sample_from_nvidia_smi("20, 50\n80, N/A\nN/A, 100\ninvalid\n")
    assert result["gpuUtilizationPercent"] == 50 and result["memoryUtilizationPercent"] == 75
    assert sampler._sample_from_nvidia_smi("N/A, N/A") is None
    assert sampler._sample_from_nvidia_smi("120, -1")["gpuUtilizationPercent"] == 120


def test_command_fallback_preserves_error_after_success(monkeypatch):
    calls = []
    def run(command, **kwargs):
        calls.append((command, kwargs))
        if command[0] == "nvidia-smi":
            return SimpleNamespace(returncode=1, stdout="", stderr="query failed\nmore")
        return SimpleNamespace(returncode=0, stdout='{"GPU Usage": 30}', stderr="")
    monkeypatch.setattr(workload.subprocess, "run", run)
    sampler = workload.GpuUtilizationSampler(250)
    result = sampler._sample_once()
    assert result["gpuUtilizationPercent"] == 30
    assert sampler.source == "amd-smi metric --usage --json" and sampler.error == "query failed"
    assert [command[0] for command, _ in calls] == ["nvidia-smi", "amd-smi"]
    assert all(kwargs == dict(capture_output=True, text=True, timeout=2) for _, kwargs in calls)


def test_missing_tools_unparsable_and_nonjson_fall_through(monkeypatch):
    def run(command, **kwargs):
        if command[0] == "nvidia-smi":
            raise FileNotFoundError("missing")
        if command[0] == "amd-smi":
            return SimpleNamespace(returncode=0, stdout="not json", stderr="")
        return SimpleNamespace(returncode=0, stdout="{}", stderr="")
    monkeypatch.setattr(workload.subprocess, "run", run)
    sampler = workload.GpuUtilizationSampler(250)
    sample = sampler._sample_once()
    assert sample["gpuUtilizationPercent"] is sample["memoryUtilizationPercent"] is None
    assert sampler.source.startswith("rocm-smi") and "non-json" in sampler.error


def test_disabled_sampler_has_unavailable_status_without_probe(monkeypatch):
    sampler = workload.GpuUtilizationSampler(0)
    monkeypatch.setattr(sampler, "_sample_once", lambda: pytest.fail("disabled sampler queried"))
    sampler.start()
    report = sampler.stop()
    assert report["status"] == "UNAVAILABLE" and report["error"] == "disabled"
    assert sampler._thread is None


def test_start_no_source_does_not_create_thread(monkeypatch):
    sampler = workload.GpuUtilizationSampler(250)
    monkeypatch.setattr(sampler, "_sample_once", lambda: None)
    sampler.start()
    assert sampler._thread is None and sampler.stop()["samples"] == []


def test_stop_bounded_join_and_shared_sample_list():
    sampler = workload.GpuUtilizationSampler(500)
    joined = []
    sampler._thread = SimpleNamespace(join=lambda **kwargs: joined.append(kwargs))
    sampler.samples.append({"gpuUtilizationPercent": None})
    report = sampler.stop()
    assert sampler._stop.is_set() and joined == [dict(timeout=2.)]
    assert report["status"] == "SAMPLED" and report["samples"] is sampler.samples
    sampler.samples.append({"gpuUtilizationPercent": 1})
    assert len(report["samples"]) == 2


def test_run_retries_failure_with_previous_source(monkeypatch):
    sampler = workload.GpuUtilizationSampler(250)
    sampler.source = "previous-source"
    results = iter([None, {"gpuUtilizationPercent": 10}])
    waits = []
    def wait(seconds):
        waits.append(seconds)
        if len(waits) == 2:
            sampler._stop.set()
    monkeypatch.setattr(sampler, "_sample_once", lambda: next(results))
    monkeypatch.setattr(sampler._stop, "wait", wait)
    sampler._run()
    assert len(sampler.samples) == 1 and waits == [.25, .25]


def test_process_delta_host_core_normalization_lifetime_rss_and_clamps():
    start = dict(processTimeSeconds=4, rssPeakBytes=900, io=dict(read_bytes=20, cancelled_write_bytes=10))
    end = dict(processTimeSeconds=6, rssPeakBytes=800, cpuCount=8,
               io=dict(read_bytes=10, write_bytes=15, cancelled_write_bytes=5))
    report = workload.process_metrics_delta(start, end, 1000)
    assert report["cpuTimeSeconds"] == 2 and report["cpuUtilizationMean"] == 25
    assert report["rssPeakBytes"] == 900
    assert report["diskReadBytes"] == report["diskCancelledWriteBytes"] == 0
    assert report["diskWriteBytes"] == 15
    assert report["source"] == "process_time+/proc/self/io"
    assert workload.process_metrics_delta(end, start, 1000)["cpuTimeSeconds"] == 0


def test_missing_io_and_rss_are_none_not_zero():
    report = workload.process_metrics_delta(dict(processTimeSeconds=0, io={}),
        dict(processTimeSeconds=1, cpuCount=1, io={}), 0)
    assert report["cpuUtilizationMean"] > 100
    assert report["rssPeakBytes"] is report["diskReadBytes"] is None
    assert report["source"] == "process_time"


def test_snapshot_fallback_cpu_count_and_helper_values(monkeypatch):
    monkeypatch.setattr(workload, "time", SimpleNamespace(process_time=lambda: 2.5))
    monkeypatch.setattr(workload, "os", SimpleNamespace(cpu_count=lambda: None))
    monkeypatch.setattr(workload, "rss_peak_bytes", lambda: 100)
    monkeypatch.setattr(workload, "proc_self_io", lambda: {"read_bytes": 3})
    assert workload.process_metrics_snapshot() == dict(processTimeSeconds=2.5, rssPeakBytes=100,
                                                       io={"read_bytes": 3}, cpuCount=1)


@pytest.mark.parametrize("platform,expected", [("darwin", 12), ("linux", 12288)])
def test_resource_rss_platform_units(monkeypatch, platform, expected):
    monkeypatch.setitem(sys.modules, "resource", SimpleNamespace(RUSAGE_SELF=0,
        getrusage=lambda who: SimpleNamespace(ru_maxrss=12)))
    monkeypatch.setattr(workload, "sys", SimpleNamespace(platform=platform))
    assert workload.rss_peak_bytes() == expected


def test_proc_io_fixture_missing_and_malformed(tmp_path, monkeypatch):
    path = tmp_path / "proc-io"
    monkeypatch.setattr(workload, "Path", lambda requested: path)
    assert workload.proc_self_io() is None
    path.write_text("read_bytes: 10\nwrite_bytes: 20\n", encoding="utf-8")
    assert workload.proc_self_io() == {"read_bytes": 10, "write_bytes": 20}
    path.write_text("read_bytes: 10\nmalformed\n", encoding="utf-8")
    assert workload.proc_self_io() is None
