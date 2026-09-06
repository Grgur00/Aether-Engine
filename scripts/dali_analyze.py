"""Analyze verified canonical-DALI paired reports separately from primary results."""
import argparse
import json
from pathlib import Path

from dali_comparison import validated_report
from evidence import digest
from analyze import analyze_blocks
from figures import generate
from paper_common import write_json


def load_dali_blocks(root):
    root = Path(root)
    protocol = json.loads((root / "protocol.json").read_text())
    if protocol.get("schema") != "aether-dali-protocol-v1":
        raise ValueError("not a canonical-DALI protocol")
    if protocol.get("measurementRole") != "secondary-canonical-DALI":
        raise ValueError("DALI fixture smoke cannot enter research analysis")
    blocks, seen = [], set()
    for path in sorted(root.glob("*/block-*.json")):
        if path.name.endswith(".receipt.json"):
            continue
        candidate = json.loads(path.read_text())
        env_id = candidate["environmentId"]
        if len(env_id) != 64 or any(c not in "0123456789abcdef" for c in env_id):
            raise ValueError("invalid DALI environment identifier")
        env = json.loads((root / f"environment-{env_id}.json").read_text())
        if digest(env["measurementIdentity"]) != env_id or env["sourceSha256"] != protocol["sourceSha256"] or env.get("accelerator", {}).get("backend") != "cuda":
            raise ValueError("DALI environment/source evidence differs from protocol")
        report = validated_report(path, protocol, env_id)
        condition = report["condition"]
        condition_id = digest(condition)[:16]
        identity = (condition_id, report["blockIndex"])
        if identity in seen:
            raise ValueError("duplicate DALI paired block")
        seen.add(identity)
        blocks.append({"protocolHash": report["protocolHash"], "environmentId": env_id,
            "conditionId": condition_id, "blockIndex": report["blockIndex"], "throughput": report["throughput"],
            "initialReusableEntries": report["initialReusableEntries"],
            "condition": {"dataset": condition["dataset_kind"] + " / DALI", "samples": condition["samples"],
                          "reusePercent": protocol["reusePercent"], "workers": 0, "preprocessPasses": 1, "gpuCount": 1}})
    if not blocks:
        raise ValueError("no validated DALI measurements")
    return blocks


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    blocks = load_dali_blocks(args.input)
    generate(blocks, args.output, raw_label="DALI direct")
    report = analyze_blocks(blocks)
    report.update(measurementRole="secondary canonical-DALI workload; not Pillow/OCT primary results",
                  rawBackend="DALI direct with GPU decode/resize/normalization and prefetch",
                  primaryEquivalent=False)
    write_json(args.output / "analysis.json", report)


if __name__ == "__main__":
    main()
