#!/usr/bin/env python3
"""Checks that the static developer documentation matches the public Java surface."""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
import re
import sys
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
WEBSITE = ROOT / "website"
DOCS = WEBSITE / "docs" / "index.html"


class PageAudit(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: set[str] = set()
        self.references: list[str] = []
        self.assets: list[str] = []
        self.errors: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        element_id = attributes.get("id")
        if element_id:
            if element_id in self.ids:
                self.errors.append(f"duplicate id #{element_id}")
            self.ids.add(element_id)
        href = attributes.get("href")
        if href:
            self.references.append(href)
        source = attributes.get("src")
        if source:
            self.assets.append(source)


def normalize_java(source: str) -> str:
    return re.sub(r"\s+", " ", source).strip()


def audit_page(path: Path) -> list[str]:
    audit = PageAudit()
    audit.feed(path.read_text(encoding="utf-8"))
    errors = [f"{path.relative_to(ROOT)}: {error}" for error in audit.errors]
    for reference in audit.references:
        if reference.startswith("#") and reference[1:] not in audit.ids:
            errors.append(f"{path.relative_to(ROOT)}: missing anchor {reference}")
    local = audit.assets + [
        reference
        for reference in audit.references
        if not urlparse(reference).scheme and not reference.startswith("#")
    ]
    for reference in local:
        target = (path.parent / reference).resolve()
        if reference.endswith("/"):
            target /= "index.html"
        if reference and not target.exists():
            errors.append(f"{path.relative_to(ROOT)}: missing local resource {reference}")
    return errors


def public_api_files() -> list[Path]:
    api = ROOT / "modules" / "aether-api" / "src" / "main" / "java"
    files = [path for path in api.rglob("*.java") if path.name != "package-info.java"]
    additional = [
        ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/Aether.java",
        ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/DatabaseMetrics.java",
        ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/DatabaseOperation.java",
        ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/MeteredAetherDatabase.java",
        ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/OperationMetrics.java",
        ROOT / "modules/aether-embedded-typed/src/main/java/io/aetherdb/embedded/typed/AetherEmbedded.java",
        ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec/BuiltInKeyCodecs.java",
        ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec/BuiltInValueCodecs.java",
        ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec/generated/GeneratedCodecs.java",
    ]
    annotations = ROOT / "modules/aether-codec-annotations/src/main/java"
    return sorted(files + additional + list(annotations.rglob("*.java")))


def main() -> int:
    errors: list[str] = []
    pages = [WEBSITE / "index.html", DOCS]
    for page in pages:
        errors.extend(audit_page(page))

    docs_text = DOCS.read_text(encoding="utf-8")
    docs_plain = re.sub(r"<[^>]+>", " ", docs_text)
    for source in public_api_files():
        public_type = source.stem
        if public_type not in docs_plain:
            errors.append(f"developer docs do not mention public API type {public_type}")

    signature_checks = {
        "modules/aether-api/src/main/java/io/aetherdb/api/typed/TypedAetherDatabase.java": [
            "defineCollection( String name, Class<K> keyType, Class<V> valueType)",
            "defineCollection( CollectionId id, String name, Class<K> keyType, Class<V> valueType)",
            "TypedWriteBatch batch()",
            "TypedAetherSnapshot snapshot()",
        ],
        "modules/aether-api/src/main/java/io/aetherdb/api/AetherDatabase.java": [
            "LookupResult get(byte[] key)",
            "AetherCursor scan(byte[] startInclusive, byte[] endExclusive)",
            "WriteResult write(WriteBatch batch, WriteOptions options)",
        ],
        "modules/aether-api/src/main/java/io/aetherdb/api/WriteBatch.java": [
            "MAX_OPERATIONS = 10_000",
            "MAX_ENCODED_BYTES = 32L * 1024 * 1024",
            "MAX_KEY_BYTES = 65_536",
            "MAX_VALUE_BYTES = 16 * 1024 * 1024",
        ],
        "modules/aether-engine/src/main/java/io/aetherdb/engine/Aether.java": [
            "openInMemory()",
            "open(Path directory)",
            "openWithMetrics(Path directory)",
        ],
    }
    for relative, signatures in signature_checks.items():
        source = normalize_java((ROOT / relative).read_text(encoding="utf-8"))
        for signature in signatures:
            if normalize_java(signature) not in source:
                errors.append(f"documented contract signature changed or disappeared: {signature}")

    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^aetherVersion=(.+)$", properties, re.MULTILINE)
    if not match or match.group(1) not in docs_text:
        errors.append("developer docs version does not match gradle.properties")

    if errors:
        print("Developer documentation check failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Developer documentation check passed: {len(public_api_files())} public types, "
        f"{len(pages)} pages"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
