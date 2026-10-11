"""Validate H4 raw evidence; never promote diagnostic records to primary results."""
import argparse
import json
import math
from pathlib import Path

from .memory_controller import assess_read_window, verify_enforcement
from .prepare_pcam_manifest import PAYLOAD_BYTES
from .retrieval import quantile


def positive(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
        raise ValueError("finite positive measurement required")
    return value


def sha256(value):
    if not isinstance(value, str) or len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise ValueError("invalid SHA256")
    return value


def validate_retrieval(record, rows, order, pattern):
    from .retrieval import validate_order
    validate_order(rows, order)
    count = len(rows)
    if (record["schema"] != "aether-h4-retrieval-v1" or record["access_pattern"] != pattern
            or record["samples"] != count or record["payload_bytes"] != count * PAYLOAD_BYTES
            or record["lookup_batch_size"] != 16 or record["warmup_passes"] != 1
            or record["correctness_passed"] is not True):
        raise ValueError("retrieval workload/schema/correctness mismatch")
    reference = record["verified_reference"]
    reference_hash = sha256(reference["consumer_sha256"])
    if reference["logical_bytes_consumed"] != count * PAYLOAD_BYTES or reference["artifacts_consumed"] != count:
        raise ValueError("reference did not consume the full workload")
    if record["warmup"] != reference or record["postread_validation"] != reference:
        raise ValueError("warm-up/post-validation content differs")
    passes = record["measured_passes"]
    if len(passes) != 3 or [item["index"] for item in passes] != [0, 1, 2]:
        raise ValueError("three chronological measured passes required")
    qualified = []
    for item in passes:
        seconds = positive(item["seconds"])
        if any(item[key] != reference[key] for key in reference):
            raise ValueError("measured content differs from verified reference")
        if not math.isclose(positive(item["artifacts_per_second"]), count / seconds, rel_tol=1e-12):
            raise ValueError("throughput does not match pass timing")
        if not math.isclose(positive(item["logical_mib_per_second"]), count * PAYLOAD_BYTES / seconds / 1024 ** 2, rel_tol=1e-12):
            raise ValueError("logical throughput does not match pass timing")
        before, after = item["cgroup_before"], item["cgroup_after"]
        if before is None or after is None:
            verdict = False
        else:
            verify_enforcement(before, limit=record["memory_limit_bytes"], pids=record["workload_pids"])
            verify_enforcement(after, limit=record["memory_limit_bytes"], pids=record["workload_pids"])
            resource = assess_read_window(before, after, limit=record["memory_limit_bytes"],
                                         payload_bytes=count * PAYLOAD_BYTES,
                                         consumed_bytes=item["logical_bytes_consumed"], pids=record["workload_pids"])
            if item["resource"] != resource:
                raise ValueError("resource verdict differs from archived raw counters")
            verdict = resource["out_of_core_resource_evidence_verified"]
        if item["resource"]["out_of_core_resource_evidence_verified"] != verdict:
            raise ValueError("unsupported out-of-core verdict")
        qualified.append(verdict)
    expected_mean = sum(item["artifacts_per_second"] for item in passes) / 3
    if not math.isclose(record["block_mean_artifacts_per_second"], expected_mean, rel_tol=1e-12):
        raise ValueError("block throughput mean differs from pass records")
    if record["out_of_core_resource_evidence_verified"] != all(qualified) or record["primary_h4_eligible"] is not False:
        raise ValueError("retrieval evidence alone cannot establish primary eligibility")
    latency = record["latency"]
    durations = latency["sample_seconds"]
    n = len(durations)
    if not 0 < n <= min(128, count) or latency["unit"] != "seconds":
        raise ValueError("invalid latency sampling policy")
    if latency["sample_ids"] != [order[index * count // n] for index in range(n)]:
        raise ValueError("latency sample IDs differ from frozen evenly-spaced policy")
    for duration in durations:
        positive(duration)
    for name, fraction in (("p50", .5), ("p95", .95), ("p99", .99)):
        if not math.isclose(latency[name], quantile(durations, fraction), rel_tol=1e-12):
            raise ValueError("latency quantile differs from archived samples")
    sha256(latency["consumer_sha256"])
    return dict(valid=True, samples=count, consumer_sha256=reference_hash,
                out_of_core_resource_evidence_verified=all(qualified), primary_h4_eligible=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--sample-hashes", type=Path, required=True)
    parser.add_argument("--condition", type=int, required=True)
    parser.add_argument("--pattern", choices=("sequential", "shuffled"), required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    condition = manifest["conditions"][args.condition]
    membership = set(condition["sequential_ids"])
    rows = [row for row in json.loads(args.sample_hashes.read_text()) if row["sample_id"] in membership]
    result = validate_retrieval(json.loads(args.record.read_text()), rows,
                                condition[args.pattern + "_ids"], args.pattern)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
