"""Exercise source and transformation evolution against actual persistent stores."""
import argparse
import copy
import hashlib
import tempfile
from pathlib import Path

import numpy as np
from paper_common import java_daemon, write_json, environment
from benchmark_gpu_segmentation import (BackendContext, parse_args, load_sources, preprocess_sample,
    artifact_to_tensor_sample, pack_payload, deterministic_parameters)
from aether_training_cache.java_store import JavaArtifactStore
from aether_training_cache.persistent_mmap import PersistentMmapStore


def run_once(output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    reports = []
    with tempfile.TemporaryDirectory(dir=output, prefix="evolution-") as temporary:
        root = Path(temporary)
        with java_daemon(root / "java") as daemon:
            args = parse_args(["--samples", "8", "--height", "8", "--width", "8", "--resize", "8"])
            sources = load_sources(args)
            def values(config, inputs):
                context = BackendContext.__new__(BackendContext)
                context.args, context.sources = config, inputs
                return {context.cache_key(index): pack_payload(artifact_to_tensor_sample(preprocess_sample(source, config, np), np))
                        for index, source in enumerate(inputs)}
            base = values(args, sources)
            variants = [("unchanged", args, sources, 0)]
            changed_source = copy.deepcopy(sources)
            changed_source[0]["raw"] = bytes([changed_source[0]["raw"][0] ^ 255]) + changed_source[0]["raw"][1:]
            changed_source[0]["source_hash"] = hashlib.sha256(changed_source[0]["raw"]).hexdigest()
            variants.append(("source-content", args, changed_source, 1))
            for name, field, value in [("normalize", "normalization_scale", 2.0),
                                       ("resize", "resize", 10), ("implementation-version", "pipeline_version", "paper-v2"),
                                       ("artifact-codec", "artifact_codec", "zlib")]:
                config = copy.copy(args)
                setattr(config, field, value)
                variants.append((name, config, sources, len(sources)))
            for name, config, inputs, expected_misses in variants:
                java = JavaArtifactStore(port=daemon["port"], namespace=f"evolution-{name}")
                mmap = PersistentMmapStore(root / f"mmap-{name}", durable=True)
                try:
                    java.commit_bytes_many([{"cache_key": key, "data": data} for key, data in base.items()])
                    for key, data in base.items():
                        mmap.put(key, data)
                    evolved = values(config, inputs)
                    cached = java.load_cached_bytes_many(evolved)
                    mmap_hits = {key: mmap.get(key)[4:] for key in evolved if mmap.contains(key)}
                    misses = len(evolved) - len(cached)
                    if misses != expected_misses or cached != mmap_hits:
                        raise AssertionError(f"incorrect invalidation in {name}")
                    for key, data in cached.items():
                        if data != evolved[key]:
                            raise AssertionError("stale reuse")
                    java.commit_bytes_many([{"cache_key": key, "data": value} for key, value in evolved.items() if key not in cached])
                    mmap.put_many([(key, value) for key, value in evolved.items() if key not in mmap_hits])
                    if {key: mmap.get(key)[4:] for key in evolved} != evolved:
                        raise AssertionError("evolved mmap artifacts mismatch")
                    if java.load_cached_bytes_many(evolved) != evolved:
                        raise AssertionError("evolved artifacts mismatch")
                    reports.append({"scenario": name, "hits": len(cached), "misses": misses,
                                    "expectedMisses": expected_misses, "passed": True,
                                    "parameters": deterministic_parameters(config)})
                finally:
                    java.close()
                    mmap.close()
    report = {"schema": "aether-transform-evolution-v1", "allPassed": True, "scenarios": reports,
              "reuseGranularity": "final artifact only; no stage-level reuse claim", "environment": environment()}
    write_json(output / "transform-evolution.json", report)
    print("Source/normalize/resize/version/codec evolution checks passed")
    return report


def run(output, repeats=1):
    output = Path(output)
    if repeats < 1:
        raise ValueError("positive repetition count required")
    output.mkdir(parents=True, exist_ok=True)
    if (output / "transform-evolution.json").exists():
        raise FileExistsError("use a new output directory for an evolution campaign")
    reports = []
    for repeat in range(repeats):
        destination = output / f"trial-{repeat:04d}"
        if destination.exists():
            raise FileExistsError(destination)
        report = run_once(destination)
        reports.append({"repeat": repeat, "report": f"trial-{repeat:04d}/transform-evolution.json",
                        "sha256": __import__("paper_common").sha256(destination / "transform-evolution.json")})
    summary = {"schema": "aether-transform-campaign-v1", "allPassed": True, "repeats": repeats, "trials": reports,
               "reuseGranularity": "final artifact only; no stage-level reuse claim"}
    write_json(output / "transform-evolution.json", summary)
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/transform-evolution")
    parser.add_argument("--repeats", type=int, default=1)
    args = parser.parse_args()
    run(args.output, args.repeats)
