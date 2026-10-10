"""Synthetic pilot fixtures, never performance evidence."""
import csv
import json
import sys
from pathlib import Path

import numpy as np
import pytest
from scipy import stats

import analyze
import reproduce
from prepare_evolution import prepare
from run_matrix import BACKEND_IDS, build_plan, parser


def test_twenty_epoch_pilot_entrypoint(tmp_path, monkeypatch):
    config = Path(__file__).resolve().parents[2] / "configs/paper/oct5k-pilot-20ep.json"
    calls = []
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "pilot", "--config", str(config),
                                    "--epochs", "20", "--prefetch-depth", "0", "--output", str(tmp_path)])
    reproduce.main()
    matrix = next(command for command in calls if "scripts/run_matrix.py" in command)
    plan = build_plan(parser().parse_args(matrix[2:]))
    assert (plan["epochs"], plan["repeats"], plan["prefetchDepth"], plan["batchSize"]) == (20, 10, 0, 16)
    assert not plan["confirmatory"] and not plan["serverTrace"]
    spec = plan["conditions"][0]["specification"]
    assert spec["samplesV1"] == spec["expectedReusable"] == 1430
    assert spec["samplesV2"] - spec["expectedReusable"] == 75
    assert any("scripts/analyze.py" in command and "--pilot" in command for command in calls)


def test_additive_manifests_have_exactly_75_new_samples(tmp_path):
    source = tmp_path / "full.csv"
    source.write_text("sample_id,split\n" + "".join(f"sample-{i},train\n" for i in range(1505)))
    destination = tmp_path / "prepared"
    prepare(source, destination, v1_size=1430, v2_size=1505, reusable=1430, seed=20260924)
    memberships = []
    for name in ("v1.csv", "v2.csv"):
        with (destination / name).open(newline="") as stream:
            memberships.append({row["sample_id"] for row in csv.DictReader(stream)})
    v1, v2 = memberships
    assert len(v1) == 1430 and len(v2) == 1505
    assert v1 <= v2 and len(v2 - v1) == 75
    with pytest.raises(FileExistsError):
        prepare(source, destination, v1_size=1430, v2_size=1505, reusable=1430)


def pilot_fixture(root, condition="condition", count=10):
    blocks = []
    for index in range(count):
        directory = root / condition / f"block-{index:04d}"
        directory.mkdir(parents=True)
        scale = 1 + index / 100
        a_walls = [40 * scale] + [10 * scale] * 19
        m_walls = [20.] * 20
        block = dict(protocolHash="protocol", conditionId=condition, environmentId="environment",
                     blockIndex=index, throughput=dict(aether=30100 / sum(a_walls),
                                                      mmap=30100 / sum(m_walls), raw=50.))
        (directory / "block.json").write_text(json.dumps(block))
        backends = {}
        for name, walls in (("aether", a_walls), ("mmap", m_walls)):
            backends[BACKEND_IDS[name]] = dict(epochWallMs=walls, lifecycle=dict(populateMs=0.),
                steps=[dict(epoch=epoch, batchSize=1505, batchPrepareMs=wall / 2,
                            sourceLoadMs=1. if epoch == 0 else 0., preprocessMs=2. if epoch == 0 else 0.)
                       for epoch, wall in enumerate(walls)])
        run = dict(backends=backends, cacheDynamics=dict(prepopulatedEntries=1430),
                   mmapDynamics=dict(initialReusableEntries=1430))
        (directory / "training.json").write_text(json.dumps(dict(runs=[run])))
        blocks.append(block)
    return blocks


def test_pilot_exports_all_epochs_checkpoints_and_input_costs(tmp_path, monkeypatch):
    blocks = pilot_fixture(tmp_path)
    (tmp_path / "protocol.json").write_text(json.dumps(dict(epochs=20, measuredSteps=0)))
    monkeypatch.setattr(analyze, "load_blocks", lambda _: blocks)
    destination = tmp_path / "analysis"
    analyze.main(["--input", str(tmp_path), "--output", str(destination), "--pilot", "--bootstrap-resamples", "100"])
    report = json.loads((destination / "analysis.json").read_text())
    assert "20" in report["mainEndpoint"]
    assert "separate pilot" in report["measurementRole"]
    group = report["groups"][0]
    curve = group["secondaryAmortization"]
    assert len(curve["curves"]) == 20
    assert [point["epochs"] for point in curve["checkpoints"]] == [1, 5, 10, 20]
    for point in curve["checkpoints"]:
        k = point["epochs"]
        ratios = np.array([20 * k / ((40 + 10 * (k - 1)) * (1 + i / 100)) for i in range(10)])
        np.testing.assert_allclose(point["pairedTrainingRatios"], ratios)
        assert point["trainingRatioGeometricMean"] == pytest.approx(np.exp(np.log(ratios).mean()))
        expected = stats.ttest_1samp(np.log(ratios), 0).confidence_interval(.9)
        np.testing.assert_allclose(point["trainingRatioCI90"], np.exp(expected))
    costs = group["secondaryUpdateCosts"]["blocks"][0]["backends"]
    assert costs["aether"]["inputPreparationMs"] == 20
    assert costs["mmap"]["inputPreparationMs"] == 10
    assert costs["aether"]["sourceAndPreprocessMs"] == 3
    for name, expected_count in (("amortization.csv", 20), ("checkpoints.csv", 4)):
        with (destination / name).open(newline="") as stream:
            assert len(list(csv.DictReader(stream))) == expected_count


@pytest.mark.parametrize("change", ["short", "nan", "zero", "protocol", "partial"])
def test_reject_incomplete_or_invalid_epoch_curves(tmp_path, change):
    pilot_fixture(tmp_path, count=1)
    path = tmp_path / "condition/block-0000/training.json"
    report = json.loads(path.read_text())
    walls = report["runs"][0]["backends"][BACKEND_IDS["aether"]]["epochWallMs"]
    if change == "short":
        walls.pop()
    elif change in {"zero", "nan"}:
        walls[0] = 0 if change == "zero" else float("nan")
    else:
        (tmp_path / "protocol.json").write_text(json.dumps(dict(epochs=10 if change == "protocol" else 20,
                                                               measuredSteps=3 if change == "partial" else 0)))
    path.write_text(json.dumps(report))
    with pytest.raises(ValueError):
        analyze.amortization(tmp_path)


def test_curves_keep_conditions_separate(tmp_path):
    pilot_fixture(tmp_path, condition="first", count=2)
    pilot_fixture(tmp_path, condition="second", count=3)
    result = analyze.amortization(tmp_path, identities={("protocol", "first", "environment")})
    assert result["curves"][0]["n"] == 2


def test_notebook_prepares_pilot_once_and_checks_receipt(tmp_path):
    root = Path(__file__).resolve().parents[2]
    notebook = json.loads((root / "kaggle/aether_paper.ipynb").read_text(encoding="utf-8"))
    code = "".join(next(cell for cell in notebook["cells"] if cell["id"] == "aether-08")["source"])
    source = tmp_path / "full.csv"
    source.write_text("sample_id,split\n" + "".join(f"sample-{i},train\n" for i in range(1505)))
    data = tmp_path / "data"
    calls = []

    def run(script, *args):
        calls.append((script, args))
        if script == "prepare_evolution.py":
            prepare(source, data, v1_size=1430, v2_size=1505, reusable=1430, seed=20260924)

    namespace = dict(json=json, Path=lambda value: data if str(value) == "/kaggle/working/aether-data/oct5k-75-new" else Path(value),
                     config={"oct5k": {"manifestV2": str(source)}}, REPO=root, RESULTS=tmp_path / "results", run=run,
                     RUN_CONFIRMATORY=False)
    exec(code, namespace)
    exec(code, namespace)
    assert sum(script == "prepare_evolution.py" for script, _ in calls) == 1
    command = next(args for script, args in calls if script == "reproduce.py")
    assert command[0] == "pilot"
    assert command[command.index("--epochs") + 1] == 20
    assert command[command.index("--pilot-repeats") + 1] == 10
    (data / "v1.csv").write_text("altered")
    with pytest.raises(RuntimeError, match="receipt"):
        exec(code, namespace)
