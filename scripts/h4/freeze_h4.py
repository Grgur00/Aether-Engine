"""Freeze an independent confirmatory design only after a qualified H4 pilot."""
import argparse
from pathlib import Path

from .analyze_h4 import load_campaigns
from .common import write_json
from .confirmatory import make_design
from .h4_runner import archive_checksums, source_identity


def run(campaigns, prepared, protocol, output, *, sample_size, claim, null_ratio, alpha=.05):
    output = Path(output).resolve()
    if any(output == Path(path).resolve() or output.is_relative_to(Path(path).resolve()) for path in campaigns):
        raise ValueError("design freeze must not modify pilot evidence")
    loaded = load_campaigns(campaigns, prepared, protocol)
    if source_identity()["sha256"] != loaded["identity"]["source_sha256"]:
        raise ValueError("current implementation differs from qualified pilot source")
    design = make_design(loaded, sample_size=sample_size, claim=claim, null_ratio=null_ratio, alpha=alpha)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("design output must be empty; frozen evidence is immutable")
    write_json(output / "confirmatory-design.json", design)
    archive_checksums(output)
    return design


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", type=Path, action="append", required=True)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, default=Path(__file__).with_name("protocol.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sample-size", type=int, required=True)
    parser.add_argument("--claim", choices=("superiority", "noninferiority"), required=True)
    parser.add_argument("--null-ratio", type=float, required=True)
    parser.add_argument("--alpha", type=float, default=.05)
    args = parser.parse_args()
    run(args.campaign, args.prepared, args.protocol, args.output, sample_size=args.sample_size,
        claim=args.claim, null_ratio=args.null_ratio, alpha=args.alpha)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
