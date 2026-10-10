import contextlib
import os

import pytest

from persistent_service import LIFECYCLE, PersistentService, validate_service_stages


def fake_worker(service):
    def run(backend, stage, live):
        assert service.daemon["pid"] == 42
        return {"engineInfo": {"pid": 42}, "timingsMs": {"startup": 0., "work": 10., "close": 2.},
                "initialPopulation" if stage == 0 else "updateTraining": {"totalMs": 12.}}
    return run


def test_one_start_one_close_and_charged_once(tmp_path):
    calls = []
    tick = iter(range(100))
    @contextlib.contextmanager
    def daemon(path):
        calls.append("start")
        yield {"pid": 42, "port": 1234}
        calls.append("close")
    with PersistentService(tmp_path / "stores", tmp_path / "reports", {},
                           factory=daemon, clock=lambda: next(tick) / 1000) as service:
        stages = [service.run_stage("aether", i, fake_worker(service)) for i in range(5)]
    assert calls == ["start", "close"]
    validate_service_stages(stages)
    assert [s["timingsMs"]["startup"] for s in stages] == [1., 0., 0., 0., 0.]
    assert stages[-1]["timingsMs"]["close"] == 3.
    assert sum(s["fullLifecycleMs"] for s in stages) == 62.
    assert not list((tmp_path / "stores").rglob("checkpoint-*"))


def test_interruption_closes_service_but_cannot_resume(tmp_path):
    calls = []
    @contextlib.contextmanager
    def daemon(path):
        try:
            yield {"pid": 42, "port": 1234}
        finally:
            calls.append("close")
    with pytest.raises(RuntimeError, match="interrupted"):
        with PersistentService(tmp_path / "stores", tmp_path / "reports", {}, factory=daemon) as service:
            service.run_stage("aether", 0, fake_worker(service))
            raise RuntimeError("interrupted")
    assert calls == ["close"]
    with pytest.raises(ValueError, match="partial persistent block"):
        with PersistentService(tmp_path / "stores", tmp_path / "reports", {}):
            pass


def test_replacement_pid_rejected():
    stages = [{"engineInfo": {"pid": 42}, "serviceStartCount": int(i == 0),
               "serviceStopCount": int(i == 4), "serviceLifecycle": LIFECYCLE} for i in range(5)]
    stages[2]["engineInfo"] = {"pid": 43}
    with pytest.raises(ValueError, match="one service PID"):
        validate_service_stages(stages)


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="opt-in real persistent service through V4")
def test_real_persistent_service_and_completed_resume(tmp_path):
    from test_longitudinal_comparison import exercise_real_campaign
    exercise_real_campaign(tmp_path, LIFECYCLE)
