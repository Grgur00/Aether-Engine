"""Adapt Kaggle Studio 1.2.4's POSIX-quoted arguments on Windows.

Use only as that extension's cliPath, not as a general command-line entry point.
The project VS Code tasks already pass argument arrays directly.
"""
import os
import shlex
import subprocess
import sys
from pathlib import Path


def decode_arguments(arguments):
    # Studio quotes every argument except its --version availability check.
    # cmd.exe leaves apostrophes literal and splits quoted paths at spaces.
    if arguments and arguments[0].startswith("'"):
        return shlex.split(" ".join(arguments), posix=True)
    return arguments


def main():
    cli = Path(__file__).resolve().parents[1] / "build/kaggle-venv/Scripts/kaggle.exe"
    arguments = decode_arguments(sys.argv[1:]) if os.name == "nt" else sys.argv[1:]
    return subprocess.run([str(cli), *arguments], shell=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
