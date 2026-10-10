"""Tiny local input/codec contracts, not research performance measurements."""
import csv
import hashlib
import json
import struct
from types import SimpleNamespace

import numpy as np
from PIL import Image, UnidentifiedImageError
import pytest

import benchmark_gpu_segmentation as workload


def write_rows(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


@pytest.fixture
def inputs(tmp_path):
    rows = []
    for index in range(2):
        image, mask = tmp_path / f"image-{index}.png", tmp_path / f"mask-{index}.png"
        Image.fromarray(np.arange(16, dtype=np.uint8).reshape(4, 4) + index).save(image)
        Image.fromarray(np.array([[0, 1, 2, 0]] * 4, dtype=np.uint8)).save(mask)
        a, b = workload.file_sha256(image), workload.file_sha256(mask)
        rows.append(dict(sample_id=str(index), image_path=image.name, mask_path=mask.name,
                         disease="fixture", image_sha256=a, mask_sha256=b,
                         source_identity=hashlib.sha256(f"{a}:{b}".encode()).hexdigest(), split="train"))
    manifest = tmp_path / "manifest.csv"
    write_rows(manifest, rows)
    args = workload.parse_args(["--dataset-kind", "oct5k", "--dataset-manifest", str(manifest),
                               "--samples", "2", "--resize", "4"])
    return manifest, rows, args


def test_manifest_hash_trust_still_checks_identity_and_existence(inputs):
    manifest, rows, args = inputs
    assert len(workload.load_sources(args)) == 2
    image = manifest.parent / rows[0]["image_path"]
    image.write_bytes(b"no longer the declared image")
    with pytest.raises(ValueError, match="image_sha256 mismatch"):
        workload.load_sources(args)
    args.trust_manifest_hashes = True
    sources = workload.load_sources(args)
    with pytest.raises(UnidentifiedImageError):
        workload.preprocess_sample(sources[0], args, np)
    rows[0]["source_identity"] = "wrong"
    write_rows(manifest, rows)
    with pytest.raises(ValueError, match="source_identity mismatch"):
        workload.load_sources(args)
    rows[0]["source_identity"] = sources[0]["source_identity"]
    write_rows(manifest, rows)
    image.unlink()
    with pytest.raises(ValueError, match="does not exist"):
        workload.load_sources(args)


def test_only_selected_rows_checked_and_empty_split_included(inputs):
    manifest, rows, args = inputs
    rows[0]["split"] = ""
    rows[1].update(split="train", sample_id="", source_identity="wrong")
    write_rows(manifest, rows)
    args.samples = 1
    assert workload.load_sources(args)[0]["sample_id"] == "0"
    args.samples = 2
    with pytest.raises(ValueError, match="empty sample_id"):
        workload.load_sources(args)
    rows[1]["split"] = "validation"
    write_rows(manifest, rows)
    with pytest.raises(ValueError, match="fewer than"):
        workload.load_sources(args)


def test_selected_id_and_content_duplicates_rejected(inputs):
    manifest, rows, args = inputs
    rows[1]["sample_id"] = " 0 "
    write_rows(manifest, rows)
    with pytest.raises(ValueError, match="duplicate sample_id"):
        workload.load_sources(args)
    rows[1] = dict(rows[0], sample_id="different")
    write_rows(manifest, rows)
    with pytest.raises(ValueError, match="duplicate OCT5K source identities"):
        workload.load_sources(args)


def test_manifest_resolution_normalizes_but_does_not_enforce_containment(tmp_path):
    root = tmp_path / "manifests"
    root.mkdir()
    assert workload.resolve_manifest_path("../outside.png", root) == tmp_path / "outside.png"


@pytest.mark.parametrize("mode", ["1", "L", "P", "I", "I;16"])
def test_supported_semantic_modes(mode):
    workload.validate_semantic_mask_mode(mode, "fixture")


@pytest.mark.parametrize("mode", ["RGB", "RGBA", "CMYK", "HSV", "F"])
def test_unsupported_semantic_modes(mode):
    with pytest.raises(ValueError):
        workload.validate_semantic_mask_mode(mode, "fixture")


def test_oct_preprocessing_is_binary_and_normalization_changes_only_image(inputs):
    _, _, args = inputs
    source = workload.load_sources(args)[0]
    initial, timing = workload.preprocess_sample_with_timing(source, args, np)
    assert set(np.unique(initial["mask"])) == {0., 1.}
    assert initial["image"].shape == initial["mask"].shape == (1, 4, 4)
    assert initial["artifactCodec"] == "none"
    args.normalization_scale, args.normalization_offset = 2., -1.
    changed = workload.preprocess_sample(source, args, np)
    np.testing.assert_array_equal(changed["mask"], initial["mask"])
    np.testing.assert_allclose(changed["image"], initial["image"] * 2 - 1)
    assert timing["sourceLoadMs"] >= 0 and timing["preprocessMs"] >= 0


def test_nearest_resize_and_denoise_wraparound_without_input_mutation():
    source = np.array([[1., 0.], [0., 0.]], dtype=np.float32)
    original = source.copy()
    resized = workload.nearest_resize(source, 4, np)
    np.testing.assert_array_equal(resized[:2, :2], np.ones((2, 2)))
    denoised = workload.deterministic_denoise_pass(source, np)
    np.testing.assert_array_equal(denoised, [[.5, .25], [.25, 0.]])
    np.testing.assert_array_equal(source, original)
    assert denoised.dtype == np.float32


def sample(codec="none"):
    return dict(sample_id="fixture", artifactCodec=codec,
                image=np.array([.333333, 1.1, -2.3, 0.], dtype=np.float32).reshape(1, 2, 2),
                mask=np.array([-1., 0., 2., 7.], dtype=np.float32).reshape(1, 2, 2))


@pytest.mark.parametrize("codec", ["none", "zlib"])
def test_payload_quantization_binary_masks_and_float32_copy(codec):
    source = sample(codec)
    payload = bytearray(workload.pack_payload(source))
    decoded = workload.unpack_payload(payload, np)
    np.testing.assert_array_equal(decoded["image"], source["image"].astype(np.float16).astype(np.float32))
    np.testing.assert_array_equal(decoded["mask"], (source["mask"] > 0).astype(np.float32))
    assert decoded["image"].dtype == decoded["mask"].dtype == np.float32
    expected = decoded["image"].copy()
    payload[-1] ^= 1
    np.testing.assert_array_equal(decoded["image"], expected)
    canonical = workload.artifact_to_tensor_sample(source, np)
    np.testing.assert_array_equal(canonical["image"], decoded["image"])


@pytest.mark.parametrize("payload", [b"", b"abc", struct.pack("<I", 100) + b"{}"])
def test_payload_header_bounds(payload):
    with pytest.raises(ValueError):
        workload.unpack_payload(payload, np)


@pytest.mark.parametrize("codec", ["none", "zlib"])
def test_trailing_or_truncated_body_rejected(codec):
    payload = workload.pack_payload(sample(codec))
    for altered in [payload[:-1], payload + b"trailing"]:
        with pytest.raises((ValueError, __import__("zlib").error)):
            workload.unpack_payload(altered, np)


def test_legacy_float32_payload_and_encoding_tag_are_not_schema_guard():
    source = sample()
    header = json.dumps(dict(sample_id="legacy", imageShape=[1, 2, 2], maskShape=[1, 2, 2],
                             artifactEncodingVersion="not-current")).encode()
    payload = struct.pack("<I", len(header)) + header + source["image"].tobytes() + source["mask"].tobytes()
    decoded = workload.unpack_payload(payload, np)
    np.testing.assert_array_equal(decoded["image"], source["image"])
    np.testing.assert_array_equal(decoded["mask"], source["mask"])
    assert decoded["artifactCodec"] == "none"


def test_unsupported_codec_and_shape_counter_limits():
    with pytest.raises(ValueError, match="unsupported"):
        workload.pack_payload(sample("unknown"))
    assert workload.element_count([]) == 1
    assert workload.element_count([2, 0, 3]) == 0
    assert workload.element_count(["2", -3]) == -6
    assert workload.element_count([2.9]) == 2


def test_checksums_include_order_ids_but_not_shape_metadata():
    a = sample()
    b = dict(a, sample_id="other")
    assert workload.dataset_checksums([a, b]) != workload.dataset_checksums([b, a])
    reshaped = dict(a, image=a["image"].reshape(4), mask=a["mask"].reshape(4))
    assert workload.dataset_checksums([a]) == workload.dataset_checksums([reshaped])
    workload.assert_equivalent_inputs([a], workload.dataset_checksums([reshaped]))
    with pytest.raises(RuntimeError, match="checksum mismatch"):
        workload.assert_equivalent_inputs([a], workload.dataset_checksums([b]))


def test_integrity_summary_passed_does_not_require_unique_content_identity():
    args = workload.parse_args(["--samples", "2"])
    sources = [dict(sample_id="a", source_identity="same"), dict(sample_id="b", source_identity="same")]
    result = workload.dataset_integrity_summary(args, sources)
    assert result["passed"] and result["sourceIdentityCollisions"] == 1
    assert result["manifestHashesVerified"] is False


def test_batch_plan_step_count_and_descriptor_separate_from_readable_version():
    args = workload.parse_args(["--samples", "5", "--batch-size", "2", "--epochs", "3"])
    assert list(workload.batch_plan(args)) == [[0, 1], [2, 3], [4]]
    assert workload.effective_measured_steps(args) == 9
    label = workload.dataset_version(args)
    descriptor = workload.deterministic_parameters(args)
    args.normalization_scale = 2.
    args.artifact_codec = "zlib"
    assert workload.dataset_version(args) == label
    assert workload.deterministic_parameters(args) != descriptor
    args.measured_steps = 2
    assert workload.effective_measured_steps(args) == 2
    args.measured_steps = -1
    assert workload.effective_measured_steps(args) == -1


def test_storage_labels_and_configuration_are_descriptive(monkeypatch):
    args = workload.parse_args(["--samples", "2", "--aether-cache-dir", "actual-cache"])
    monkeypatch.setattr(workload, "filesystem_name", lambda path: "fixture filesystem")
    locations = workload.storage_locations(args)
    assert locations["aetherPath"] == "temporary-per-run"
    assert locations["filesystem"] == "fixture filesystem"
    description = workload.configuration(args)
    assert description["aetherCacheDir"] == "actual-cache" and description["aetherPort"] is None
    assert description["numClasses"] is None


def test_filesystem_probe_generic_posix_and_windows_english_output(monkeypatch):
    from pathlib import Path
    path = Path.cwd()
    monkeypatch.setattr(workload, "os", SimpleNamespace(name="posix"))
    assert workload.filesystem_name(path) == "posix"
    workload.os.name = "nt"
    calls = []
    def run(arguments, **kwargs):
        calls.append((arguments, kwargs))
        return SimpleNamespace(returncode=1, stdout="File System Name : NTFS\n")
    monkeypatch.setattr(workload.subprocess, "run", run)
    assert workload.filesystem_name(path) == "NTFS"
    assert "timeout" not in calls[0][1]
