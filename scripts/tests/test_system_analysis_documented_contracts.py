"""Offline runtime-inventory and CSV contracts, not measured campaign evidence."""
import csv
import json

import pytest

import compare_cache_hotspots
import systems_figures


def test_verified_classpath_rejects_changed_archived_entry(tmp_path):
    entry = tmp_path / "fixture.jar"
    entry.write_bytes(b"original fixture")
    record = compare_cache_hotspots.java_runtime_record(entry)
    (tmp_path / "runtime.json").write_text(json.dumps({"entries": [str(entry)], "runtime": {str(entry): record}}))
    assert compare_cache_hotspots.verified_classpath(tmp_path) == str(entry)
    entry.write_bytes(b"changed fixture")
    with pytest.raises(ValueError, match="frozen runtime changed"):
        compare_cache_hotspots.verified_classpath(tmp_path)


def test_csv_table_uses_ordered_union_and_blank_missing_cells(tmp_path):
    path = tmp_path / "table.csv"
    systems_figures.csv_table(path, [{"a": 1}, {"b": 2, "a": 3}])
    with path.open(newline="") as stream:
        rows = list(csv.reader(stream))
    assert rows == [["a", "b"], ["1", ""], ["3", "2"]]


def test_csv_table_rejects_empty_input_before_writing(tmp_path):
    path = tmp_path / "table.csv"
    with pytest.raises(ValueError, match="no measurements"):
        systems_figures.csv_table(path, [])
    assert not path.exists()
