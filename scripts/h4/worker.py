"""Join the delegated workload cgroup before importing numerical/backend code."""
import argparse
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--group", type=Path)
    args = parser.parse_args()
    from .common import write_json
    if args.output.exists():
        raise ValueError("worker receipt already exists; immutable output required")
    task = json.loads(args.task.read_text())
    try:
        group = args.group.resolve(strict=True) if args.group is not None else None
        if group is not None:
            if not group.name.startswith("aether-h4-"):
                raise ValueError("unsafe workload cgroup name")
            (group / "cgroup.procs").write_text(str(os.getpid()))
            if (set((group / "cgroup.procs").read_text().split()) != {str(os.getpid())}
                    or int((group / "memory.max").read_text()) != task["memory_limit_bytes"]
                    or int((group / "memory.swap.max").read_text()) != 0):
                raise ValueError("controlled worker enforcement/membership mismatch")
        from .inputs import bind_inputs
        from .backend_worker import run_backend
        binding = bind_inputs(task["data"], task["prepared"], task["protocol"], verify_source=True)
        if binding["protocol"]["memory_limit_bytes"] != task["memory_limit_bytes"]:
            raise ValueError("task memory limit differs from bound protocol")
        if task["backend"] == "aether":
            import math
            from .storage_preflight import require_previous_sizes
            payload_bytes = binding["manifest"]["conditions"][task["condition"]]["payload_bytes"]
            rounded = math.ceil(payload_bytes / 1024 ** 3) * 1024 ** 3
            import paper_common
            paper_common.java_classpath()
            require_previous_sizes(max(2 * 1024 ** 3, rounded), task.get("prior_preflights", []),
                                   expected_java_sources=paper_common.java_build_sources(paper_common.ROOT))
        result = run_backend(task, binding, group=group)
    except Exception as error:
        result = dict(schema="aether-h4-backend-failure-v1", passed=False, primary_h4_eligible=False,
                      worker_pid=os.getpid(), error_type=type(error).__name__, error=str(error))
    write_json(args.output, result)
    return 0 if result["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
