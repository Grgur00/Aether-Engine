#!/usr/bin/env python3
"""Validate Aether's staged Maven Central repository before signing or upload."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


GROUP = "io.github.grgur00"
MODULES = {
    "aether-api",
    "aether-bom",
    "aether-codec",
    "aether-codec-annotations",
    "aether-codec-processor",
    "aether-embedded-typed",
    "aether-engine",
    "aether-format",
    "aether-gradle-plugin",
    "aether-io",
    "aether-lsm",
    "aether-memory",
    "aether-memtable",
    "aether-sstable",
    "aether-wal",
}
PLUGIN_ID = "io.github.grgur00.aether"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def text(root: ET.Element, path: str) -> str:
    value = root.findtext(path, namespaces=NS)
    return value.strip() if value else ""


def validate_module(repository: Path, module: str, version: str) -> list[str]:
    directory = repository / GROUP.replace(".", "/") / module / version
    prefix = directory / f"{module}-{version}"
    pom = Path(f"{prefix}.pom")
    errors: list[str] = []

    expected = [pom, Path(f"{prefix}.module")]
    if module != "aether-bom":
        expected.extend(
            [
                Path(f"{prefix}.jar"),
                directory / f"{module}-{version}-sources.jar",
                directory / f"{module}-{version}-javadoc.jar",
            ]
        )
    for artifact in expected:
        if not artifact.is_file() or artifact.stat().st_size == 0:
            errors.append(f"{module}: missing or empty {artifact.name}")

    if not pom.is_file():
        return errors

    root = ET.parse(pom).getroot()
    required = {
        "groupId": "m:groupId",
        "artifactId": "m:artifactId",
        "version": "m:version",
        "name": "m:name",
        "description": "m:description",
        "url": "m:url",
        "license": "m:licenses/m:license/m:name",
        "developer": "m:developers/m:developer/m:name",
        "SCM connection": "m:scm/m:connection",
        "SCM URL": "m:scm/m:url",
    }
    for label, path in required.items():
        if not text(root, path):
            errors.append(f"{module}: missing POM {label}")

    if text(root, "m:groupId") != GROUP:
        errors.append(f"{module}: unexpected POM groupId")
    if text(root, "m:artifactId") != module:
        errors.append(f"{module}: unexpected POM artifactId")
    if text(root, "m:version") != version:
        errors.append(f"{module}: unexpected POM version")
    if module == "aether-bom" and text(root, "m:packaging") != "pom":
        errors.append("aether-bom: packaging must be pom")

    for dependency in root.findall(".//m:dependency", NS):
        dependency_group = text(dependency, "m:groupId")
        dependency_name = text(dependency, "m:artifactId")
        if dependency_group == GROUP and dependency_name not in MODULES:
            errors.append(
                f"{module}: unpublished internal dependency {dependency_name}"
            )
    return errors


def validate_plugin_marker(repository: Path, version: str) -> list[str]:
    artifact = f"{PLUGIN_ID}.gradle.plugin"
    pom = (
        repository
        / PLUGIN_ID.replace(".", "/")
        / artifact
        / version
        / f"{artifact}-{version}.pom"
    )
    if not pom.is_file() or pom.stat().st_size == 0:
        return [f"Gradle plugin marker: missing or empty {pom.name}"]

    root = ET.parse(pom).getroot()
    errors: list[str] = []
    if text(root, "m:packaging") != "pom":
        errors.append("Gradle plugin marker: packaging must be pom")
    if text(root, "m:licenses/m:license/m:name") == "":
        errors.append("Gradle plugin marker: missing POM license")
    dependency = root.find("m:dependencies/m:dependency", NS)
    if dependency is None:
        errors.append("Gradle plugin marker: implementation dependency is missing")
    elif (
        text(dependency, "m:groupId") != GROUP
        or text(dependency, "m:artifactId") != "aether-gradle-plugin"
        or text(dependency, "m:version") != version
    ):
        errors.append("Gradle plugin marker: implementation coordinates are invalid")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument(
        "--repository",
        type=Path,
        default=Path("build/staging-deploy"),
    )
    arguments = parser.parse_args()

    errors = [
        error
        for module in sorted(MODULES)
        for error in validate_module(
            arguments.repository,
            module,
            arguments.version,
        )
    ]
    errors.extend(validate_plugin_marker(arguments.repository, arguments.version))
    if errors:
        print("Maven publication validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        f"Maven publication check passed: {len(MODULES) + 1} coordinates, "
        f"version {arguments.version}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
