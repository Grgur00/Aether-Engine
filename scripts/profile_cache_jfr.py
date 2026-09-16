"""Record isolated local cache workloads with JFR; these are diagnostic timings."""
import argparse
import json
import random
import shutil
import subprocess
import time
from pathlib import Path

from paper_common import environment, java_daemon, write_json, sha256
from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint

EVENTS = ",".join(("jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ObjectAllocationSample",
                   "jdk.CPULoad", "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.FileRead",
                   "jdk.FileWrite", "jdk.FileForce", "jdk.JavaMonitorEnter", "jdk.ThreadPark",
                   "jdk.SocketRead", "jdk.SocketWrite", "jdk.DataLoss"))


def run(output, seconds=30, baseline_seconds=15, warmup_seconds=5):
    if min(seconds, baseline_seconds, warmup_seconds) <= 0:
        raise ValueError("all durations must be positive")
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    jcmd, jfr = shutil.which("jcmd"), shutil.which("jfr")
    if not jcmd or not jfr:
        raise RuntimeError("jcmd and jfr from the Java runtime must be on PATH")
    configuration = output / "cache-profile.jfc"
    stock = Path(jfr).resolve().parents[1] / "lib/jfr/profile.jfc"
    def command(args, log):
        result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", check=True)
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
        return result.stdout
    command([jfr, "configure", "--input", str(stock), "--output", str(configuration),
             "jdk.ExecutionSample#period=2ms", "jdk.NativeMethodSample#period=2ms",
             "jdk.ObjectAllocationSample#throttle=300/s", "jdk.FileForce#threshold=0ms",
             "jdk.JavaMonitorEnter#threshold=1ms", "jdk.ThreadPark#threshold=5ms",
             "jdk.SocketRead#threshold=5ms", "jdk.SocketWrite#threshold=5ms"], output / "configure.log")
    reports = []
    cases = (("warm-batch-4k", 4096, 32), ("publish-batch-4k", 4096, 4), ("warm-single-1m", 1024 * 1024, 1))
    for case, payload_bytes, batch_size in cases:
        folder = output / case
        folder.mkdir()
        payload = random.Random(42).randbytes(payload_bytes)
        transform = TransformationFingerprint.from_descriptor("jfr-diagnostic-v1")
        warm_keys = [CacheKey("jfr", f"warm-{i}", transform) for i in range(batch_size)]
        expected = {key: payload for key in warm_keys}
        recording = folder / "recording.jfr"
        with java_daemon(folder / "store", jvm_options=("-Xms128m", "-Xmx512m")) as daemon:
            with AetherTrainingCache(port=daemon["port"]) as client:
                if not case.startswith("publish"):
                    client.put_many(list(expected.items()))
                sequence = 0
                def workload(duration):
                    nonlocal sequence
                    start = time.perf_counter()
                    calls = 0
                    while time.perf_counter() - start < duration:
                        if case.startswith("publish"):
                            keys = [CacheKey("jfr", f"p-{sequence}-{i}", transform) for i in range(batch_size)]
                            client.put_many([(key, payload) for key in keys])
                            sequence += 1
                        elif batch_size == 1:
                            if client.get(warm_keys[0]) != payload:
                                raise AssertionError("large read differs from published value")
                        elif client.get_many(warm_keys) != expected:
                            raise AssertionError("batch read differs from published values")
                        calls += 1
                    elapsed = time.perf_counter() - start
                    return {"requests": calls, "samples": calls * batch_size, "wallSeconds": elapsed,
                            "requestsPerSecond": calls / elapsed, "payloadBytesPerSecond": calls * batch_size * payload_bytes / elapsed}
                print(f"{case}: warming up", flush=True)
                workload(warmup_seconds)
                baseline = workload(baseline_seconds)
                command([jcmd, str(daemon["pid"]), "JFR.start", "name=AetherCache", f'settings="{configuration}"'], folder / "start.log")
                print(f"{case}: recording {seconds}s", flush=True)
                try:
                    measured = workload(seconds)
                finally:
                    command([jcmd, str(daemon["pid"]), "JFR.stop", "name=AetherCache", f'filename="{recording}"'], folder / "stop.log")
                if not recording.is_file() or recording.stat().st_size == 0:
                    raise RuntimeError("JFR produced no recording")
                reports.append({"case": case, "payloadBytes": payload_bytes, "batchSize": batch_size,
                                "daemonCommand": daemon["command"], "baseline": baseline, "recorded": measured,
                                "recording": recording.relative_to(output).as_posix(), "sha256": sha256(recording)})
        command([jfr, "summary", str(recording)], folder / "summary.txt")
        with (folder / "events.json").open("w", encoding="utf-8") as events:
            subprocess.run([jfr, "print", "--json", "--stack-depth", "64", "--events", EVENTS, str(recording)],
                           stdout=events, check=True, encoding="utf-8")
        print(f"{case}: saved {recording}", flush=True)
        write_json(output / "runs.json", {"schema": "aether-jfr-diagnostics-v1", "cases": reports,
                   "scope": "Windows/local when run here; sequential fresh-daemon workloads, durable publication, uncontrolled OS page cache; baseline precedes JFR and is not a randomized overhead estimate",
                   "durations": {"recordingSeconds": seconds, "baselineSeconds": baseline_seconds, "warmupSeconds": warmup_seconds},
                   "configurationSha256": sha256(configuration)})
    write_json(output / "environment.json", environment())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=float, default=30)
    parser.add_argument("--baseline-seconds", type=float, default=15)
    parser.add_argument("--warmup-seconds", type=float, default=5)
    args = parser.parse_args()
    run(args.output, args.seconds, args.baseline_seconds, args.warmup_seconds)
