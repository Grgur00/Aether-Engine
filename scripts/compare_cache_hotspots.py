"""Compare frozen cache builds on identical workloads with balanced run ordering."""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import random
import shutil
import statistics
import time
from unittest.mock import patch

from paper_common import java_classpath, java_daemon, java_runtime_record, write_json, ROOT
from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint


def snapshot(folder):
    folder.mkdir(parents=True, exist_ok=False)
    entries = []
    for index, entry in enumerate(java_classpath().split(os.pathsep)):
        source = Path(entry)
        target = folder / f"{index:02d}" / source.name
        target.parent.mkdir()
        if source.is_dir():
            shutil.copytree(source, target)
        elif source.is_file():
            shutil.copy2(source, target)
        entries.append(str(target))
    write_json(folder / "runtime.json", {"entries": entries,
               "runtime": {entry: java_runtime_record(Path(entry)) for entry in entries}})
    shutil.copy2(ROOT / "modules/aether-training-cache/build/paper-runtime-build.json", folder / "original-build.json")


def verified_classpath(folder):
    manifest = json.loads((folder / "runtime.json").read_text())
    if manifest["runtime"] != {entry: java_runtime_record(Path(entry)) for entry in manifest["entries"]}:
        raise ValueError(f"frozen runtime changed: {folder}")
    return os.pathsep.join(manifest["entries"])


def process_cpu_seconds(pid):
    if os.name != "nt":
        # Linux /proc fields 14 and 15 follow a parenthesized command name.
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = (wintypes.HANDLE,) + (ctypes.POINTER(wintypes.FILETIME),) * 4
    kernel.GetProcessTimes.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    handle = kernel.OpenProcess(0x1000, False, pid)  # Query limited information only.
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    times = [wintypes.FILETIME() for _ in range(4)]
    try:
        if not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
            raise ctypes.WinError(ctypes.get_last_error())
        return sum((t.dwHighDateTime << 32) + t.dwLowDateTime for t in times[2:]) / 1e7
    finally:
        kernel.CloseHandle(handle)


def run(folder, repeats, operations, warmup):
    folder = folder.resolve()
    if repeats < 2 or repeats % 2 or min(operations, warmup) < 1:
        raise ValueError("use positive counts and an even repeat count >= 2")
    snapshot(folder / "optimized")
    classpaths = {label: verified_classpath(folder / label) for label in ("baseline", "optimized")}
    rows = []
    transform = TransformationFingerprint.from_descriptor("jfr-diagnostic-v1")
    for case, size, batch in (("warm-batch-4k", 4096, 32), ("publish-batch-4k", 4096, 4), ("warm-single-1m", 1048576, 1)):
        payload = random.Random(42).randbytes(size)
        keys = [CacheKey("jfr", f"warm-{i}", transform) for i in range(batch)]
        expected = dict.fromkeys(keys, payload)
        for repeat in range(repeats):
            order = ("baseline", "optimized") if repeat % 2 == 0 else ("optimized", "baseline")
            for position, label in enumerate(order):
                print(f"{case} pair {repeat + 1}/{repeats}: {label}", flush=True)
                store = folder / "paired" / case / f"{repeat}-{label}" / "store"
                # Validate the archived runtime, then use the standard owned-daemon cleanup.
                assert verified_classpath(folder / label) == classpaths[label]
                with patch("paper_common.java_classpath", return_value=classpaths[label]), java_daemon(
                        store, jvm_options=("-Xms128m", "-Xmx512m")) as daemon:
                    with AetherTrainingCache(port=daemon["port"]) as client:
                        if not case.startswith("publish"):
                            client.put_many(list(expected.items()))
                        def invoke(index):
                            if case.startswith("publish"):
                                items = [(CacheKey("jfr", f"p-{index}-{i}", transform), payload) for i in range(batch)]
                                client.put_many(items)
                            elif batch == 1:
                                assert client.get(keys[0]) == payload
                            else:
                                assert client.get_many(keys) == expected
                        for index in range(-warmup, 0):
                            invoke(index)
                        cpu_start = process_cpu_seconds(daemon["pid"])
                        start = time.perf_counter()
                        for index in range(operations):
                            invoke(index)
                        elapsed = time.perf_counter() - start
                        cpu = process_cpu_seconds(daemon["pid"]) - cpu_start
                        rows.append({"case": case, "repeat": repeat, "orderIndex": position, "build": label,
                                     "operations": operations, "batchSize": batch, "payloadBytes": size,
                                     "wallSeconds": elapsed, "javaCpuSeconds": cpu,
                                     "requestsPerSecond": operations / elapsed,
                                     "javaCpuMicrosecondsPerRequest": cpu * 1e6 / operations})
                        if case.startswith("publish"):
                            for index in (0, operations - 1):
                                for i in range(batch):
                                    assert client.get(CacheKey("jfr", f"p-{index}-{i}", transform)) == payload
                write_json(folder / "comparison.json", {"rows": rows, "warmupRequests": warmup,
                           "scope": "fixed request counts; fresh daemon/store per observation; alternating paired order; no JFR; durable; Java user+kernel process CPU; uncontrolled OS page cache; local diagnostic, not GPU training"})
    summary = {}
    for case in sorted({r["case"] for r in rows}):
        pairs = [{r["build"]: r for r in rows if r["case"] == case and r["repeat"] == repeat} for repeat in range(repeats)]
        summary[case] = {}
        for metric in ("wallSeconds", "javaCpuSeconds"):
            ratios = [p["optimized"][metric] / p["baseline"][metric] for p in pairs]
            summary[case][metric] = {"pairedOptimizedBaselineRatios": ratios, "medianRatio": statistics.median(ratios)}
    write_json(folder / "comparison-summary.json", summary)
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, help="folder containing a frozen baseline/runtime.json")
    parser.add_argument("--repeats", type=int, default=4)
    parser.add_argument("--operations", type=int, default=3000)
    parser.add_argument("--warmup", type=int, default=1000)
    args = parser.parse_args()
    run(args.folder, args.repeats, args.operations, args.warmup)
