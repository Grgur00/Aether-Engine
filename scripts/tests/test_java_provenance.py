"""Build evidence fixtures; no compiled engine or research results are fabricated."""
import os

import pytest

import paper_common as common


@pytest.fixture
def build(tmp_path, monkeypatch):
    monkeypatch.setattr(common, "ROOT", tmp_path)
    source = tmp_path / "modules/example/src/main/java/Example.java"
    source.parent.mkdir(parents=True)
    source.write_text("class Example {}")
    classes = tmp_path / "classes"
    classes.mkdir()
    (classes / "Example.class").write_bytes(b"explicit test fixture")
    jar = tmp_path / "dependency.jar"
    jar.write_bytes(b"explicit dependency fixture")
    output = tmp_path / "modules/aether-training-cache/build/paper-runtime-classpath.txt"
    output.parent.mkdir(parents=True)
    output.write_text(os.pathsep.join(map(str, (classes, jar))), encoding="utf-8")
    common.write_json(output.with_name("paper-runtime-build.json"), {
        "schema": "aether-java-build-v1", "sources": common.java_build_sources(tmp_path),
        "classpathSha256": common.sha256(output),
        "runtime": {str(path): common.java_runtime_record(path) for path in (classes, jar)}})
    return tmp_path, output, source, classes, jar


def test_matching_build_accepted(build):
    assert common.java_classpath() == build[1].read_text()


@pytest.mark.parametrize("change", ["source", "new_source", "removed_source", "class", "new_class", "jar", "classpath", "missing_manifest"])
def test_stale_or_modified_build_rejected(build, change):
    root, output, source, classes, jar = build
    if change == "source":
        source.write_text("class Changed {}")
    elif change == "new_source":
        source.with_name("New.java").write_text("class New {}")
    elif change == "removed_source":
        source.unlink()
    elif change == "class":
        (classes / "Example.class").write_bytes(b"changed")
    elif change == "new_class":
        (classes / "New.class").write_bytes(b"added")
    elif change == "jar":
        jar.write_bytes(b"changed")
    elif change == "classpath":
        output.write_text(str(jar))
    else:
        output.with_name("paper-runtime-build.json").unlink()
    with pytest.raises(RuntimeError, match="Rebuild Java"):
        common.java_classpath()
