"""Run the frozen pilot worker unchanged; add execution identity outside its endpoint."""
import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    candidate = args.candidate_root.resolve()
    sys.path.insert(0, str(candidate / "scripts"))
    from paper_common import write_json
    request = json.loads(args.request.read_text(encoding="utf-8"))
    lease = Path(request["lease"])
    write_json(lease, {"pids": [os.getpid()]})
    try:
        spec = importlib.util.spec_from_file_location("frozen_longitudinal_worker", candidate / "scripts/longitudinal_worker.py")
        worker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(worker)
        if request.get("cpuFixture") or not worker.base.torch.cuda.is_available():
            raise RuntimeError("H2 performance worker requires CUDA; CPU smoke is not confirmatory evidence")
        if request["config"].get("trainV0") is not True or request["config"].get("serverTrace") is not False:
            raise ValueError("H2 worker protocol drift")
        mapping = request.get("inputBinding")
        if mapping is None:
            result = worker.execute(request)
        else:
            from h2_input_paths import LOGICAL_ROOT, input_paths
            if mapping.get("logicalRoot") != LOGICAL_ROOT:
                raise ValueError("frozen logical input root changed")
            with input_paths(worker.base.workload, mapping):
                result = worker.execute(request)
            result["inputBinding"] = mapping
        result.update(device="cuda", traceEnabled=False,
                      trainingState="fresh-model-and-optimizer-per-version",
                      workerSource="frozen pilot longitudinal_worker.py; unchanged")
        write_json(args.output, result)
    finally:
        lease.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
