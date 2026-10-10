"""Synthetic fixtures exercise protocol guards; never research evidence."""
import copy
import json
import sys

import numpy as np
import pytest
from scipy import stats

from analyze import analyze_blocks, paired_analysis
from confirmatory import DESIGN, MANIFEST_HASHES, PRIMARY_CONFIG, SEED_BASE, validate_plan, validate_training
from experiment_output import freeze_metadata
from run_matrix import parser, build_plan


def test_frozen_plan():
    plan = build_plan(parser().parse_args(["--confirmatory", "--epochs", "20", "--prefetch-depth", "0",
                                          "--config", PRIMARY_CONFIG, "--seed-base", str(SEED_BASE)]))
    assert plan["confirmatoryDesign"]["equivalenceBounds"] == [.97, 1.03]
    assert plan["repeats"] == 24 and plan["confirmatoryDesign"]["reusable"] == 1430


@pytest.mark.parametrize("option,value", [("--epochs", "5"), ("--prefetch-depth", "1"),
    ("--repeats", "25"), ("--repeats", "10"), ("--batch-size", "32"),
    ("--sizes", "1504"), ("--preprocess-passes", "2"), ("--gpu-counts", "2"),
    ("--epochs", "10"), ("--seed-base", "20260904"), ("--config", "configs/paper/datasets.json")])
def test_reject_protocol_drift(option, value):
    with pytest.raises(ValueError):
        build_plan(parser().parse_args(["--confirmatory", "--epochs", "20", "--prefetch-depth", "0",
                                       "--config", PRIMARY_CONFIG, "--seed-base", str(SEED_BASE), option, value]))


def test_primary_entrypoint_forwards_frozen_configuration(tmp_path, monkeypatch):
    import reproduce
    calls = []
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "primary", "--output", str(tmp_path)])
    reproduce.main()
    matrix = next(c for c in calls if "scripts/run_matrix.py" in c)
    plan = build_plan(parser().parse_args(matrix[2:]))
    assert plan["confirmatoryDesign"]["epochs"] == 20
    assert plan["seedBase"] == SEED_BASE
    assert not any("--holm" in c or "--pilot" in c for c in calls)


def blocks():
    return [dict(protocolHash="fixture", conditionId="fixture", environmentId="fixture", blockIndex=i,
                 throughput=dict(aether=float(np.exp(x)), mmap=1., raw=1.))
            for i, x in enumerate(np.linspace(-.01, .01, 24))]


def test_secondary_equivalence_does_not_imply_primary_superiority():
    report = analyze_blocks(blocks(), confirmatory=True, resamples=100)["groups"][0]
    assert report["aetherOverMmap"]["secondaryEquivalent"]
    assert not report["aetherOverMmap"]["primarySuperior"]
    assert not report["aetherOverRaw"]["secondarySuperior"]
    assert "holmP" not in report["aetherOverMmap"]
    expected = stats.ttest_1samp(np.linspace(-.01, .01, 24), 0, alternative="greater")
    assert report["aetherOverRaw"]["pairedTTestGreaterP"] == pytest.approx(expected.pvalue)


def test_primary_superiority_can_pass_when_equivalence_fails():
    records = blocks()
    for block in records:
        block["throughput"]["aether"] *= np.exp(.05)
    result = analyze_blocks(records, confirmatory=True, resamples=100)["groups"][0]["aetherOverMmap"]
    expected = stats.ttest_1samp(np.linspace(.04, .06, 24), 0, alternative="greater")
    assert result["primarySuperior"] and not result["secondaryEquivalent"]
    assert result["pairedTTestGreaterP"] == pytest.approx(expected.pvalue)
    assert result["ratioLowerOneSided95"] == pytest.approx(np.exp(expected.confidence_interval(.95).low))
    assert "primaryEquivalent" not in result


def test_old_equivalence_evidence_keeps_its_original_hypothesis():
    from confirmatory_v1 import DESIGN as legacy
    result = analyze_blocks(blocks(), confirmatory=True, confirmatory_design=legacy, resamples=100)["groups"][0]
    assert result["aetherOverMmap"]["primaryEquivalent"]
    assert "primarySuperior" not in result["aetherOverMmap"]


def test_manifest_hashes_are_bound_to_pilot_membership():
    plan = build_plan(parser().parse_args(["--confirmatory", "--epochs", "20", "--prefetch-depth", "0",
                                          "--config", PRIMARY_CONFIG, "--seed-base", str(SEED_BASE)]))
    spec = plan["conditions"][0]["specification"]
    plan["manifestSha256"] = {spec["manifest" + version]: sha for version, sha in MANIFEST_HASHES.items()}
    validate_plan(plan)
    plan["manifestSha256"][spec["manifestV1"]] = "different-membership"
    with pytest.raises(ValueError, match="manifests differ"):
        validate_plan(plan)


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
    report = {"configuration": dict(samples=1505, epochs=20, batchSize=16, prefetchBatches=0,
        workers=0, gpuCount=1, preprocessPasses=4, measuredSteps=0, warmupSteps=0, serverTrace=False,
        resize=256, modelTier="small", augmentationMode="light", normalizationScale=1., normalizationOffset=0.,
        pipelineVersion="paper-v1", oct5kTransformVersion="oct5k-v1", datasetKind="oct5k", datasetSplit="train",
        aetherCacheMode="reuse", mmapCacheMode="reuse", cacheDurability="durable", verifyManifestHashes=True),
        "runs": [{"engineInfo": {"integrityPolicy": {"version": "immutable-inline-admission-v1"},
                  "backgroundCompaction": {"enabled": True}},
                  "cacheDynamics": dict(prepopulatedEntries=1430, misses=75, recomputedSamples=75, publishedSamples=75),
                  "mmapDynamics": dict(initialReusableEntries=1430, initialMissingEntries=75, misses=75, entriesAppended=75),
                  "backends": {"fixture": {"epochWallMs": [1.] * 20,
                    "steps": [{"batchSize": 1505, "epoch": epoch} for epoch in range(20)]}}}]}
    validate_training(report)
    changed = copy.deepcopy(report)
    changed["runs"][0]["engineInfo"]["backgroundCompaction"]["enabled"] = False
    with pytest.raises(ValueError, match="compaction"):
        validate_training(changed)
    changed = copy.deepcopy(report)
    changed["runs"][0]["mmapDynamics"]["entriesAppended"] = 1505
    with pytest.raises(ValueError, match="cardinality"):
        validate_training(changed)
    changed = copy.deepcopy(report)
    changed["configuration"]["modelTier"] = "medium"
    with pytest.raises(ValueError, match="configuration"):
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
