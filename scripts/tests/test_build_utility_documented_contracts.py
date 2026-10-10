"""Offline tooling contracts; no publication, compilation or remote commands."""
import importlib.util
from pathlib import Path
import xml.etree.ElementTree as ET

import kaggle_studio_windows


spec = importlib.util.spec_from_file_location("maven_checker", Path(__file__).parents[1] / "check-maven-publication.py")
maven = importlib.util.module_from_spec(spec)
spec.loader.exec_module(maven)


def test_maven_text_strips_and_requires_namespace():
    root = ET.fromstring('<project xmlns="http://maven.apache.org/POM/4.0.0"><name> Aether </name></project>')
    assert maven.text(root, "m:name") == "Aether"
    assert maven.text(root, "m:description") == ""
    assert maven.text(ET.fromstring("<project><name>Aether</name></project>"), "m:name") == ""


def test_maven_missing_artifact_inventory_and_marker(tmp_path):
    assert len(maven.validate_module(tmp_path, "aether-api", "fixture")) == 5
    assert len(maven.validate_module(tmp_path, "aether-bom", "fixture")) == 2
    assert "missing or empty" in maven.validate_plugin_marker(tmp_path, "fixture")[0]


def test_studio_posix_quoted_path_reassembly_and_plain_pass_through():
    assert kaggle_studio_windows.decode_arguments(["'kernels'", "'push'", "'-p'", "'C:/folder", "with", "spaces'"]) == [
        "kernels", "push", "-p", "C:/folder with spaces"]
    arguments = ["--version"]
    assert kaggle_studio_windows.decode_arguments(arguments) is arguments
