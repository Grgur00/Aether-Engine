"""Launch one H4 worker in a fresh delegated child; never alter parent limits."""
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

from .common import write_json
from .memory_controller import configure_child, discover_parent, snapshot


def launch(task_path, output, *, limit, timeout_seconds):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    child = None
    report = dict(schema="aether-h4-controlled-launch-v1", completed=False,
                  parent_modified=False, cleanup_completed=False)
    try:
        child = configure_child(discover_parent(), "aether-h4-" + uuid.uuid4().hex, limit)
        report.update(child=str(child), configured=snapshot(child))
        environment = dict(os.environ)
        root = Path(__file__).resolve().parents[2]
        environment["PYTHONPATH"] = os.pathsep.join((str(root / "scripts"),
                                                     str(root / "clients/python"),
                                                     environment.get("PYTHONPATH", "")))
        command = [sys.executable, "-m", "h4.worker", "--task", str(Path(task_path).resolve()),
                   "--output", str(output / "backend.json"), "--group", str(child)]
        report["command"] = command
        with (output / "worker.log").open("wb") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                       env=environment, timeout=timeout_seconds, cwd=root)
        report.update(worker_exit_code=completed.returncode, completed=completed.returncode == 0)
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        report.update(error_type=type(error).__name__, error=str(error))
    finally:
        if child is not None:
            report["final_snapshot"] = snapshot(child)
            try:
                members = child / "cgroup.procs"
                if members.read_text().split():
                    report.update(completed=False, leftover_processes=members.read_text().split())
                    (child / "cgroup.kill").write_text("1")
                    deadline = time.monotonic() + 5
                    while members.read_text().split() and time.monotonic() < deadline:
                        time.sleep(.05)
                child.rmdir()
                report["cleanup_completed"] = True
            except OSError as error:
                report.update(completed=False, cleanup_error=str(error))
        write_json(output / "controller.json", report)
    return report
