"""One Aether service per paired block; no live-store checkpoint or partial resume."""
import os
import time
from pathlib import Path

from paper_common import java_daemon, write_json
from longitudinal_state import save_receipt

LIFECYCLE = "persistent-per-block"


def validate_service_stages(stages):
    if (len(stages) != 5 or len({s["engineInfo"]["pid"] for s in stages}) != 1
            or [s.get("serviceStartCount") for s in stages] != [1, 0, 0, 0, 0]
            or [s.get("serviceStopCount") for s in stages] != [0, 0, 0, 0, 1]
            or any(s.get("serviceLifecycle") != LIFECYCLE for s in stages)):
        raise ValueError("persistent block must have one service PID, one start and one final stop")


class PersistentService:
    def __init__(self, scratch, reports, identity, *, completed=False, factory=java_daemon, clock=time.perf_counter):
        self.scratch, self.reports, self.identity = Path(scratch), Path(reports), identity
        self.completed, self.factory, self.clock = completed, factory, clock
        self.manager = self.daemon = None
        self.started_at = None
        self.lease = self.reports / "persistent-service.lease.json"

    def __enter__(self):
        self.reports.mkdir(parents=True, exist_ok=True)
        marker = self.reports / "persistent-block-started.json"
        if not self.completed:
            if marker.exists() or any(self.reports.glob("v*-*.json")) or self.scratch.exists():
                raise ValueError("partial persistent block cannot resume; preserve it and use a fresh campaign output")
            write_json(marker, {**self.identity, "serviceLifecycle": LIFECYCLE})
        return self

    def run_stage(self, backend, stage, worker):
        live = self.scratch / backend / "live"
        startup = 0.
        if backend == "aether" and stage == 0:
            self.manager = self.factory(live)
            self.started_at = self.clock()
            self.daemon = self.manager.__enter__()
            startup = (self.clock() - self.started_at) * 1000
            write_json(self.lease, {**self.identity, "pids": [os.getpid(), self.daemon["pid"]]})
        elif backend == "aether" and self.daemon is None:
            raise RuntimeError("persistent Aether service is missing; no restart fallback")
        live.mkdir(parents=True, exist_ok=True)
        report = worker(backend, stage, live)
        report.update(serviceLifecycle=LIFECYCLE, checkpointPolicy="no live-store checkpoints; complete-block resume only")
        if backend == "aether":
            if stage == 0 and "bulkPort" in self.daemon:
                if not report.get("servicePort"):
                    raise RuntimeError("bulk bootstrap did not publish a training port")
                self.daemon["port"] = report["servicePort"]
                del self.daemon["bulkPort"]
            if report["engineInfo"]["pid"] != self.daemon["pid"]:
                raise RuntimeError("Aether service PID changed inside persistent block")
            report["serviceStartCount"] = int(stage == 0)
            report["serviceStopCount"] = int(stage == 4)
            report["timingsMs"]["startup"] += startup
            if stage == 4:
                started = self.clock()
                self.manager.__exit__(None, None, None)
                self.manager = self.daemon = None
                report["timingsMs"]["close"] += (self.clock() - started) * 1000
                report["serviceResidenceMs"] = (self.clock() - self.started_at) * 1000
                self.lease.unlink(missing_ok=True)
            total = sum(report["timingsMs"].values())
            report.update(totalMs=total, fullLifecycleMs=total, serviceStartupMs=report["timingsMs"]["startup"])
            report["initialPopulation" if stage == 0 else "updateTraining"]["totalMs"] = total
        return save_receipt(self.reports / f"v{stage}-{backend}.json", report,
                            {**self.identity, "backend": backend, "version": stage})

    def __exit__(self, exc_type, exc, traceback):
        if self.manager is not None and self.daemon is not None:
            try:
                self.manager.__exit__(exc_type, exc, traceback)
            finally:
                self.manager = self.daemon = None
                self.lease.unlink(missing_ok=True)
