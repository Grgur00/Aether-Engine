"""Test orchestration with explicit fake reports, never emitted outside pytest tempdirs."""
import contextlib
import json

from paper_common import write_json
import run_matrix


def test_successive_workflow_restarts_java_and_accumulates_real_report_fields(tmp_path, monkeypatch):
    v1, v2 = tmp_path / "v1.csv", tmp_path / "v2.csv"
    v1.write_text("sample_id,split\na,train\n")
    v2.write_text("sample_id,split\na,train\nb,train\n")
    config = tmp_path / "datasets.json"
    write_json(config, {"oct5k": {"manifestV1": str(v1), "manifestV2": str(v2), "samplesV1": 1,
                                "samplesV2": 2, "expectedReusable": 1, "imageSize": 8}})
    args = run_matrix.parser().parse_args(["--config", str(config), "--repeats", "1", "--workflow-experiments", "2"])
    plan = run_matrix.build_plan(args)
    plan["sourceSha256"] = {"fixture": "not-research-evidence"}
    env = {"measurementIdentity": {"fixture": True}, "sourceSha256": plan["sourceSha256"]}
    env_id, protocol_hash = run_matrix.digest(env["measurementIdentity"]), run_matrix.digest(plan)
    root = tmp_path / "campaign"
    write_json(root / "protocol.json", plan)
    write_json(root / f"environment-{env_id}.json", env)
    starts, training_commands = [], []
    @contextlib.contextmanager
    def fake_daemon(path):
        starts.append(path)
        yield {"port": 123}
    def invoke(command, output):
        if "--aether-populate-only" in command:
            report = {"populateOnly": {"populationWallMs": 10}, "mmapPopulateOnly": {"populationWallMs": 20}}
        else:
            training_commands.append(command)
            count = len(training_commands)
            dynamics = {"prepopulatedEntries": count, "invariants": {"passed": True}}
            run = {"cacheDynamics": dynamics, "mmapDynamics": dynamics, "backendOrder": list(plan["backends"].values()),
                "modelParityPassed": True, "engineInfo": {"durability": "DURABLE"},
                "backends": {backend: {"steadyState": {"effectiveSamplesPerSecond": 100},
                                      "lifecycle": {"totalMs": 1000 * count}} for backend in plan["backends"].values()}}
            report = {"storageEngine": "java", "status": "PASSED", "allPassed": True,
                "accelerator": {"deviceAvailable": True, "backend": "cuda"},
                "correctness": {"allChecksumsEqual": True}, "runs": [run]}
        write_json(output, report)
        return report, .01
    monkeypatch.setattr(run_matrix, "java_daemon", fake_daemon)
    monkeypatch.setattr(run_matrix, "invoke", invoke)
    point = plan["conditions"][0]
    block = run_matrix.run_block(plan, point, 0, root / point["conditionId"] / "block-0000", env_id, protocol_hash)
    assert len(starts) == 3  # V1 population, then two independently restarted V2 processes.
    assert all("--aether-cache-mode" in command and "reuse" in command for command in training_commands)
    assert block["workflowCostMs"] == {"raw": 3000, "aether": 3010, "mmap": 3020, "ram": 3000}
    assert block["workflow"][0]["initialReusableEntries"] == 1
    assert block["workflow"][1]["initialReusableEntries"] == 2
