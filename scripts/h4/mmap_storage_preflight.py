"""Independent mmap persistence diagnostic; not a controlled H4 performance run."""
import argparse
import hashlib
import math
import shutil
import tempfile
import time
from pathlib import Path

import numpy as np

from .common import digest, write_json
from .mmap_backend import MmapBackend
from .prepare_pcam_manifest import PAYLOAD_BYTES
from .storage_preflight import DiskPeak, synthetic_sample


def run(output, scratch, samples):
    output, scratch = Path(output).resolve(), Path(scratch).resolve(strict=True)
    if type(samples) is not int or samples <= 0 or not scratch.is_dir():
        raise ValueError("positive sample count and existing scratch directory required")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("preflight output must be empty")
    logical_bytes = samples * PAYLOAD_BYTES
    report = dict(schema="aether-h4-mmap-size-preflight-v1", passed=False,
                  dataset="seeded synthetic unique float32 tensors; not PCam",
                  samples=samples, logical_payload_bytes=logical_bytes,
                  primary_h4_eligible=False, out_of_core_verified=False,
                  timing_role="persistence diagnostic; not primary H4 retrieval timing")
    rows = []
    try:
        if shutil.disk_usage(scratch).free < logical_bytes * 2 + 2 * 1024 ** 3:
            raise ValueError("insufficient safe disk capacity")
        with tempfile.TemporaryDirectory(prefix="aether-h4-mmap-", dir=scratch) as temporary:
            store = Path(temporary) / "store"
            peak = DiskPeak(store)
            peak.start()
            try:
                def stream():
                    for index in range(samples):
                        row, payload = synthetic_sample(index)
                        rows.append(row)
                        yield row, payload

                with MmapBackend(store) as backend:
                    started = time.perf_counter()
                    report["materialization"] = backend.append(stream())
                    report["materialization_seconds_including_synthetic_generation"] = time.perf_counter() - started
                    report["preclose_validation"] = backend.validate(rows)
                with MmapBackend(store) as backend:
                    report["reopen_validation"] = backend.validate(rows)
                    order = np.random.Generator(np.random.PCG64(20261012)).permutation(samples)
                    consumer = hashlib.sha256()
                    started = time.perf_counter()
                    for start in range(0, samples, 16):
                        with backend.get_many(int(index) for index in order[start:start + 16]) as views:
                            for view in views:
                                consumer.update(view)
                    report["shuffled_read"] = dict(seconds=time.perf_counter() - started,
                                                   logical_bytes_consumed=logical_bytes,
                                                   consumer_sha256=consumer.hexdigest(), order_seed=20261012)
                    report["postread_validation"] = backend.validate(rows)
                    report["final_metrics"] = backend.metrics()
            finally:
                report["disk_peak"] = peak.stop()
        report.update(passed=True, scratch_cleanup_completed=True)
    except (OSError, ValueError, RuntimeError) as error:
        report.update(error_type=type(error).__name__, error=str(error))
    finally:
        write_json(output / "preflight.json", report)
        write_json(output / "sample_hashes.json", rows)
        write_json(output / "sha256.json", {path.name: digest(path) for path in output.iterdir()
                                            if path.is_file() and path.name != "sha256.json"})
    return report["passed"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--gib", type=int, choices=(1, 2, 4, 8), default=1)
    parser.add_argument("--samples", type=int, help="small harness smoke only")
    args = parser.parse_args()
    samples = args.samples if args.samples is not None else math.ceil(args.gib * 1024 ** 3 / PAYLOAD_BYTES)
    passed = run(args.output, args.scratch, samples)
    print("H4 mmap persistence preflight passed=" + str(passed), flush=True)
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
