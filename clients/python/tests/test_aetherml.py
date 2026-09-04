import json
import tempfile
from argparse import Namespace
from pathlib import Path

from aether_bench import accelerator_backend_supported, build_sweep_plan, evidence_audit, expected_gpu_matches, export_tidy_reports, parse_args, run_suite
from benchmark_aetherml import run_benchmark
from benchmark_gpu_segmentation import (
    BackendContext,
    GpuUtilizationSampler,
    accelerator_backend_matches,
    artifact_to_tensor_sample,
    configuration as gpu_configuration,
    gpu_name_matches,
    load_sources,
    parse_args as parse_gpu_args,
    preprocess_sample,
    run_benchmark as run_gpu_benchmark,
    training_break_even_epoch,
)
from oct_segmentation_workload import run_workload
from aether_training_cache.ml import (
    AetherDataLoader,
    AetherDataset,
    AetherMLStore,
    ExperimentRecord,
    environment_report,
)


def test_cached_transform_reuses_committed_artifact():
    calls = {"count": 0}
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")

        @store.cached_transform(version="1", parameters={"resize": [2, 2]})
        def transform(value):
            calls["count"] += 1
            return {"value": value * 2}

        assert transform(3) == {"value": 6}
        assert transform(3) == {"value": 6}
        assert calls["count"] == 1
        assert transform.aether_artifact_id(3).startswith("aether:")


def test_snapshot_restore_and_experiment_lookup():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        cache_key = store.transformation_key(
            input_hash="input",
            transformation_name="normalize",
            transformation_version="1",
            parameters={"method": "zscore"},
        )
        metadata = store.commit_artifact(
            b"payload",
            cache_key=cache_key,
            transformation_name="normalize",
            transformation_version="1",
            parameters={"method": "zscore"},
        )
        snapshot = store.create_snapshot("oct", [metadata.artifact_id], {"split": "train"})
        store.record_experiment(
            ExperimentRecord(
                experiment_id="exp-1",
                dataset_snapshot=snapshot.snapshot_id,
                model="unet",
                training_parameters={"epochs": 1},
                random_seed=42,
            )
        )

        assert store.restore_snapshot(snapshot.snapshot_id) == {metadata.artifact_id: b"payload"}
        verification = store.verify_snapshot(snapshot.snapshot_id)
        manifest = store.snapshot_manifest(snapshot.snapshot_id)
        assert verification.is_reproducible
        assert verification.verified_artifact_ids == [metadata.artifact_id]
        assert manifest["snapshot"]["snapshot_id"] == snapshot.snapshot_id
        assert manifest["artifacts"][0]["artifact_id"] == metadata.artifact_id
        assert [record.experiment_id for record in store.experiments_using(metadata.artifact_id)] == ["exp-1"]


def test_aether_dataset_applies_cached_transforms_with_sequence_protocol():
    calls = {"count": 0}
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")

        def add_one(value):
            calls["count"] += 1
            return value + 1

        transform = store.cached_transform_function(add_one, version="1", name="add_one")
        dataset = AetherDataset([1, 2, 1], [transform], return_artifact_ids=True)

        assert len(dataset) == 3
        first_value, first_artifacts = dataset[0]
        second_value, second_artifacts = dataset[1]
        third_value, third_artifacts = dataset[2]

        assert first_value == 2
        assert second_value == 3
        assert third_value == 2
        assert first_artifacts == third_artifacts
        assert first_artifacts[0].startswith("aether:")
        assert second_artifacts[0].startswith("aether:")
        assert calls["count"] == 2


def test_aether_data_loader_batches_cached_dataset_without_torch_dependency():
    calls = {"count": 0}
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")

        def add_one(value):
            calls["count"] += 1
            return value + 1

        transform = store.cached_transform_function(add_one, version="1", name="add_one")
        dataset = AetherDataset([1, 2, 1], [transform])
        loader = AetherDataLoader(dataset, batch_size=2)

        assert len(loader) == 2
        assert list(loader) == [[2, 3], [2]]
        assert calls["count"] == 2


def test_aether_data_loader_supports_custom_collation_and_drop_last():
    dataset = [1, 2, 3]
    loader = AetherDataLoader(dataset, batch_size=2, drop_last=True, collate_fn=sum)

    assert len(loader) == 1
    assert list(loader) == [3]


def test_aether_dataset_records_multistage_lineage():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")

        first = store.cached_transform_function(lambda value: value + 1, version="1", name="first")
        second = store.cached_transform_function(lambda value: value * 10, version="1", name="second")
        dataset = AetherDataset([4], [first, second], return_artifact_ids=True)

        value, artifact_ids = dataset[0]

        assert value == 50
        assert store.parents(artifact_ids[1]) == [artifact_ids[0]]
        assert [metadata.artifact_id for metadata in store.lineage(artifact_ids[1])] == artifact_ids
        assert store.depends_on(artifact_ids[1], artifact_ids[0])
        assert not store.depends_on(artifact_ids[0], artifact_ids[1])


def test_experiment_lookup_matches_upstream_lineage_artifact():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        raw_stage = store.cached_transform_function(lambda value: value + 1, version="1", name="raw_stage")
        final_stage = store.cached_transform_function(lambda value: value * 10, version="1", name="final_stage")
        dataset = AetherDataset([4], [raw_stage, final_stage], return_artifact_ids=True)
        _, artifact_ids = dataset[0]
        snapshot = store.create_snapshot("oct", [artifact_ids[-1]], {"split": "train"})
        store.record_experiment(
            ExperimentRecord(
                experiment_id="exp-lineage",
                dataset_snapshot=snapshot.snapshot_id,
                model="unet",
                training_parameters={"epochs": 1},
                random_seed=42,
            )
        )

        assert [record.experiment_id for record in store.experiments_using(artifact_ids[0])] == ["exp-lineage"]


def test_noop_transform_preserves_distinct_provenance_node_with_shared_content():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")

        first = store.cached_transform_function(lambda value: value + 1, version="1", name="first")
        noop = store.cached_transform_function(lambda value: value, version="1", name="noop")
        dataset = AetherDataset([4], [first, noop], return_artifact_ids=True)

        value, artifact_ids = dataset[0]
        first_metadata = store.artifact_metadata(artifact_ids[0])
        noop_metadata = store.artifact_metadata(artifact_ids[1])

        assert value == 5
        assert artifact_ids[0] != artifact_ids[1]
        assert first_metadata.content_hash == noop_metadata.content_hash
        assert store.parents(artifact_ids[1]) == [artifact_ids[0]]


def test_snapshot_manifest_includes_lineage_by_default():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        first = store.cached_transform_function(lambda value: value + 1, version="1", name="first")
        second = store.cached_transform_function(lambda value: value * 2, version="1", name="second")
        dataset = AetherDataset([2], [first, second], return_artifact_ids=True)
        _, artifact_ids = dataset[0]
        snapshot = store.create_snapshot("lineage", [artifact_ids[-1]])

        manifest = store.snapshot_manifest(snapshot.snapshot_id)

        assert [artifact["artifact_id"] for artifact in manifest["artifacts"]] == artifact_ids


def test_commit_file_supports_large_artifact_references_and_deduplicates_storage():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        source = Path(directory) / "volume.bin"
        source.write_bytes(b"oct-volume" * 128)
        first_key = store.transformation_key(
            input_hash="scan-1",
            transformation_name="volume_export",
            transformation_version="1",
        )
        second_key = store.transformation_key(
            input_hash="scan-1",
            transformation_name="volume_export",
            transformation_version="2",
        )

        first = store.commit_file(
            source,
            cache_key=first_key,
            transformation_name="volume_export",
            transformation_version="1",
        )
        second = store.commit_bytes(
            source.read_bytes(),
            cache_key=second_key,
            source_artifact_ids=[first.artifact_id],
            transformation_name="volume_export",
            transformation_version="2",
        )

        assert first.artifact_id != second.artifact_id
        assert first.content_hash == second.content_hash
        assert first.storage_location == second.storage_location
        assert store.load_artifact_bytes(first.artifact_id) == source.read_bytes()
        assert store.artifact_path(second.artifact_id) == store.artifact_path(first.artifact_id)
        assert store.parents(second.artifact_id) == [first.artifact_id]
        assert store.validate().is_consistent


def test_validate_and_recover_clear_uncommitted_and_dangling_state():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        (Path(directory) / "tmp" / "artifact.tmp").write_bytes(b"partial")
        (Path(directory) / "metadata" / "cache" / "dangling.json").write_text(
            '{"artifact_id": "aether:missing"}',
            encoding="utf-8",
        )
        orphan = Path(directory) / "artifacts" / "aa" / "bb" / "orphan"
        orphan.parent.mkdir(parents=True)
        orphan.write_bytes(b"orphan")

        report = store.validate()

        assert report.temporary_files == ["tmp/artifact.tmp"]
        assert report.dangling_cache_entries == ["dangling.json"]
        assert report.orphan_artifact_files == ["artifacts/aa/bb/orphan"]
        assert store.load_cached("dangling") is None
        assert not report.is_consistent

        store.recover(remove_orphan_artifacts=True)
        assert store.validate().is_consistent


def test_validate_reports_visible_corrupt_artifact():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        cache_key = store.transformation_key(
            input_hash="input",
            transformation_name="normalize",
            transformation_version="1",
        )
        metadata = store.commit_artifact(
            b"payload",
            cache_key=cache_key,
            transformation_name="normalize",
            transformation_version="1",
        )
        (Path(directory) / metadata.storage_location).write_bytes(b"corrupt")

        report = store.validate()

        assert report.checksum_mismatches == [metadata.artifact_id]
        assert report.dangling_cache_entries == [f"{cache_key}.json"]
        assert not report.is_consistent


def test_snapshot_verification_reports_corrupt_snapshot_artifact():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        cache_key = store.transformation_key(
            input_hash="input",
            transformation_name="normalize",
            transformation_version="1",
        )
        metadata = store.commit_artifact(
            b"payload",
            cache_key=cache_key,
            transformation_name="normalize",
            transformation_version="1",
        )
        snapshot = store.create_snapshot("oct", [metadata.artifact_id])
        (Path(directory) / metadata.storage_location).write_bytes(b"corrupt")

        verification = store.verify_snapshot(snapshot.snapshot_id)

        assert not verification.is_reproducible
        assert verification.verified_artifact_ids == []
        assert verification.checksum_mismatches == [metadata.artifact_id]


def test_store_operation_metrics_include_core_latency_distributions():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        cache_key = store.transformation_key(
            input_hash="input",
            transformation_name="normalize",
            transformation_version="1",
        )
        metadata = store.commit_artifact(
            b"payload",
            cache_key=cache_key,
            transformation_name="normalize",
            transformation_version="1",
        )
        store.cached_artifact_id(cache_key)
        store.artifact_metadata(metadata.artifact_id)
        store.load_artifact(metadata.artifact_id)
        store.create_snapshot("oct", [metadata.artifact_id])

        metrics = store.operation_metrics()

        for operation in ["artifact_commit", "artifact_retrieval", "cache_lookup", "metadata_lookup", "snapshot_create"]:
            assert metrics[operation]["count"] > 0
            assert metrics[operation]["max"] >= metrics[operation]["min"]
            assert "confidence95" in metrics[operation]
            assert "standardDeviation" in metrics[operation]

        store.reset_operation_metrics()
        assert all(summary["count"] == 0 for summary in store.operation_metrics().values())


def test_store_supports_batch_cache_lookup_retrieval_and_commit():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        entries = [
            {"cache_key": "batch-a", "data": b"payload-a"},
            {"cache_key": "batch-b", "data": b"payload-b"},
        ]

        metadata = store.commit_bytes_many(
            entries,
            transformation_name="normalize",
            transformation_version="1",
            parameters={"resize": 2},
        )
        cached = store.cached_artifact_ids(["batch-a", "missing", "batch-b"])
        payloads = store.load_artifact_bytes_many(cached.values())
        cached_payloads = store.load_cached_bytes_many(["batch-a", "missing", "batch-b"])
        metrics = store.operation_metrics()

        assert [item.cache_key for item in metadata] == ["batch-a", "batch-b"]
        assert cached == {
            "batch-a": metadata[0].artifact_id,
            "batch-b": metadata[1].artifact_id,
        }
        assert payloads[metadata[0].artifact_id] == b"payload-a"
        assert payloads[metadata[1].artifact_id] == b"payload-b"
        assert cached_payloads == {"batch-a": b"payload-a", "batch-b": b"payload-b"}
        assert metrics["artifact_commit_batch"]["count"] == 1
        assert metrics["cache_lookup_batch"]["count"] == 1
        assert metrics["artifact_retrieval_batch"]["count"] == 1
        assert metrics["cache_payload_retrieval_batch"]["count"] == 1


def test_store_storage_metrics_report_overhead_and_amplification():
    with tempfile.TemporaryDirectory() as directory:
        store = AetherMLStore(directory, code_commit="test")
        cache_key = store.transformation_key(
            input_hash="input",
            transformation_name="normalize",
            transformation_version="1",
        )
        metadata = store.commit_artifact(
            b"payload",
            cache_key=cache_key,
            transformation_name="normalize",
            transformation_version="1",
        )

        storage = store.storage_metrics(raw_dataset_bytes=10, baseline_preprocessed_bytes=7)

        assert storage["rawDatasetBytes"] == 10
        assert storage["baselinePreprocessedBytes"] == 7
        assert storage["cachedArtifactBytes"] == metadata.size
        assert storage["logicalArtifactBytes"] == metadata.size
        assert storage["metadataBytes"] >= storage["cacheIndexBytes"] > 0
        assert storage["walBytes"] == 0
        assert storage["compactionWriteBytes"] == 0
        assert storage["writeAmplification"] == storage["totalStorageBytes"] / 10
        assert storage["cacheAmplification"] == metadata.size / 10
        assert storage["metadataOverhead"] == storage["metadataBytes"] / metadata.size


def test_environment_report_records_reproducibility_fields():
    with tempfile.TemporaryDirectory() as directory:
        environment = environment_report(
            dataset_version="oct-test-v1",
            code_commit="commit-test",
            root=directory,
        )

        assert environment["cpu"]["cores"] is not None
        assert "devices" in environment["gpu"]
        assert "totalBytes" in environment["ram"]
        assert environment["disk"]["totalBytes"] > 0
        assert environment["os"]["system"]
        assert environment["pythonVersion"]
        assert "pytorchVersion" in environment
        assert environment["datasetVersion"] == "oct-test-v1"
        assert environment["gitCommit"] == "commit-test"


def test_benchmark_reports_comparable_baseline_checksums():
    with tempfile.TemporaryDirectory() as directory:
        report = run_benchmark(
            Namespace(samples=4, repeat=2, changed_percent=25.0, work=2),
            Path(directory),
        )

        expected = report["coldTraining"]["checksumSet"]
        assert report["warmTraining"]["checksumSet"] == expected
        assert report["baselines"]["filesystemRecompute"]["checksumSet"] == expected
        assert report["baselines"]["manualPreprocessed"]["checksumSet"] == expected
        packed = report["baselines"]["highPerformancePackedDataset"]
        assert packed["backend"] == "HIGH_PERFORMANCE_PACKED_DATASET_EQUIVALENT"
        assert packed["checksumSet"] == expected
        assert packed["packedBytes"] > 0
        assert packed["samplesPerSecond"] > 0
        assert report["environment"]["datasetVersion"] == "synthetic-oct-samples-4"
        assert report["environment"]["gitCommit"] == "benchmark"
        assert "confidence95" in report["coldTraining"]["epochLatencyNanos"]
        assert report["coldTraining"]["samplesPerSecond"] > 0
        assert report["coldTraining"]["inputWaitNanos"] > 0
        assert report["coldTraining"]["simulatedGpuActiveNanos"] > 0
        assert report["coldTraining"]["simulatedGpuIdleNanos"] == report["coldTraining"]["inputWaitNanos"]
        assert 0 <= report["coldTraining"]["simulatedGpuUtilization"] <= 1
        assert "inputPreparationLatencyNanos" in report["coldTraining"]
        assert "modelComputeLatencyNanos" in report["coldTraining"]
        assert report["operationMetrics"]["cache_lookup"]["count"] > 0
        assert report["operationMetrics"]["artifact_commit"]["count"] > 0
        assert report["storage"]["rawDatasetBytes"] > 0
        assert report["storage"]["writeAmplification"] is not None
        assert report["storage"]["cacheAmplification"] is not None
        assert report["storage"]["metadataOverhead"] is not None
        assert report["recovery"]["isConsistent"]


def test_oct_workload_reports_input_wait_and_utilization_metrics():
    with tempfile.TemporaryDirectory() as directory:
        report = run_workload(
            Namespace(samples=2, epochs=1, height=4, width=4, resize=4, batch_size=2, seed=42),
            Path(directory),
        )

        metrics = report["coldTraining"]["metrics"]
        assert metrics["samplesPerSecond"] > 0
        assert metrics["inputWaitNanos"] > 0
        assert metrics["simulatedGpuActiveNanos"] > 0
        assert metrics["simulatedGpuIdleNanos"] == metrics["inputWaitNanos"]
        assert 0 <= metrics["simulatedGpuUtilization"] <= 1
        assert "batchPreparationLatencyNanos" in metrics
        assert "batchModelComputeLatencyNanos" in metrics
        assert "timeToNextBatchNanos" in metrics


def test_oct_workload_accepts_local_raw_input_directory():
    with tempfile.TemporaryDirectory() as directory:
        input_dir = Path(directory) / "oct"
        input_dir.mkdir()
        (input_dir / "scan-a.raw").write_bytes(bytes(range(16)))
        (input_dir / "scan-b.raw").write_bytes(bytes(reversed(range(16))))
        store_dir = Path(directory) / "store"

        report = run_workload(
            Namespace(
                samples=2,
                epochs=1,
                height=4,
                width=4,
                resize=4,
                batch_size=2,
                seed=42,
                input_dir=str(input_dir),
                extensions=".raw",
            ),
            store_dir,
        )

        assert report["configuration"]["dataset"] == "local-oct-files"
        assert report["environment"]["datasetVersion"].startswith("local-oct-2-")
        assert report["datasetSnapshot"]["configuration"]["dataset"] == "local-oct-files"
        assert report["restoredArtifactCount"] == 2


def test_aether_bench_cli_suite_writes_structured_summary():
    with tempfile.TemporaryDirectory() as directory:
        args = parse_args([
            "--dataset",
            "oct",
            "--pipeline",
            "segmentation",
            "--samples",
            "2",
            "--repeat",
            "1",
            "--trials",
            "1",
            "--work",
            "1",
            "--height",
            "4",
            "--width",
            "4",
            "--resize",
            "4",
            "--batch-size",
            "2",
            "--workers",
            "2",
            "--output-dir",
            directory,
        ])

        summary = run_suite(args)
        summary_path = Path(directory) / "summary.json"
        combined_path = Path(directory) / "aetherml-report.json"

        assert summary["allPassed"]
        assert summary["configuration"]["workers"] == 2
        assert summary["combinedReportPath"] == str(combined_path)
        assert summary["keyStats"]["aethermlBenchmark"]["warmSamplesPerSecond"] > 0
        assert summary["keyStats"]["octSegmentation"]["warmMetrics"]["samplesPerSecond"] > 0
        assert summary["keyStats"]["crashRecovery"]["postRecoveryConsistent"]
        assert summary["keyStats"]["crashRecovery"]["lostAcknowledgedArtifacts"] == 0
        assert "benchmark_aetherml" in summary["reportData"]
        assert "oct_segmentation_workload" in summary["reportData"]
        assert "aetherml_crash_recovery" in summary["reportData"]
        assert [report["name"] for report in summary["reports"]] == [
            "benchmark_aetherml",
            "oct_segmentation_workload",
            "aetherml_crash_recovery",
        ]
        assert json.loads(summary_path.read_text(encoding="utf-8"))["allPassed"]
        assert json.loads(combined_path.read_text(encoding="utf-8"))["keyStats"]["crashRecovery"]["postRecoveryConsistent"]
        assert (Path(directory) / "aetherml-benchmark.json").exists()
        assert (Path(directory) / "oct-segmentation-workload.json").exists()
        assert (Path(directory) / "aetherml-crash-recovery.json").exists()


def test_gpu_segmentation_benchmark_skips_without_matching_accelerator():
    with tempfile.TemporaryDirectory() as directory:
        report = run_gpu_benchmark(parse_gpu_args([
            "--samples",
            "2",
            "--epochs",
            "1",
            "--height",
            "4",
            "--width",
            "4",
            "--resize",
            "4",
            "--batch-size",
            "1",
            "--warmup-steps",
            "0",
            "--accelerator-backend",
            "rocm",
            "--expected-gpu",
            "AMD Radeon RX 7900 XTX",
            "--output",
            str(Path(directory) / "gpu.json"),
        ]))

        if report["status"] == "SKIPPED_UNSUPPORTED_ACCELERATOR":
            assert not report["allPassed"]
            assert report["backends"] == {}
            assert (
                report["accelerator"]["hipVersion"] is None
                or not report["accelerator"]["deviceAvailable"]
                or "AMD Radeon RX 7900" not in (report["accelerator"].get("deviceName") or "")
            )
        else:
            assert report["status"] == "PASSED"
            assert report["deviceSmokeTestPassed"]
            assert report["correctness"]["allChecksumsEqual"]
            assert set(report["backends"]) == {"RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"}
            assert report["protocol"]["singleGetRequests"] == 0
            assert report["protocol"]["singlePutRequests"] == 0


def test_aether_bench_gpu_training_profile_records_combined_skip_report():
    with tempfile.TemporaryDirectory() as directory:
        args = parse_args([
            "--profile",
            "gpu-training",
            "--samples",
            "2",
            "--epochs",
            "1",
            "--height",
            "4",
            "--width",
            "4",
            "--resize",
            "4",
            "--batch-size",
            "1",
            "--warmup-steps",
            "0",
            "--trials",
            "1",
            "--output-dir",
            directory,
        ])

        summary = run_suite(args)
        report = json.loads((Path(directory) / "aetherml-report.json").read_text(encoding="utf-8"))

        assert summary["profile"] == "gpu-training"
        assert summary["configuration"]["runs"] == 3
        assert "benchmark_gpu_segmentation" in summary["reportData"]
        assert report["keyStats"]["gpuTraining"]["status"] in {"SKIPPED_UNSUPPORTED_ACCELERATOR", "PASSED"}
        if report["keyStats"]["gpuTraining"]["status"] == "SKIPPED_UNSUPPORTED_ACCELERATOR":
            assert not report["allPassed"]
        else:
            assert report["allPassed"]
        assert report["reports"][0]["command"][1].endswith("benchmark_gpu_segmentation.py")


def test_aether_bench_gpu_acceptance_profile_uses_bridge_defaults():
    args = parse_args(["--profile", "gpu-acceptance"])

    assert args.samples == 128
    assert args.repeat == 2
    assert args.runs == 3
    assert args.height == 128
    assert args.width == 128
    assert args.resize == 128
    assert args.batch_size == 8


def test_aether_bench_writes_gpu_sweep_plan_commands():
    with tempfile.TemporaryDirectory() as directory:
        args = parse_args([
            "--profile",
            "gpu-training",
            "--runs",
            "1",
            "--sweep-plan",
            "gpu-required",
            "--accelerator-backend",
            "cuda",
            "--expected-gpu",
            "NVIDIA",
            "--output-dir",
            directory,
        ])

        plan = build_sweep_plan(args, Path(directory))
        model_variant = next(variant for variant in plan["variants"] if variant["sweep"] == "model-size")
        changed_variant = next(variant for variant in plan["variants"] if variant["sweep"] == "changed-percent")
        worker_variant = next(variant for variant in plan["variants"] if variant["sweep"] == "worker")
        hit_ratio_variant = next(variant for variant in plan["variants"] if variant["sweep"] == "hit-ratio")
        prefetch_variant = next(variant for variant in plan["variants"] if variant["sweep"] == "prefetch")

        assert plan["status"] == "PLANNED"
        assert plan["variantCount"] == 28
        assert model_variant["status"] == "PLANNED_EXECUTABLE"
        assert "--model-tier" in model_variant["command"]
        assert model_variant["command"][model_variant["command"].index("--accelerator-backend") + 1] == "cuda"
        assert model_variant["command"][model_variant["command"].index("--expected-gpu") + 1] == "NVIDIA"
        assert changed_variant["status"] == "PLANNED_EXECUTABLE"
        assert "--prepopulate-previous-version" in changed_variant["command"]
        assert worker_variant["status"] == "PLANNED_UNSUPPORTED_UNTIL_FAIR_MULTIPROCESSING"
        assert hit_ratio_variant["status"] == "PLANNED_EXECUTABLE"
        assert "--initial-cache-hit-ratio" in hit_ratio_variant["command"]
        assert prefetch_variant["status"] == "PLANNED_EXECUTABLE"
        assert "--prefetch-batches" in prefetch_variant["command"]


def test_aether_bench_plan_only_writes_sweep_report_without_running_children():
    with tempfile.TemporaryDirectory() as directory:
        args = parse_args([
            "--profile",
            "smoke",
            "--sweep-plan",
            "gpu-required",
            "--plan-only",
            "--output-dir",
            directory,
        ])

        summary = run_suite(args)
        report = json.loads((Path(directory) / "aetherml-report.json").read_text(encoding="utf-8"))
        first_command = report["sweepPlan"]["variants"][0]["command"]

        assert summary["allPassed"]
        assert summary["reports"] == []
        assert report["configuration"]["planOnly"]
        assert report["sweepPlan"]["variantCount"] == 28
        assert first_command[first_command.index("--profile") + 1] == "gpu-training"


def test_gpu_segmentation_benchmark_records_run_configuration():
    args = parse_gpu_args([
        "--runs",
        "3",
        "--samples",
        "2",
        "--height",
        "4",
        "--width",
        "4",
        "--resize",
        "4",
        "--preprocess-passes",
        "4",
        "--initial-cache-hit-ratio",
        "75",
        "--prepopulate-previous-version",
        "--prefetch-batches",
        "2",
        "--gpu-sample-interval-ms",
        "100",
        "--accelerator-backend",
        "cuda",
        "--expected-gpu",
        "NVIDIA",
    ])
    config = gpu_configuration(args)

    assert args.runs == 3
    assert args.samples == 2
    assert args.accelerator_backend == "cuda"
    assert args.expected_gpu == "NVIDIA"
    assert args.preprocess_passes == 4
    assert args.initial_cache_hit_ratio == 75
    assert args.prepopulate_previous_version
    assert args.prefetch_batches == 2
    assert args.gpu_sample_interval_ms == 100
    assert config["acceleratorBackend"] == "cuda"
    assert config["expectedGpu"] == "NVIDIA"


def test_git_gpu_segmentation_persistent_cache_reuse_cycle():
    with tempfile.TemporaryDirectory() as directory:
        cache_root = Path(directory) / "aether-cache"
        args = parse_gpu_args([
            "--samples",
            "2",
            "--epochs",
            "1",
            "--height",
            "4",
            "--width",
            "4",
            "--resize",
            "4",
            "--batch-size",
            "1",
            "--warmup-steps",
            "0",
            "--aether-cache-dir",
            str(cache_root),
            "--aether-cache-mode",
            "fresh",
        ])
        sources = load_sources(args)
        reference = [artifact_to_tensor_sample(preprocess_sample(sample, args, __import__("numpy")), __import__("numpy")) for sample in sources]
        context = BackendContext(args, reference, sources, __import__("numpy"))
        context.populate_aether_dataset()
        expected = context.store.cached_artifact_ids([context.cache_key(index) for index in range(len(sources))])
        assert len(expected) == len(sources)

        args_reuse = parse_gpu_args([
            "--samples",
            "2",
            "--epochs",
            "1",
            "--height",
            "4",
            "--width",
            "4",
            "--resize",
            "4",
            "--batch-size",
            "1",
            "--warmup-steps",
            "0",
            "--aether-cache-dir",
            str(cache_root),
            "--aether-cache-mode",
            "reuse",
        ])
        reuse_sources = load_sources(args_reuse)
        reuse_reference = [artifact_to_tensor_sample(preprocess_sample(sample, args_reuse, __import__("numpy")), __import__("numpy")) for sample in reuse_sources]
        reuse_context = BackendContext(args_reuse, reuse_reference, reuse_sources, __import__("numpy"))
        reusable = reuse_context._existing_cache_entries()
        assert reusable == len(reuse_sources)
        assert reuse_context.target_initial_cache_hit_ratio > 0


def test_gpu_segmentation_backend_gate_accepts_cuda_and_rocm():
    cuda_accelerator = {
        "deviceAvailable": True,
        "backend": "cuda",
        "cudaVersion": "12.4",
        "hipVersion": None,
        "deviceName": "NVIDIA L4",
    }
    rocm_accelerator = {
        "deviceAvailable": True,
        "backend": "rocm",
        "cudaVersion": None,
        "hipVersion": "6.4",
        "deviceName": "AMD Radeon RX 7900 XT",
    }

    assert accelerator_backend_matches(cuda_accelerator, "cuda")
    assert not accelerator_backend_matches(cuda_accelerator, "rocm")
    assert accelerator_backend_matches(rocm_accelerator, "rocm")
    assert accelerator_backend_matches(cuda_accelerator, "auto")
    assert accelerator_backend_supported(cuda_accelerator, "cuda")
    assert expected_gpu_matches("NVIDIA L4", "NVIDIA")
    assert gpu_name_matches("NVIDIA L4", "")


def test_gpu_training_break_even_uses_midrange_epoch_points():
    result = training_break_even_epoch({
        "RAW_RECOMPUTE": {
            "steadyState": {"meanEpochMs": 1000},
            "lifecycle": {"populateMs": 0},
        },
        "AETHER_CACHE": {
            "steadyState": {"meanEpochMs": 900},
            "lifecycle": {"populateMs": 900},
        },
    })

    assert result["epoch"] == 15


def test_gpu_utilization_sampler_extracts_amd_smi_metrics():
    sampler = GpuUtilizationSampler(interval_ms=250)
    sample = sampler._sample_from_payload({
        "card0": {
            "GPU use (%)": "73%",
            "GPU Memory Allocated (VRAM%)": "41%",
        }
    })

    assert sample["gpuUtilizationPercent"] == 73
    assert sample["memoryUtilizationPercent"] == 41


def test_gpu_utilization_sampler_extracts_nvidia_smi_metrics():
    sampler = GpuUtilizationSampler(interval_ms=250)
    sample = sampler._sample_from_nvidia_smi("73, 41\n")

    assert sample["gpuUtilizationPercent"] == 73
    assert sample["memoryUtilizationPercent"] == 41


def test_evidence_audit_blocks_tiny_gpu_claims():
    summary = {
        "profile": "gpu-acceptance",
        "reports": [{"command": ["python", "benchmark_gpu_segmentation.py"]}],
        "reportData": {
            "benchmark_gpu_segmentation": {
                "profile": "gpu-acceptance",
                "numpy": {"available": True},
                "accelerator": {
                    "deviceAvailable": True,
                    "cudaVersion": None,
                    "hipVersion": "6.4",
                    "deviceName": "AMD Radeon RX 7900 XT",
                },
                "deviceSmokeTestPassed": True,
                "model": {"device": "cuda:0"},
                "configuration": {"samples": 8, "resize": 32, "measuredStepsEffective": 2, "acceleratorBackend": "rocm"},
                "backends": {
                    name: {
                        "gpu": {"sampling": {"statuses": ["UNAVAILABLE"]}},
                        "process": {"cpuUtilizationMean": 10, "rssPeakBytes": 1000, "diskReadBytes": 0, "diskWriteBytes": 0},
                    }
                    for name in ("RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY")
                },
                "protocol": {"singleGetRequests": 0, "singlePutRequests": 0},
                "validity": {"deterministicCacheInvalidation": {"passed": True}},
                "correctness": {
                    "allChecksumsEqual": True,
                    "backendEquivalence": {
                        name: {"sameShape": True, "sameDtype": True, "sameValues": True}
                        for name in ("RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY")
                    },
                },
                "runs": [
                    {
                        "backends": {
                            name: {"steps": [{"step": 0}]}
                            for name in ("RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY")
                        }
                    }
                ],
                "runAggregate": {"runs": 1},
                "cacheDynamics": {
                    "hitRatio": {"mean": 0.5},
                    "missRatio": {"mean": 0.5},
                    "recomputedSamples": {"mean": 4},
                    "publishedSamples": {"mean": 4},
                    "publishCostMs": {"mean": 1},
                    "lookupCostMs": {"mean": 1},
                },
                "comparisons": {"primary": {"aetherSamplesPerSecondRatio": 2.0}},
                "outcome": {
                    "statuses": {"TRAINING_THROUGHPUT_AND_TOTAL_WALL_IMPROVED": 1},
                    "samplesPerSecondRatio": {"mean": 2.0},
                    "flagPassRates": {"samplesPerSecondImproved": 1.0, "totalWallReduced": 1.0},
                },
            }
        },
    }

    audit = evidence_audit(summary)
    final_scale = next(check for check in audit["checks"] if check["name"] == "finalTrainingScale")
    cache_dynamics = next(check for check in audit["checks"] if check["name"] == "cacheDynamicsPresent")
    outcome = next(check for check in audit["checks"] if check["name"] == "outcomePresent")

    assert audit["status"] == "INCOMPLETE_FOR_GPU_TRAINING_CLAIM"
    assert not audit["trainingImprovementSupported"]
    assert not final_scale["passed"]
    assert cache_dynamics["passed"]
    assert outcome["passed"]


def test_tidy_gpu_exports_are_written_from_combined_report():
    with tempfile.TemporaryDirectory() as directory:
        output_dir = Path(directory)
        summary = {
            "profile": "gpu-training",
            "reportData": {
                "benchmark_gpu_segmentation": {
                    "backends": {
                        "AETHER_CACHE": {
                            "lifecycle": {"populateMs": 1, "trainingMs": 2, "totalMs": 3},
                            "steadyState": {
                                "samplesPerSecond": 4,
                                "stepsPerSecond": 5,
                                "pixelsPerSecond": 6,
                                "inputWaitPercent": 7,
                            },
                            "timing": {
                                "hostToDeviceMs": 8,
                                "prefetchWaitMs": 0.25,
                                "forwardMs": 9,
                                "backwardMs": 10,
                                "optimizerMs": 11,
                            },
                            "gpu": {
                                "utilizationMean": 30,
                                "utilizationP95": 40,
                                "memoryUtilizationMean": 20,
                                "sampling": {"statuses": ["SAMPLED"], "sources": ["test-source"]},
                            },
                            "process": {
                                "cpuUtilizationMean": 12,
                                "rssPeakBytes": 2048,
                                "diskReadBytes": 10,
                                "diskWriteBytes": 20,
                            },
                            "steps": [
                                {
                                    "epoch": 0,
                                    "step": 0,
                                    "batchSize": 1,
                                    "inputWaitMs": 1,
                                    "batchPrepareMs": 1,
                                    "prefetchWaitMs": 0.25,
                                    "hostToDeviceMs": 1,
                                    "forwardMs": 1,
                                    "lossMs": 1,
                                    "backwardMs": 1,
                                    "optimizerMs": 1,
                                    "stepWallMs": 5,
                                    "loss": 0.5,
                                    "tensorLayout": {
                                        "images": {"isContiguous": True, "stride": [16, 16, 4, 1], "isPinned": False, "storageOffset": 0, "memoryFormat": "contiguous", "dataPtrModulo64": 0, "dataPtrModulo256": 0},
                                        "masks": {"isContiguous": True, "stride": [16, 16, 4, 1], "isPinned": False, "storageOffset": 0, "memoryFormat": "contiguous", "dataPtrModulo64": 0, "dataPtrModulo256": 0},
                                    },
                                }
                            ],
                        }
                    },
                    "cacheDynamics": {
                        "hitRatio": {"mean": 0.5},
                        "missRatio": {"mean": 0.5},
                        "recomputedSamples": {"mean": 1},
                        "publishedSamples": {"mean": 1},
                        "publishCostMs": {"mean": 1},
                        "lookupCostMs": {"mean": 1},
                    },
                    "runs": [
                        {
                            "runIndex": 0,
                            "seed": 42,
                            "cacheDynamics": {
                                "configuredChangedPercent": 30,
                                "configuredInitialCacheHitRatio": 75,
                                "prepopulatePreviousVersion": True,
                                "targetInitialCacheHitRatio": 75,
                                "prepopulatedEntries": 3,
                                "lookups": 4,
                                "hits": 3,
                                "misses": 1,
                                "hitRatio": 0.75,
                                "missRatio": 0.25,
                                "recomputedSamples": 1,
                                "publishedSamples": 1,
                                "publishCostMs": 1.5,
                                "lookupCostMs": 0.5,
                                "bytesRead": 100,
                                "bytesWritten": 50,
                                "writeBatchCount": 1,
                                "entriesPerWriteBatchMean": 1,
                            },
                            "backends": {
                                "AETHER_CACHE": {
                                    "lifecycle": {"populateMs": 1, "trainingMs": 2, "totalMs": 3},
                                    "steadyState": {
                                        "samplesPerSecond": 4,
                                        "stepsPerSecond": 5,
                                        "pixelsPerSecond": 6,
                                        "inputWaitPercent": 7,
                                    },
                                    "gpu": {
                                        "utilizationMean": 30,
                                        "utilizationP95": 40,
                                    },
                                    "process": {
                                        "cpuUtilizationMean": 12,
                                        "rssPeakBytes": 2048,
                                        "diskReadBytes": 10,
                                        "diskWriteBytes": 20,
                                    },
                                    "steps": [
                                        {
                                            "epoch": 0,
                                            "step": 0,
                                            "batchSize": 1,
                                            "inputWaitMs": 1,
                                            "batchPrepareMs": 1,
                                            "prefetchWaitMs": 0.25,
                                            "hostToDeviceMs": 1,
                                            "forwardMs": 1,
                                            "lossMs": 1,
                                            "backwardMs": 1,
                                            "optimizerMs": 1,
                                            "stepWallMs": 5,
                                            "loss": 0.5,
                                            "tensorLayout": {
                                                "images": {"isContiguous": True, "stride": [16, 16, 4, 1], "isPinned": False, "storageOffset": 0, "memoryFormat": "contiguous", "dataPtrModulo64": 0, "dataPtrModulo256": 0},
                                                "masks": {"isContiguous": True, "stride": [16, 16, 4, 1], "isPinned": False, "storageOffset": 0, "memoryFormat": "contiguous", "dataPtrModulo64": 0, "dataPtrModulo256": 0},
                                            },
                                        }
                                    ],
                                }
                            },
                        }
                    ],
                }
            },
        }
        (output_dir / "summary.json").write_text("{}", encoding="utf-8")
        (output_dir / "aetherml-report.json").write_text("{}", encoding="utf-8")

        export_tidy_reports(summary, output_dir)

        assert (output_dir / "exports.json").exists()
        assert (output_dir / "tidy-gpu-backends.csv").exists()
        assert (output_dir / "tidy-gpu-runs.csv").exists()
        assert (output_dir / "tidy-gpu-steps.csv").exists()
        assert (output_dir / "tidy-gpu-cache-dynamics.csv").exists()
        backend_csv = (output_dir / "tidy-gpu-backends.csv").read_text(encoding="utf-8")
        step_csv = (output_dir / "tidy-gpu-steps.csv").read_text(encoding="utf-8")
        dynamics_csv = (output_dir / "tidy-gpu-cache-dynamics.csv").read_text(encoding="utf-8")
        assert "AETHER_CACHE" in backend_csv
        assert "prefetchWaitMsMean" in backend_csv
        assert "gpuUtilizationMean" in backend_csv
        assert "gpuSamplingStatus" in backend_csv
        assert "cpuUtilizationMean" in backend_csv
        assert "diskWriteBytes" in backend_csv
        assert "prefetchWaitMs" in step_csv
        assert "imageIsContiguous" in step_csv
        assert "maskStride" in step_csv
        assert "hitRatio" in dynamics_csv
        assert "publishedSamples" in dynamics_csv
