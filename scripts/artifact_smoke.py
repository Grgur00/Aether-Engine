"""Small CPU correctness campaign against the real Java engine; not paper timings."""
import argparse
import tempfile
from pathlib import Path

from paper_common import environment, java_daemon, write_json


def smoke(output, workers=(0, 2)):
    import numpy as np
    from benchmark_gpu_segmentation import (BackendContext, BACKENDS, artifact_to_tensor_sample,
        batch_checksums, cache_dynamics, effective_measured_steps, load_sources,
        parse_args, preprocess_sample, scheduled_batches)
    from aether_training_cache.loader_workers import worker_batches
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    cases = []
    with tempfile.TemporaryDirectory(prefix="artifact-smoke-", dir=output) as temporary:
        with java_daemon(Path(temporary) / "java") as daemon:
            for worker_count in workers:
                args = parse_args(["--samples", "12", "--height", "8", "--width", "8",
                    "--resize", "8", "--batch-size", "4", "--epochs", "2", "--workers", str(worker_count),
                    "--aether-engine", "java", "--cache-durability", "durable", "--aether-port", str(daemon["port"]),
                    "--aether-namespace", f"smoke-w{worker_count}", "--initial-cache-hit-ratio", "50"])
                sources = load_sources(args)
                reference = [artifact_to_tensor_sample(preprocess_sample(s, args, np), np) for s in sources]
                context = BackendContext(args, reference, sources, np)
                try:
                    schedule = scheduled_batches(args, effective_measured_steps(args))
                    expected = [batch_checksums(context.batch("RAW_RECOMPUTE", indices)[0]) for indices, _ in schedule]
                    for backend in BACKENDS:
                        schedule = scheduled_batches(args, effective_measured_steps(args))
                        if worker_count:
                            actual = [batch_checksums(prepared[0]) for _, _, prepared, _ in worker_batches(context, backend, schedule)]
                        else:
                            actual = [batch_checksums(context.batch(backend, indices)[0]) for indices, _ in schedule]
                        if actual != expected:
                            raise AssertionError(f"tensor mismatch: {backend}, workers={worker_count}")
                    aether = cache_dynamics(context.protocol_counters(), args)
                    mmap = context.mmap_dynamics()
                    if not aether["invariants"]["passed"] or not mmap["invariants"]["passed"]:
                        raise AssertionError(f"cache accounting mismatch: {aether}, {mmap}")
                    cases.append({"workers": worker_count, "tensorParity": True,
                                  "aetherDynamics": aether, "mmapDynamics": mmap})
                    print(f"Java/mmap/RAW/RAM parity and accounting passed: workers={worker_count}", flush=True)
                finally:
                    context.close()
            # Execute actual optimization on CPU to validate the same training
            # function used by the GPU runner. These timings never enter block.json.
            import torch
            from benchmark_gpu_segmentation import run_training_once
            torch.set_num_threads(1)
            args = parse_args(["--samples", "4", "--height", "8", "--width", "8", "--resize", "8",
                "--batch-size", "2", "--epochs", "1", "--warmup-steps", "0", "--gpu-sample-interval-ms", "0",
                "--aether-engine", "java", "--cache-durability", "durable", "--aether-port", str(daemon["port"]), "--aether-namespace", "smoke-training"])
            cpu_training = run_training_once(args, torch, np, torch.device("cpu"), 0)
            write_json(output / "cpu-training-correctness.json", {"measurementRole": "CPU correctness only", "run": cpu_training})
            print("CPU optimizer/final-model parity passed through all four backends", flush=True)
            # Exercise classification and the bounded-reference, three-backend path.
            import json
            from PIL import Image
            from prepare_vision import prepare
            fixtures = Path(temporary) / "vision-fixtures"
            for label in ("a", "b"):
                (fixtures / label).mkdir(parents=True)
                for index in range(2):
                    Image.new("RGB", (9 + index, 13), (index * 70, 77, 160 if label == "a" else 20)).save(fixtures / label / f"{index}.png")
            for kind in ("imagenet", "coco"):
                manifest = Path(temporary) / f"{kind}.csv"
                annotations = None
                if kind == "coco":
                    annotations = Path(temporary) / "instances.json"
                    annotations.write_text(json.dumps({"images": [{"id": i, "file_name": p.relative_to(fixtures).as_posix()}
                        for i, p in enumerate(sorted(fixtures.glob("*/*.png")))],
                        "categories": [{"id": 1, "name": "a"}, {"id": 5, "name": "b"}],
                        "annotations": [{"image_id": i, "category_id": 1 if i < 2 else 5} for i in range(4)]}))
                prepare(kind, fixtures, manifest, annotations)
                vision_args = parse_args(["--dataset-kind", kind, "--dataset-manifest", str(manifest),
                    "--num-classes", "2", "--samples", "4", "--resize", "8", "--batch-size", "2",
                    "--epochs", "1", "--warmup-steps", "0", "--gpu-sample-interval-ms", "0",
                    "--aether-engine", "java", "--cache-durability", "durable", "--aether-port", str(daemon["port"]),
                    "--aether-namespace", f"smoke-{kind}", "--backends", "raw,aether,mmap"])
                if kind == "coco":
                    vision_args.artifact_codec = "zlib"
                result = run_training_once(vision_args, torch, np, torch.device("cpu"), 0)
                write_json(output / f"cpu-{kind}-correctness.json", {"measurementRole": "CPU correctness with generated tiny RGB fixtures; not dataset evaluation", "run": result})
                print(f"CPU {kind} optimization and bounded-reference parity passed", flush=True)
    report = {"schema": "aether-artifact-smoke-v1", "measurementRole": "CPU correctness only",
              "allPassed": True, "cases": cases, "environment": environment()}
    write_json(output / "smoke.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/artifact-smoke")
    parser.add_argument("--workers", default="0,2")
    args = parser.parse_args()
    smoke(args.output, tuple(int(value) for value in args.workers.split(",")))


if __name__ == "__main__":
    main()
