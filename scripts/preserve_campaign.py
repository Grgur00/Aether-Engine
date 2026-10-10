"""Verify and preserve a completed campaign with its exact packaged source."""
import argparse
import hashlib
import json
import shutil
import tempfile
import zipfile
from pathlib import Path

from evidence import validate_block
from paper_common import sha256, write_json


def preserve(results, source, output, receipt):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(source) as package:
        provenance_bytes = package.read("artifact-provenance.json")
        provenance = json.loads(provenance_bytes)
        for name, digest in provenance["files"].items():
            if hashlib.sha256(package.read(name)).hexdigest() != digest:
                raise ValueError(f"source checksum mismatch: {name}")
    manifest_hash = hashlib.sha256(provenance_bytes).hexdigest()
    with tempfile.TemporaryDirectory(prefix="verify-", dir=output) as temporary:
        root = Path(temporary).resolve()
        with zipfile.ZipFile(results) as archive:
            sums = {line.split("  ", 1)[1]: line.split("  ", 1)[0]
                    for line in archive.read("SHA256SUMS").decode().splitlines()}
            if len(archive.namelist()) != len(set(archive.namelist())) or set(archive.namelist()) != set(sums) | {"SHA256SUMS"}:
                raise ValueError("result file inventory mismatch")
            for name, digest in sums.items():
                if not (root / name).resolve().is_relative_to(root):
                    raise ValueError("unsafe result path")
                if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                    raise ValueError(f"result checksum mismatch: {name}")
            archive.extractall(root)
        status = json.loads((root / "run-status.json").read_text())
        if status["status"] != "passed" or status["config"]["sourceManifestSha256"] != manifest_hash:
            raise ValueError("results are failed or from a different source snapshot")
        protocol = json.loads((root / "protocol.json").read_text())
        blocks = [validate_block(p) for p in sorted(root.glob("*/block-*/block.json"))]
        for condition in protocol["conditions"]:
            subset = [b for b in blocks if b["conditionId"] == condition["conditionId"]]
            if sorted(b["blockIndex"] for b in subset) != list(range(protocol["repeats"])):
                raise ValueError("incomplete or duplicated paired block indices")
        for path in root.glob("environment-*.json"):
            env = json.loads(path.read_text())
            if env["archiveProvenance"]["manifestSha256"] != manifest_hash or not env["archiveProvenance"]["verified"]:
                raise ValueError("environment source provenance differs")
        analysis = json.loads((root / "processed/analysis.json").read_text())
        summary = [{key: group[key] for key in ("protocolHash", "conditionId", "environmentId", "aetherOverMmap", "aetherOverRaw")}
                   for group in analysis["groups"]]
        for name in ("protocol.json", "processed/analysis.json", "run-status.json"):
            target = output / Path(name).name
            shutil.copy2(root / name, target)
    shutil.copy2(results, output / "results.zip")
    shutil.copy2(source, output / "source.zip")
    record = {"schema": "aether-preserved-campaign-v1", "resultsArchiveSha256": sha256(results),
              "sourceArchiveSha256": sha256(source), "sourceManifestSha256": manifest_hash,
              "sourceCommit": provenance["gitCommit"]["stdout"], "sourceClean": provenance["sourceClean"],
              "confirmatory": protocol["confirmatory"], "validatedBlocks": len(blocks),
              "resultsFilesVerified": len(sums), "localPreservationDirectory": str(output), "analysis": summary}
    write_json(receipt, record)
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("results", "source", "output", "receipt"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    preserve(args.results, args.source, args.output, args.receipt)
