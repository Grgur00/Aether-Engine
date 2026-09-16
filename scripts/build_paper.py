"""Compile the draft and supplement with Tectonic; preserve logs and source hashes."""
import argparse
import os
import shutil
import subprocess
from pathlib import Path

from paper_common import ROOT, sha256, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tectonic", help="Tectonic executable, otherwise use PATH or workspace toolchain")
    parser.add_argument("--offline", action="store_true", help="Use only the previously populated TeX cache")
    parser.add_argument("--render", action="store_true", help="Render each PDF page for inspection (requires PyMuPDF)")
    parser.add_argument("--output", type=Path, default=ROOT / "build/paper-pdf")
    args = parser.parse_args()
    local = ROOT / "build/tex-toolchain" / ("tectonic.exe" if os.name == "nt" else "tectonic")
    executable = args.tectonic or shutil.which("tectonic") or (str(local) if local.is_file() else None)
    if not executable:
        raise RuntimeError("Install Tectonic 0.17.0 or pass --tectonic PATH; see paper/README.md")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    child_env = os.environ.copy()
    child_env["TECTONIC_CACHE_DIR"] = str(ROOT / "build/tex-cache")
    version = subprocess.run([executable, "--version"], capture_output=True, text=True, check=True).stdout.strip()
    reports = []
    for name in ("manuscript", "supplement"):
        destination = output / name
        destination.mkdir(exist_ok=True)
        command = [executable, "--untrusted", "--keep-logs", "--keep-intermediates", "--outdir", str(destination)]
        if args.offline:
            command.append("--only-cached")
        command.append(str(ROOT / "paper" / (name + ".tex")))
        with (destination / "command.log").open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=ROOT / "paper", env=child_env, stdout=log, stderr=subprocess.STDOUT, check=True)
        pdf = destination / (name + ".pdf")
        if not pdf.is_file() or not pdf.read_bytes().startswith(b"%PDF"):
            raise RuntimeError(f"compiler did not produce a PDF: {pdf}")
        log = (destination / (name + ".log")).read_text(encoding="utf-8", errors="replace")
        warnings = [line.strip() for line in log.splitlines() if any(marker in line for marker in (
            "Overfull", "undefined", "LaTeX Warning", "Package longtable Warning"))]
        reports.append({"document": name, "pdf": str(pdf), "sha256": sha256(pdf), "warnings": warnings})
        if args.render:
            import pymupdf
            with pymupdf.open(pdf) as document:
                reports[-1]["pages"] = len(document)
                for index, page in enumerate(document):
                    page.get_pixmap(dpi=110).save(destination / f"page-{index + 1}.png")
                (destination / "extracted-text.txt").write_text("\n".join(page.get_text() for page in document), encoding="utf-8")
        print(f"Compiled {name}: {pdf}", flush=True)
    write_json(output / "build.json", {"schema": "aether-paper-build-v1", "compiler": version,
        "offline": args.offline, "sources": {path.name: sha256(path) for path in sorted((ROOT / "paper").glob("*"))
                                               if path.suffix in {".tex", ".bib"}},
        "documents": reports, "submissionReady": False,
        "scope": "Draft compilation; does not certify measured claims, final page count or author approval"})


if __name__ == "__main__":
    main()
