import copy
import math

import numpy as np
import pytest
from scipy import stats

from h4.analyze_h4 import available_mean, io_delta, metric_delta, paired_statistics, process_window_metrics
from h4.generate_h4_figures import make_figures


def test_paired_log_interval_matches_scipy_and_single_block_has_no_interval():
    ratios = [1.01, 1.05, .99, 1.03, 1.02]
    result = paired_statistics(ratios)
    expected = stats.ttest_1samp(np.log(ratios), 0).confidence_interval()
    assert result["geometric_mean_ratio"] == pytest.approx(math.exp(float(np.log(ratios).mean())))
    assert result["ci_lower"] == pytest.approx(math.exp(expected.low))
    assert result["ci_upper"] == pytest.approx(math.exp(expected.high))
    assert result["paired_blocks"] == 5
    one = paired_statistics([1.1])
    assert one["ci_lower"] is one["ci_upper"] is None
    assert not one["inference_available"]
    assert paired_statistics([1.2] * 5)["ci_lower"] == pytest.approx(1.2)


@pytest.mark.parametrize("ratios", [[], [0], [-1], [float("nan")], [float("inf")]])
def test_invalid_ratios_never_produce_statistics(ratios):
    with pytest.raises(ValueError):
        paired_statistics(ratios)


def test_missing_counters_are_not_imputed_as_zero():
    before = {"memory.stat": dict(available=True, text="pgmajfault 2\n"),
              "io.stat": dict(available=True, text="8:0 rbytes=4096 wbytes=0\n")}
    after = {"memory.stat": dict(available=True, text="pgmajfault 5\n"),
             "io.stat": dict(available=True, text="8:0 rbytes=8192 wbytes=0\n")}
    assert metric_delta(before, after, "memory.stat", "pgmajfault") == 3
    assert io_delta(before, after, "rbytes") == 4096
    assert metric_delta(before, after, "memory.stat", "file") is None
    assert io_delta(None, after, "rbytes") is None
    assert available_mean([None, 0, 2]) == dict(mean=1, available=2, total=3)
    assert available_mean([None]) == dict(mean=None, available=0, total=1)
    after["io.stat"]["text"] = "8:0 rbytes=1\n"
    assert io_delta(before, after, "rbytes") is None


def test_process_cpu_faults_and_rss_use_archived_linux_units():
    def state(cpu, system, faults, rss):
        fields = ["0"] * 30
        fields[0], fields[9], fields[11], fields[12], fields[21] = "R", str(faults), str(cpu), str(system), str(rss)
        return dict(stat=dict(available=True, text="123 (worker (test)) " + " ".join(fields)),
                    clock_ticks_per_second=100, page_size_bytes=4096)
    item = dict(workload_pids=[123], processes_before={"123": state(100, 10, 3, 100)},
                processes_after={"123": state(140, 20, 5, 120)})
    result = process_window_metrics(item)
    assert result["cpu_seconds"] == .5
    assert result["process_major_page_faults"] == 2
    assert result["sampled_process_rss_bytes"] == 120 * 4096
    item["processes_after"]["123"]["clock_ticks_per_second"] = 200
    assert process_window_metrics(item)["cpu_seconds"] is None


def figure_fixture():
    summary = dict(schema="aether-h4-analysis-v1", phase="test-fixture-not-experiment",
                   primary_h4_eligible=False, conditions=[], observations=[])
    for condition, ratio in enumerate((.25, .5, 1, 2, 4)):
        summary["observations"].append(dict(condition=condition, payload_bytes=int(ratio * 2 * 1024 ** 3)))
        for pattern in ("sequential", "shuffled"):
            backends = {}
            for name, rate in (("aether", 100), ("mmap", 110)):
                missing = available_mean([None] * 5)
                backends[name] = dict(throughput=available_mean([rate / ratio] * 5),
                                     latency={key: available_mean([value] * 5) for key, value in (("p50", .001), ("p95", .002), ("p99", .003))},
                                     resources={key: copy.deepcopy(missing) for key in ("physical_read_bytes", "file_refaults", "major_page_faults")},
                                     store_allocated_bytes=copy.deepcopy(missing),
                                     store_file_bytes=available_mean([ratio * 2 * 1024 ** 3] * 5))
            summary["conditions"].append(dict(condition=condition, requested_ratio=ratio, access_pattern=pattern,
                                               paired_statistics=paired_statistics([.9, .91, .89, .92, .9]), backends=backends))
    return summary


def test_five_figures_have_bitmap_content_and_keep_unavailable_resource_data(tmp_path):
    from PIL import Image
    summary = figure_fixture()
    unchanged = copy.deepcopy(summary)
    files = make_figures(summary, tmp_path)
    assert len(files) == 10
    assert summary == unchanged
    for number in range(1, 6):
        bitmap = np.asarray(Image.open(tmp_path / f"h4-{number}.png").convert("RGB"))
        assert bitmap.shape[0] > 500 and bitmap.shape[1] > 1000
        assert (bitmap < 220).any(axis=2).sum() > 10000
        assert (tmp_path / f"h4-{number}.pdf").stat().st_size > 1000
    with pytest.raises(ValueError, match="empty"):
        make_figures(summary, tmp_path)
