"""Frozen append-only OCT5K superiority protocol; never tune after collection."""
import math
from collections import Counter

import confirmatory_v1

PRIMARY_CONFIG = "configs/paper/oct5k-confirmatory-v2.json"
SEED_BASE = 20260924
MANIFEST_HASHES = {
    "V1": "ca84f70fac9c253c612fd09b69fb6640e1788131f58470a44daecbe7f8d00979",
    "V2": "892141e67b567f3443f4397a55e4a95b6d3fde53e8814baad4dbfd8bb1d09dc5",
}
DESIGN = {
    "id": "oct5k-20epoch-append75-superiority-v2",
    "repetitions": 24, "epochs": 20, "batchSize": 16, "prefetchDepth": 0,
    "serverTrace": False, "asyncCompaction": True,
    "checksumPolicy": "immutable-inline-admission-v1",
    "samplesV1": 1430, "samplesV2": 1505, "reusable": 1430, "newOrChanged": 75,
    "removed": 0, "update": "append-only", "seedBase": SEED_BASE,
    "backendOrder": "seeded random permutation within each paired block",
    "modelTier": "small", "augmentationMode": "light", "pipelineVersion": "paper-v1",
    "manifestSha256": MANIFEST_HASHES,
    "alpha": .05, "equivalenceBounds": [.97, 1.03],
    "primary": "paired log(Aether/incremental mmap) one-sided t-test; H0 mean <= 0, H1 mean > 0; p < 0.05",
    "endpoint": "steadyState.effectiveSamplesPerSecond over all 20 V2 training epochs",
    "timingScope": "V2 training wall including inline admission for both Aether and incremental mmap; excludes V1 population, reference/checksum validation, daemon startup and post-training compaction drain",
    "secondary": "Aether/mmap +/-3% TOST and Aether/raw one-sided superiority, nominal alpha=0.05 exploratory secondary; RAM descriptive; no multiplicity adjustment to the sole primary test",
    "amortization": "cumulative epochs 1-20; report 1,5,10,20 descriptively; no endpoint selection",
    "stopping": "exactly 24 completed fresh paired blocks; no interim inference or sample-size adaptation",
}


def known_design(design):
    if design not in (DESIGN, confirmatory_v1.DESIGN):
        raise ValueError("missing or changed frozen confirmatory design")
    return design


def validate_manifest_hashes(spec, hashes):
    if any(hashes.get(spec["manifest" + version]) != expected for version, expected in MANIFEST_HASHES.items()):
        raise ValueError("confirmatory manifests differ from the frozen 75-new-sample pilot membership")


def validate_plan(plan):
    design = known_design(plan.get("confirmatoryDesign"))
    if design == confirmatory_v1.DESIGN:
        return confirmatory_v1.validate_plan(plan)
    expected = dict(repeats=24, epochs=20, batchSize=16, prefetchDepth=0,
                    serverTrace=False, measuredSteps=0, workflowExperiments=1,
                    alpha=.05, equivalenceMargin=.03, confirmatory=True, seedBase=SEED_BASE,
                    backendOrder=DESIGN["backendOrder"])
    if any(plan.get(key) != value for key, value in expected.items()) or len(plan["conditions"]) != 1:
        raise ValueError("confirmatory protocol requires exactly 24 blocks, 20 epochs, batch 16, prefetch 0, tracing off and fresh frozen seeds")
    point = plan["conditions"][0]
    spec = point["specification"]
    if (point["dataset"] != "oct5k" or point["samples"] != 1505 or point["reusePercent"] is not None
            or point["workers"] != 0 or point["preprocessPasses"] != 4 or point["gpuCount"] != 1
            or spec.get("samplesV1") != 1430 or spec.get("samplesV2") != 1505
            or spec.get("expectedReusable") != 1430 or spec.get("imageSize", 256) != 256
            or spec.get("split", "train") != "train"
            or set(plan["backends"]) != {"raw", "aether", "mmap", "ram"}):
        raise ValueError("confirmatory protocol requires the frozen append-only OCT5K configuration without sweeps")
    if "manifestSha256" in plan:
        validate_manifest_hashes(spec, plan["manifestSha256"])


def validate_training(training, design=DESIGN):
    known_design(design)
    if design == confirmatory_v1.DESIGN:
        return confirmatory_v1.validate_training(training)
    config = training["configuration"]
    expected = dict(samples=1505, epochs=20, batchSize=16, prefetchBatches=0,
                    workers=0, gpuCount=1, preprocessPasses=4, measuredSteps=0,
                    warmupSteps=0, serverTrace=False, resize=256, modelTier="small",
                    augmentationMode="light", normalizationScale=1., normalizationOffset=0.,
                    pipelineVersion="paper-v1", oct5kTransformVersion="oct5k-v1",
                    datasetKind="oct5k", datasetSplit="train", aetherCacheMode="reuse",
                    mmapCacheMode="reuse", cacheDurability="durable", verifyManifestHashes=True)
    if any(config.get(key) != value for key, value in expected.items()):
        raise ValueError("training report differs from the frozen confirmatory configuration")
    run = training["runs"][0]
    info = run["engineInfo"]
    if (info.get("integrityPolicy", {}).get("version") != DESIGN["checksumPolicy"]
            or info.get("backgroundCompaction", {}).get("enabled") is not True):
        raise ValueError("confirmatory checksum policy or async compaction mismatch")
    aether, mmap = run["cacheDynamics"], run["mmapDynamics"]
    if (aether["prepopulatedEntries"] != 1430 or aether["misses"] != 75
            or aether["recomputedSamples"] != 75 or aether["publishedSamples"] != 75
            or mmap["initialReusableEntries"] != 1430 or mmap["initialMissingEntries"] != 75
            or mmap["misses"] != 75 or mmap["entriesAppended"] != 75):
        raise ValueError("confirmatory incremental reuse/admission cardinality mismatch")
    for backend in run["backends"].values():
        walls = backend["epochWallMs"]
        if len(walls) != 20 or not all(math.isfinite(v) and v > 0 for v in walls):
            raise ValueError("confirmatory evidence requires twenty complete epochs")
        counts = Counter()
        for step in backend["steps"]:
            counts[step["epoch"]] += step["batchSize"]
        if counts != Counter({epoch: 1505 for epoch in range(20)}):
            raise ValueError("confirmatory evidence requires all 1505 samples in every epoch")
