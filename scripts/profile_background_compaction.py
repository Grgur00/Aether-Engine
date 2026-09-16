"""Local storage diagnostic: foreground publications, background drain, then durable reopen parity.

This synthetic storage check is not an OCT5K training-throughput experiment.
"""
import argparse
import json
from pathlib import Path

from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint
from paper_common import java_daemon, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=1440)
    parser.add_argument("--payload-bytes", type=int, default=196849)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    transform = TransformationFingerprint.from_descriptor("background-compaction-diagnostic-v1")
    keys = [CacheKey("background-diagnostic", str(i), transform) for i in range(args.samples)]
    payload = bytes((i % 251 for i in range(args.payload_bytes)))
    traces = []
    with java_daemon(args.output / "store") as daemon:
        with AetherTrainingCache(port=daemon["port"], trace_sink=traces.append, server_trace=True) as client:
            for start in range(0, len(keys), 16):
                client.put_many([(key, payload) for key in keys[start:start + 16]])
    write_json(args.output / "requests.json", traces)
    drains = sorted(args.output.glob("store.compaction-*.json"))
    background = json.loads(drains[-1].read_text())["backgroundCompaction"]
    if background["completed"] < 1 or background["failed"]:
        raise RuntimeError("diagnostic did not complete a background compaction successfully")
    with java_daemon(args.output / "store") as daemon:
        with AetherTrainingCache(port=daemon["port"]) as client:
            for start in range(0, len(keys), 16):
                actual = client.get_many(keys[start:start + 16])
                if any(actual.get(key) != payload for key in keys[start:start + 16]):
                    raise RuntimeError("reopen payload parity failed")
    server = [event["server"] for request in traces for event in request["events"] if event["stage"] == "server_trace"]
    if any(record.get("compactions") for record in server):
        raise RuntimeError("compaction unexpectedly ran in a foreground trace")
    flushes = [flush for record in server for flush in record.get("flushes", [])]
    write_json(args.output / "summary.json", {"scope": "synthetic storage diagnostic; not training throughput",
        "samples": len(keys), "payloadBytes": len(payload), "reopenParityPassed": True,
        "foregroundCompactionCount": 0, "flushCount": len(flushes),
        "flushTotalMs": [f["totalNs"] / 1e6 for f in flushes], "backgroundCompaction": background})
    print(args.output / "summary.json")


if __name__ == "__main__":
    main()
