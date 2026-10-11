"""Probe delegated cgroup v2 controls without changing the notebook parent.

This is a capability/file-accounting probe, not beyond-RAM qualification.
"""
import argparse
from contextlib import contextmanager
import json
import os
import platform
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

from .common import counters, read_evidence

COUNTER_FILES = ("memory.max", "memory.swap.max", "memory.current", "memory.peak",
                 "memory.stat", "memory.events", "io.stat", "cgroup.procs")


def snapshot(group):
    return {name: read_evidence(Path(group) / name) for name in COUNTER_FILES}


def io_counters(text):
    devices = {}
    for line in text.splitlines():
        device, *fields = line.split()
        major, minor = device.split(":")
        if not major.isdigit() or not minor.isdigit() or device in devices:
            raise ValueError("invalid or duplicate I/O device")
        devices[device] = dict((key, int(value)) for key, value in (field.split("=", 1) for field in fields))
    return devices


def verify_enforcement(state, *, limit, pids):
    for name in ("memory.max", "memory.swap.max", "memory.events", "cgroup.procs"):
        if not state[name]["available"]:
            raise ValueError("required enforcement evidence unavailable: " + name)
    if int(state["memory.max"]["text"]) != limit or int(state["memory.swap.max"]["text"]) != 0:
        raise ValueError("workload memory/swap limits changed")
    if not pids or set(state["cgroup.procs"]["text"].split()) != {str(pid) for pid in pids}:
        raise ValueError("workload process membership changed")
    events = counters(state["memory.events"]["text"])
    if not {"oom", "oom_kill"}.issubset(events) or any(events.get(key, 0) for key in ("oom", "oom_kill", "oom_group_kill")):
        raise ValueError("OOM-free controlled workload cannot be verified")
    return True


def assess_read_window(before, after, *, limit, payload_bytes, consumed_bytes, pids):
    """Qualify actual workload snapshots, not diagnostic or fabricated pressure.

    This is resource evidence only; dataset/backend correctness is a separate gate.
    """
    report = {"out_of_core_resource_evidence_verified": False,
              "primary_h4_eligible": False, "physical_read_bytes": None,
              "pressure_verified": False, "reason": "not verified"}
    try:
        if limit <= 0 or payload_bytes <= limit or consumed_bytes < payload_bytes or not pids:
            raise ValueError("requires a larger-than-budget fully consumed workload and its PIDs")
        expected_pids = {str(pid) for pid in pids}
        for state in (before, after):
            for name in ("memory.max", "memory.swap.max", "memory.stat", "memory.events", "cgroup.procs", "io.stat"):
                if not state[name]["available"]:
                    raise ValueError("required resource evidence unavailable: " + name)
            if int(state["memory.max"]["text"]) != limit or int(state["memory.swap.max"]["text"]) != 0:
                raise ValueError("memory/swap enforcement differs from requested budget")
            if set(state["cgroup.procs"]["text"].split()) != expected_pids:
                raise ValueError("workload membership incomplete or includes unattributed processes")
            events = counters(state["memory.events"]["text"])
            if not {"oom", "oom_kill"}.issubset(events) or any(events.get(key, 0) for key in ("oom", "oom_kill", "oom_group_kill")):
                raise ValueError("OOM-free execution cannot be verified")
        start, end = (counters(state["memory.stat"]["text"]) for state in (before, after))
        required = {"file", "pgscan", "pgsteal", "workingset_refault_file"}
        if not required.issubset(start) or not required.issubset(end):
            raise ValueError("required file-cache/reclaim counters unavailable")
        deltas = {key: end[key] - start[key] for key in required if key != "file"}
        if any(value < 0 for value in deltas.values()):
            raise ValueError("memory counters regressed across workload window")
        report["memory_counter_deltas"] = deltas
        report["pressure_verified"] = (deltas["pgscan"] > 0 and deltas["pgsteal"] > 0
                                       and deltas["workingset_refault_file"] > 0 and end["file"] > 0)
        io_start, io_end = (io_counters(state["io.stat"]["text"]) for state in (before, after))
        if not io_end or not set(io_start).issubset(io_end):
            raise ValueError("attributable cgroup I/O counters unavailable or reset")
        read_bytes = 0
        for device, values in io_end.items():
            if "rbytes" not in values:
                raise ValueError("physical read counter unavailable")
            delta = values["rbytes"] - io_start.get(device, {}).get("rbytes", 0)
            if delta < 0:
                raise ValueError("physical read counter regressed")
            read_bytes += delta
        report["physical_read_bytes"] = read_bytes
        report["minimum_physical_read_bytes"] = max(1024 ** 2, limit // 16)
        report["out_of_core_resource_evidence_verified"] = (report["pressure_verified"]
            and read_bytes >= report["minimum_physical_read_bytes"])
        report["reason"] = ("resource window qualified; dataset/backend correctness still required"
                            if report["out_of_core_resource_evidence_verified"]
                            else "sustained reclaim/refault and nontrivial physical reads not established")
    except (ValueError, KeyError, TypeError) as error:
        report["reason"] = str(error)
    return report


def delegated_parent(current, mount, explicit):
    current, mount = Path(current).resolve(strict=True), Path(mount).resolve(strict=True)
    parent = Path(explicit).resolve(strict=True)
    if (parent == mount or parent == current or not parent.is_relative_to(mount)
            or not current.is_relative_to(parent)):
        raise RuntimeError("explicit cgroup parent must be a non-root ancestor of the launcher leaf")
    if (parent / "cgroup.procs").read_text().split():
        raise RuntimeError("delegated parent contains processes; launcher must occupy a leaf")
    if "memory" not in (parent / "cgroup.subtree_control").read_text().split():
        raise RuntimeError("explicit parent has no delegated memory controller; parent unchanged")
    return parent


def discover_parent(mount=Path("/sys/fs/cgroup")):
    mount = Path(mount).resolve(strict=True)
    lines = Path("/proc/self/cgroup").read_text().splitlines()
    unified = [line.split(":", 2)[2] for line in lines if line.startswith("0::")]
    if len(unified) != 1 or ".." in Path(unified[0]).parts:
        raise RuntimeError("unified cgroup membership is missing or unsafe")
    candidates = [mount / unified[0].lstrip("/"), mount]
    for candidate in candidates:
        resolved = candidate.resolve()
        if not resolved.is_relative_to(mount):
            continue
        try:
            members = (resolved / "cgroup.procs").read_text().split()
            if str(os.getpid()) in members:
                explicit = os.environ.get("H4_CGROUP_PARENT")
                if explicit:
                    return delegated_parent(resolved, mount, explicit)
                return resolved
        except OSError:
            continue
    raise RuntimeError("cannot bind this process to its visible cgroup mount")


def configure_child(parent, name, limit):
    if type(limit) is not int or limit < 256 * 1024 ** 2:
        raise ValueError("probe memory limit must be at least 256 MiB")
    if not name.startswith("aether-h4-") or Path(name).name != name:
        raise ValueError("unsafe child cgroup name")
    parent = Path(parent).resolve(strict=True)
    if "memory" not in (parent / "cgroup.subtree_control").read_text().split():
        raise RuntimeError("memory controller is not delegated to children; parent unchanged")
    child = parent / name
    child.mkdir(exist_ok=False)
    try:
        (child / "memory.max").write_text(str(limit))
        (child / "memory.swap.max").write_text("0")
        (child / "memory.oom.group").write_text("1")
        if int((child / "memory.max").read_text()) != limit:
            raise RuntimeError("child memory.max readback differs from requested limit")
        if (child / "memory.swap.max").read_text().strip() != "0":
            raise RuntimeError("child swap disable readback failed")
    except BaseException:
        # cgroup control files are virtual; never recursively delete their paths.
        child.rmdir()
        raise
    return child


@contextmanager
def diagnostic_java(group, scratch):
    with tempfile.TemporaryDirectory(prefix="aether-h4-java-probe-", dir=scratch) as directory:
        receipt = Path(directory) / "ready.json"
        source = Path(__file__).with_name("java") / "H4MemoryProbe.java"
        with (Path(directory) / "stderr.txt").open("w+") as stderr:
            process = subprocess.Popen(["java", "-Xmx512m", str(source), str(receipt)],
                                       stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=stderr)
            try:
                deadline = time.monotonic() + 90
                while not receipt.exists():
                    if process.poll() is not None or time.monotonic() >= deadline:
                        stderr.seek(0)
                        raise RuntimeError("diagnostic Java 21 startup failed: " + stderr.read(4096))
                    time.sleep(.05)
                evidence = json.loads(receipt.read_text())
                if evidence["pid"] != process.pid or evidence["java_major"] != 21:
                    raise RuntimeError("diagnostic JVM identity mismatch")
                evidence["membership_verified"] = str(process.pid) in (group / "cgroup.procs").read_text().split()
                evidence["proc_membership"] = read_evidence(Path("/proc") / str(process.pid) / "cgroup")
                if not evidence["membership_verified"] or not 0 < evidence["heap_max_bytes"] <= 512 * 1024 ** 2:
                    raise RuntimeError("diagnostic JVM escaped cgroup or heap configuration")
                yield evidence
            finally:
                process.stdin.close()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def probe_worker(group, scratch, byte_count):
    group = Path(group).resolve(strict=True)
    (group / "cgroup.procs").write_text(str(os.getpid()))
    membership = str(os.getpid()) in (group / "cgroup.procs").read_text().split()
    if not membership:
        raise RuntimeError("probe process did not join requested group")
    with diagnostic_java(group, scratch) as java:
        before = snapshot(group)
        descriptor, filename = tempfile.mkstemp(prefix="aether-h4-file-probe-", dir=scratch)
        try:
            with os.fdopen(descriptor, "w+b", buffering=0) as stream:
                chunk = bytes(1024 * 1024)
                for _ in range(byte_count // len(chunk)):
                    stream.write(chunk)
                os.fsync(stream.fileno())
                stream.seek(0)
                touched = 0
                while block := stream.read(len(chunk)):
                    touched += len(block)
            after = snapshot(group)
            members = (group / "cgroup.procs").read_text().split()
            if str(os.getpid()) not in members or str(java["pid"]) not in members:
                raise RuntimeError("diagnostic Python/JVM membership changed during file-cache probe")
            return {"pid": os.getpid(), "membership_verified": membership, "java": java,
                    "proc_membership": read_evidence("/proc/self/cgroup"),
                    "bytes_touched": touched, "before": before, "after": after}
        finally:
            os.unlink(filename)


def probe(scratch, limit=2 * 1024 ** 3):
    report = {"memory_control_supported": False, "file_cache_accounting_verified": False,
              "out_of_core_verified": False, "requested_memory_limit_bytes": limit,
              "pressure_verified": False, "physical_reads_verified": False,
              "reason": "not attempted", "parent_modified": False}
    if platform.system() != "Linux":
        report["reason"] = "cgroup v2 probe requires Linux"
        return report
    child = None
    try:
        parent = discover_parent()
        report["parent"] = str(parent)
        report["parent_snapshot"] = snapshot(parent)
        child = configure_child(parent, "aether-h4-" + uuid.uuid4().hex, limit)
        report["child"] = str(child)
        report["configured"] = snapshot(child)
        environment = dict(os.environ)
        scripts = str(Path(__file__).resolve().parents[1])
        environment["PYTHONPATH"] = scripts + os.pathsep + environment.get("PYTHONPATH", "")
        result = subprocess.run(
            [sys.executable, "-m", "h4.memory_controller", "--worker-group", str(child),
             "--scratch", str(Path(scratch).resolve()), "--probe-bytes", str(64 * 1024 ** 2)],
            capture_output=True, text=True, timeout=120, env=environment,
        )
        report["worker_exit_code"] = result.returncode
        report["worker_stderr"] = result.stderr
        report["final_snapshot"] = snapshot(child)
        if result.returncode:
            raise RuntimeError("controlled probe failed; inspect exit code and memory.events")
        worker = json.loads(result.stdout)
        report["worker"] = worker
        before = counters(worker["before"]["memory.stat"]["text"])
        after = counters(worker["after"]["memory.stat"]["text"])
        events = counters(report["final_snapshot"]["memory.events"]["text"])
        if not {"oom", "oom_kill"}.issubset(events) or "file" not in before or "file" not in after:
            raise RuntimeError("required memory accounting counters are unavailable")
        no_oom = all(events.get(key, 0) == 0 for key in ("oom", "oom_kill", "oom_group_kill"))
        report["oom_free"] = no_oom
        report["diagnostic_java_membership_verified"] = bool(worker["java"]["membership_verified"])
        report["memory_control_supported"] = bool(worker["membership_verified"]
                                                  and report["diagnostic_java_membership_verified"] and no_oom)
        report["file_cache_accounting_verified"] = after.get("file", 0) > before.get("file", 0)
        report["reason"] = "diagnostic Python/JVM capability only; actual Aether/worker and larger-than-budget reclaim/read evidence still required"
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        report["reason"] = str(error)
    finally:
        if child is not None:
            try:
                members = child / "cgroup.procs"
                if members.exists() and members.read_text().split():
                    # Kill only our dedicated child group if a timed-out worker left descendants.
                    (child / "cgroup.kill").write_text("1")
                    deadline = time.monotonic() + 5
                    while members.read_text().split() and time.monotonic() < deadline:
                        time.sleep(.05)
                child.rmdir()
                report["cleanup"] = {"completed": True}
            except OSError as error:
                report["cleanup"] = {"completed": False, "error": str(error)}
                report["memory_control_supported"] = False
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker-group", required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--probe-bytes", type=int, default=64 * 1024 ** 2)
    args = parser.parse_args()
    if not 0 < args.probe_bytes <= 64 * 1024 ** 2 or args.probe_bytes % (1024 * 1024):
        parser.error("probe bytes must be a positive whole MiB, at most 64 MiB")
    print(json.dumps(probe_worker(args.worker_group, args.scratch, args.probe_bytes)))


if __name__ == "__main__":
    main()
