"""Freeze or verify checksums of a result/artifact directory without modifying data."""
import argparse
from pathlib import Path
from paper_common import sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    manifest = root / "SHA256SUMS"
    if args.verify:
        for line in manifest.read_text(encoding="utf-8").splitlines():
            expected, relative = line.split("  ", 1)
            path = (root / relative).resolve()
            if not path.is_relative_to(root) or sha256(path) != expected:
                raise ValueError(f"checksum mismatch: {relative}")
        print("All recorded checksums verified")
    else:
        lines = [f"{sha256(path)}  {path.relative_to(root).as_posix()}" for path in sorted(root.rglob("*"))
                 if path.is_file() and path != manifest and not path.is_symlink()]
        manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"Recorded {len(lines)} file checksums")


if __name__ == "__main__":
    main()
