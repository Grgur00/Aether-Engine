"""Source-backed documentation checks, never a real H2 measurement or GPU run."""
import itertools
import math
import random
from types import SimpleNamespace

import numpy as np
import pytest
from scipy import stats

import h2_analysis as analysis
import h2_confirmatory as runner
import h2_input_paths as inputs
import h2_protocol as protocol
from longitudinal_state import BACKENDS, cumulative


def arithmetic_blocks():
    blocks = []
    for index, order in enumerate(itertools.permutations(BACKENDS)):
        results = {}
        for backend in BACKENDS:
            stages = []
            for _ in range(5):
                phases = dict(startup=1., modelSetup=2., scanAdmission=3., training=100., drain=4., close=5.)
                stages.append(dict(timingsMs=phases, fullLifecycleMs=sum(phases.values())))
            results[backend] = dict(stages=stages, cumulativeMs=cumulative(stages))
        blocks.append(dict(blockIndex=index, backendOrder=list(order), measurementRole="confirmatory",
            technicalFailure=False, correctnessPassed=True, sourceHash="a" * 64,
            frozenProtocolHash="b" * 64, backendResults=results))
    return blocks


def set_mmap_ratios(blocks, values):
    for block in blocks:
        previous = 0.
        result = block["backendResults"]["mmap"]
        aether = block["backendResults"]["aether"]["cumulativeMs"]
        for version, (stage, ratio) in enumerate(zip(result["stages"], values)):
            endpoint = aether[f"V{version}"] * ratio
            phases = stage["timingsMs"]
            overhead = sum(value for key, value in phases.items() if key != "training")
            phases["training"] = endpoint - previous - overhead
            assert phases["training"] > 0
            stage["fullLifecycleMs"] = sum(phases.values())
            previous = endpoint
        result["cumulativeMs"] = cumulative(result["stages"])


@pytest.mark.parametrize("seed", [True, False, 42., "42", None])
def test_schedule_requires_actual_integer(seed):
    with pytest.raises(ValueError, match="integer"):
        protocol.schedule(seed)


def test_order_seed_changes_order_but_not_block_seeds_or_global_rng():
    before = random.getstate()
    first, second = protocol.schedule(42), protocol.schedule(43)
    assert random.getstate() == before
    assert first != second
    assert [row["seed"] for row in first] == [row["seed"] for row in second] == list(range(20260926, 20260950))


def test_declared_config_guard_allows_unrecognized_extra_metadata():
    config = protocol.read(protocol.ROOT / "configs/paper/oct5k-h2-confirmatory.json")
    config["documentationOnly"] = "not a schema-wide unknown-key ban"
    assert protocol.validate_config(config) is None


def test_source_inventory_is_packaging_scope_not_entire_tree(tmp_path, monkeypatch):
    monkeypatch.setattr(protocol, "ROOT_FILES", ("README.md", "missing-root-file"))
    monkeypatch.setattr(protocol, "DIRECTORIES", ("scripts", "clients/python"))
    monkeypatch.setattr(protocol, "EXCLUDED", {"build"})
    for name in ("README.md", "scripts/run.py", "scripts/build/ignored.py",
                 "clients/python/helper.py", "clients/python/README.md", "clients/python/data.bin",
                 "unlisted/other.py"):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("fixture", encoding="utf-8")
    assert set(protocol.source_files(tmp_path)) == {
        "README.md", "scripts/run.py", "clients/python/helper.py", "clients/python/README.md"}


def test_seal_excludes_completion_basename_even_in_nested_directory(tmp_path):
    (tmp_path / "nested").mkdir()
    excluded = tmp_path / "nested/completion.json"
    excluded.write_text("first", encoding="utf-8")
    (tmp_path / "result.json").write_text("{}", encoding="utf-8")
    runner.seal(tmp_path)
    excluded.write_text("changed", encoding="utf-8")
    runner.verify_seal(tmp_path)
    assert protocol.read(tmp_path / "completion.json")["files"].keys() == {"result.json"}


def test_seal_rejects_an_added_inventoried_file(tmp_path):
    runner.seal(tmp_path)
    (tmp_path / "late.txt").write_text("extra", encoding="utf-8")
    with pytest.raises(ValueError, match="extended"):
        runner.verify_seal(tmp_path)


def test_binding_accepts_writable_directory_and_resolution_does_not_require_file(tmp_path):
    logical = tmp_path.parent / "logical-input"
    mapping = inputs.binding(tmp_path, logical)
    target = inputs.resolve_path(logical / "missing.png", mapping)
    assert target == tmp_path / "missing.png"
    assert not target.exists()
    (tmp_path / "writable.txt").write_text("still writable", encoding="utf-8")


def test_nested_metadata_binding_restores_outer_and_original_resolvers(tmp_path):
    original = lambda value, root: "original"
    workload = SimpleNamespace(resolve_manifest_path=original)
    logical = tmp_path.parent / "logical-input"
    mapping = inputs.binding(tmp_path, logical)
    with inputs.input_paths(workload, mapping):
        outer = workload.resolve_manifest_path
        with pytest.raises(RuntimeError, match="fixture"):
            with inputs.input_paths(workload, mapping):
                assert workload.resolve_manifest_path(logical / "image.png", None) == tmp_path / "image.png"
                raise RuntimeError("fixture")
        assert workload.resolve_manifest_path is outer
    assert workload.resolve_manifest_path is original


@pytest.mark.parametrize("values", [
    [1.] * 23, [1.] * 25, [0.] + [1.] * 23, [-1.] + [1.] * 23,
    [math.nan] + [1.] * 23, [math.inf] + [1.] * 23,
])
def test_ratio_requires_24_positive_finite_observations(values):
    with pytest.raises(ValueError, match="24 positive finite"):
        analysis.ratios(values)


def test_ratio_is_geometric_with_log_interval_and_baseline_relative_reduction():
    logs = np.linspace(-.2, .3, 24)
    values = np.exp(logs)
    result = analysis.ratios(values)
    radius = stats.t.ppf(.975, 23) * logs.std(ddof=1) / math.sqrt(24)
    expected = math.exp(logs.mean())
    assert result["geometricMeanRatio"] == pytest.approx(expected)
    assert result["geometricMeanRatio"] != pytest.approx(values.mean())
    assert result["twoSidedCI95"] == pytest.approx(np.exp([logs.mean() - radius, logs.mean() + radius]))
    assert result["percentTimeReduction"] == pytest.approx(100 * (1 - 1 / expected))


def test_mean_ci_helper_has_fixed_24_denominator_without_own_count_guard():
    values = np.array([[1., 2.], [3., 4.], [5., 6.]])
    mean, low, high = analysis.mean_ci(values)
    expected_radius = stats.t.ppf(.975, 23) * values.std(axis=0, ddof=1) / math.sqrt(24)
    np.testing.assert_allclose(mean, values.mean(axis=0))
    np.testing.assert_allclose(high - mean, expected_radius)
    np.testing.assert_allclose(mean - low, expected_radius)


def test_favorable_constant_ratios_do_not_fabricate_primary_significance():
    blocks = arithmetic_blocks()
    set_mmap_ratios(blocks, [2.] * 5)
    primary = analysis.analyze(blocks)["comparisons"]["mmap"]["primaryEndpoint"]
    assert primary["geometricMeanRatio"] == pytest.approx(2.)
    assert primary["degenerateVariance"] is True
    assert primary["oneSidedP"] is None and primary["tStatistic"] is None
    assert primary["superiority"] is False


def test_ties_qualify_per_block_break_even_but_not_aggregate_advantage():
    comparison = analysis.analyze(arithmetic_blocks())["comparisons"]["mmap"]
    assert all(row["firstVersion"] == 0 and not row["advantageLostLater"] for row in comparison["perBlockBreakEven"])
    assert comparison["aggregateBreakEven"] is None
    assert comparison["aggregateAdvantageLostLater"] is False
    assert comparison["primaryEndpoint"]["ties"] == 24
    assert comparison["primaryEndpoint"]["wins"] == 0


def test_first_crossing_does_not_promise_permanent_advantage():
    blocks = arithmetic_blocks()
    set_mmap_ratios(blocks, [1., .99, 1.02, .99, 1.03])
    comparison = analysis.analyze(blocks)["comparisons"]["mmap"]
    assert all(row["firstVersion"] == 0 and row["advantageLostLater"] for row in comparison["perBlockBreakEven"])
    assert comparison["aggregateBreakEven"] == 2
    assert comparison["aggregateAdvantageLostLater"] is True


def test_direct_analysis_helper_does_not_certify_worker_device_or_complete_stage_schema():
    blocks = arithmetic_blocks()
    for block in blocks:
        for result in block["backendResults"].values():
            for stage in result["stages"]:
                stage["device"] = "cpu"
                stage["traceEnabled"] = True
                assert "epochs" not in stage
    assert analysis.analyze(blocks)["completePairedBlocks"] == 24
