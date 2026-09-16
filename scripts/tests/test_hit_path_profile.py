"""Hit-diagnostic invariants; fixtures are not benchmark evidence."""
import json

import pytest

from hit_path_profile import InvalidHitRun, build_plan, jobs, require_hits, require_no_activity
from run_matrix import parser


def idle():
    return dict(state="IDLE", debtBytes=0, failed=0, completed=2,
                flushesCompleted=8, backgroundCompactionsStarted=2)


def test_hit_only_rejects_missing_values_and_accepts_empty_hits():
    with pytest.raises(InvalidHitRun, match="missing"):
        require_hits([b"artifact", None], 2)
    require_hits([b""], 1)


@pytest.mark.parametrize("counter", ["flushesCompleted", "backgroundCompactionsStarted", "completed", "failed"])
def test_hit_only_rejects_storage_activity(counter):
    after = idle()
    after[counter] += 1
    with pytest.raises(InvalidHitRun):
        require_no_activity(idle(), after)


def test_hit_only_rejects_publication_even_without_flush():
    with pytest.raises(InvalidHitRun, match="publishes"):
        require_no_activity(idle(), idle(), publishes=1)


def test_hit_only_rejects_missing_instrumentation():
    legacy = idle()
    del legacy["flushesCompleted"]
    with pytest.raises(InvalidHitRun, match="rebuild"):
        require_no_activity(legacy, idle())


def test_hit_only_rejects_correlated_flush():
    trace = {"outcome": "complete", "events": [{"stage": "server_trace", "server": {"flushes": [{}]}}]}
    with pytest.raises(InvalidHitRun, match="trace"):
        require_no_activity(idle(), idle(), traces=[trace])


def test_diagnostic_identity_and_fixed_full_input_batch(tmp_path):
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"oct5k": {"manifestV2": "not-executed.csv", "samplesV2": 1505}}))
    args = parser().parse_args(["--config", str(config), "--backends", "aether,mmap", "--hit-path-profile",
                               "--request-sizes", "1,64", "--batch-size", "16", "--repeats", "5", "--epochs", "10"])
    plan = build_plan(args)
    assert plan["measurementRole"] == "steady-state-hit-path-diagnostic"
    assert plan["allArtifactsPrepopulated"] and not plan["publishesAllowed"] and not plan["confirmatory"]
    assert plan["requestSizes"] == [1, 16, 64]
    trials = jobs(plan, 7)
    assert trials == jobs(plan, 7)
    assert all(job["requestSize"] == 16 for job in trials if job["layer"] == "full-input")
    assert {job["prefetchDepth"] for job in trials if job["layer"] == "full-input"} == {0, 1, 2, 4, 8}


def test_diagnostic_cannot_be_confirmatory(tmp_path):
    args = parser().parse_args(["--hit-path-profile", "--confirmatory"])
    with pytest.raises(ValueError, match="confirmatory"):
        build_plan(args)
