"""Synthetic fixtures exercise protocol guards; never research evidence."""
import copy
import json
import sys

import numpy as np
import pytest
from scipy import stats

from analyze import analyze_blocks, paired_analysis
from confirmatory import validate_training
from experiment_output import freeze_metadata
from run_matrix import parser, build_plan


def test_frozen_plan():
    plan = build_plan(parser().parse_args(["--confirmatory", "--epochs", "10", "--prefetch-depth", "0"]))
    assert plan["confirmatoryDesign"]["equivalenceBounds"] == [.97, 1.03]
    assert plan["repeats"] == 24


@pytest.mark.parametrize("option,value", [("--epochs", "5"), ("--prefetch-depth", "1"),
    ("--repeats", "25"), ("--repeats", "10"), ("--batch-size", "32"),
    ("--sizes", "1504"), ("--preprocess-passes", "2"), ("--gpu-counts", "2")])
def test_reject_protocol_drift(option, value):
    with pytest.raises(ValueError):
        build_plan(parser().parse_args(["--confirmatory", "--epochs", "10", "--prefetch-depth", "0", option, value]))


def test_primary_entrypoint_forwards_frozen_configuration(tmp_path, monkeypatch):
    import reproduce
    calls = []
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "primary", "--output", str(tmp_path)])
    reproduce.main()
    matrix = next(c for c in calls if "scripts/run_matrix.py" in c)
    plan = build_plan(parser().parse_args(matrix[2:]))
    assert plan["confirmatoryDesign"]["epochs"] == 10
    assert not any("--holm" in c or "--pilot" in c for c in calls)


def blocks():
    return [dict(protocolHash="fixture", conditionId="fixture", environmentId="fixture", blockIndex=i,
                 throughput=dict(aether=float(np.exp(x)), mmap=1., raw=1.))
            for i, x in enumerate(np.linspace(-.01, .01, 24))]


def test_single_primary_passes_even_without_secondary_superiority():
    report = analyze_blocks(blocks(), confirmatory=True, resamples=100)["groups"][0]
    assert report["aetherOverMmap"]["primaryEquivalent"]
    assert not report["aetherOverRaw"]["secondarySuperior"]
    assert "holmP" not in report["aetherOverMmap"]
    expected = stats.ttest_1samp(np.linspace(-.01, .01, 24), 0, alternative="greater")
    assert report["aetherOverRaw"]["pairedTTestGreaterP"] == pytest.approx(expected.pvalue)


@pytest.mark.parametrize("change", ["short", "mixed", "margin", "alpha"])
def test_reject_incomplete_or_redefined_analysis(change):
    records, options = blocks(), {}
    if change == "short":
        records.pop()
    elif change == "mixed":
        records[0]["environmentId"] = "other"
    else:
        options[change] = .1
    with pytest.raises(ValueError):
        analyze_blocks(records, confirmatory=True, resamples=10, **options)


def test_tost_ci_matches_reference():
    values = np.linspace(-.012, .008, 24)
    result = paired_analysis(np.exp(values), np.ones(24), resamples=100)
    interval = stats.ttest_1samp(values, 0).confidence_interval(.9)
    np.testing.assert_allclose(result["tost"]["ratioCI"], np.exp(interval))
    low, high = result["tost"]["ratioCI"]
    assert result["tost"]["equivalentUnadjusted"] == (.97 < low and high < 1.03)


def test_orphaned_pilot_blocks_cannot_be_adopted(tmp_path):
    block = tmp_path / "condition/block-0000/block.json"
    block.parent.mkdir(parents=True)
    block.write_text("{}")
    with pytest.raises(ValueError, match="existing measurements"):
        freeze_metadata(tmp_path, {}, "fixture", {}, True)
    assert not (tmp_path / "protocol.json").exists()


def test_measured_runtime_policy_must_match():
    report = {"configuration": dict(samples=1505, epochs=10, batchSize=16, prefetchBatches=0,
        workers=0, gpuCount=1, preprocessPasses=4, measuredSteps=0, warmupSteps=0, serverTrace=False),
        "runs": [{"engineInfo": {"integrityPolicy": {"version": "immutable-inline-admission-v1"},
                  "backgroundCompaction": {"enabled": True}}, "cacheDynamics": {"prepopulatedEntries": 1003},
                  "backends": {"fixture": {"epochWallMs": [1.] * 10,
                    "steps": [{"batchSize": 1505, "epoch": epoch} for epoch in range(10)]}}}]}
    validate_training(report)
    changed = copy.deepcopy(report)
    changed["runs"][0]["engineInfo"]["backgroundCompaction"]["enabled"] = False
    with pytest.raises(ValueError, match="compaction"):
        validate_training(changed)


def test_amortization_uses_only_completed_blocks(tmp_path):
    from analyze import amortization
    from run_matrix import BACKEND_IDS
    directory = tmp_path / "fixture/block-0000"
    directory.mkdir(parents=True)
    (directory / "block.json").write_text("{}")
    run = {"backends": {BACKEND_IDS[name]: {"epochWallMs": [wall] * 10,
        "lifecycle": {"populateMs": setup}} for name, wall, setup in [("aether", 2., 0.), ("mmap", 1., 2.)]}}
    (directory / "training.json").write_text(json.dumps({"runs": [run]}))
    interrupted = tmp_path / "fixture/block-0001-interrupted"
    interrupted.mkdir()
    (interrupted / "training.json").write_text("invalid unfinished evidence")
    curves = amortization(tmp_path)["curves"]
    assert len(curves) == 10
    assert curves[0]["trainingRatioGeometricMean"] == pytest.approx(.5)
    assert curves[0]["includingV2PreparationRatioGeometricMean"] == pytest.approx(1.5)
    assert curves[-1]["includingV2PreparationRatioGeometricMean"] == pytest.approx(.6)
