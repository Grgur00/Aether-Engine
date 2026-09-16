"""Frozen OCT5K confirmatory protocol; changes require a new campaign."""
import math
from collections import Counter

DESIGN = {
    "id": "oct5k-10epoch-equivalence-v1",
    "repetitions": 24,
    "epochs": 10,
    "batchSize": 16,
    "prefetchDepth": 0,
    "serverTrace": False,
    "asyncCompaction": True,
    "checksumPolicy": "immutable-inline-admission-v1",
    "samplesV1": 1170,
    "samplesV2": 1505,
    "reusable": 1003,
    "newOrChanged": 502,
    "alpha": 0.05,
    "equivalenceBounds": [0.97, 1.03],
    "primary": "paired log(Aether/mmap) TOST; 90% t CI strictly within 0.97–1.03",
    "endpoint": "steadyState.effectiveSamplesPerSecond over all 10 V2 training epochs",
    "timingScope": "V2 training wall including inline admission; excludes V1 population, mmap pre-materialization, reference validation, daemon startup and post-training compaction drain",
    "secondary": "paired log(Aether/raw) one-sided superiority at alpha=0.05; RAM descriptive only",
    "amortization": "cumulative epochs 1–10 from the same blocks; descriptive, no endpoint selection",
}


def validate_plan(plan):
    expected = dict(repeats=24, epochs=10, batchSize=16, prefetchDepth=0,
                    serverTrace=False, measuredSteps=0, workflowExperiments=1,
                    alpha=.05, equivalenceMargin=.03, confirmatory=True)
    if any(plan.get(key) != value for key, value in expected.items()):
        raise ValueError("confirmatory protocol requires exactly 24 blocks, 10 epochs, batch 16, prefetch 0, tracing off")
    if plan.get("confirmatoryDesign") != DESIGN or len(plan["conditions"]) != 1:
        raise ValueError("missing or changed frozen confirmatory design")
    point = plan["conditions"][0]
    spec = point["specification"]
    if (point["dataset"] != "oct5k" or point["samples"] != 1505 or point["reusePercent"] is not None
            or point["workers"] != 0 or point["preprocessPasses"] != 4 or point["gpuCount"] != 1
            or spec.get("samplesV1") != 1170 or spec.get("samplesV2") != 1505
            or spec.get("expectedReusable") != 1003 or spec.get("imageSize", 256) != 256
            or spec.get("split", "train") != "train"
            or set(plan["backends"]) != {"raw", "aether", "mmap", "ram"}):
        raise ValueError("confirmatory protocol requires the frozen OCT5K V1/V2 configuration without sweeps")


def validate_training(training):
    config = training["configuration"]
    expected = dict(samples=1505, epochs=10, batchSize=16, prefetchBatches=0,
                    workers=0, gpuCount=1, preprocessPasses=4, measuredSteps=0,
                    warmupSteps=0, serverTrace=False)
    if any(config.get(key) != value for key, value in expected.items()):
        raise ValueError("training report differs from the frozen confirmatory configuration")
    run = training["runs"][0]
    info = run["engineInfo"]
    if (info.get("integrityPolicy", {}).get("version") != DESIGN["checksumPolicy"]
            or info.get("backgroundCompaction", {}).get("enabled") is not True):
        raise ValueError("confirmatory checksum policy or async compaction mismatch")
    if run["cacheDynamics"]["prepopulatedEntries"] != 1003:
        raise ValueError("confirmatory reusable cardinality mismatch")
    for backend in run["backends"].values():
        walls = backend["epochWallMs"]
        if len(walls) != 10 or not all(math.isfinite(v) and v > 0 for v in walls):
            raise ValueError("confirmatory evidence requires ten complete epochs")
        counts = Counter()
        for step in backend["steps"]:
            counts[step["epoch"]] += step["batchSize"]
        if counts != Counter({epoch: 1505 for epoch in range(10)}):
            raise ValueError("confirmatory evidence requires all 1505 samples in every epoch")
