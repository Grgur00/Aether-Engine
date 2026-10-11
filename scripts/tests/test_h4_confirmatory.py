import copy

import numpy as np
import pytest
from scipy import stats

from h4.confirmatory import confirmatory_test, make_design, validate_design
from h4.prepare_pcam_manifest import canonical_hash


def pilot_fixture():
    # Policy unit fixture only; does not constitute runtime resource evidence.
    return dict(phase="exploratory", excluded=[], archives=[], identity={key: str(index) * 64 for index, key in enumerate(
                ("protocol_sha256", "manifest_sha256", "dataset_sha256", "source_sha256", "host_class_sha256"))},
                binding=dict(protocol=dict(memory_limit_bytes=2 * 1024 ** 3), manifest=dict(official_samples=262144)),
                blocks=[dict(condition=4, block_id=index, block_record_sha256=str(index + 5) * 64,
                             results={backend: dict(patterns=dict(shuffled=dict(out_of_core_resource_evidence_verified=True)))
                                      for backend in ("aether", "mmap")}) for index in range(5)])


def test_design_requires_complete_qualified_pilot_and_even_balanced_schedule():
    loaded = pilot_fixture()
    design = make_design(loaded, sample_size=24, claim="noninferiority", null_ratio=.95)
    assert validate_design(design) == design
    assert len(design["block_ids"]) == 24
    assert sum(order[0] == "aether" for order in design["ordering"].values()) == 12
    for size in (0, 5, 7, 24.0):
        with pytest.raises(ValueError, match="even"):
            make_design(loaded, sample_size=size, claim="superiority", null_ratio=1)
    loaded["blocks"][0]["results"]["aether"]["patterns"]["shuffled"]["out_of_core_resource_evidence_verified"] = False
    with pytest.raises(ValueError, match="out-of-core"):
        make_design(loaded, sample_size=24, claim="superiority", null_ratio=1)


def test_failed_pilot_or_missing_block_cannot_be_frozen():
    loaded = pilot_fixture()
    loaded["excluded"] = [dict(block_id=0)]
    with pytest.raises(ValueError, match="five complete"):
        make_design(loaded, sample_size=24, claim="superiority", null_ratio=1)
    loaded = pilot_fixture()
    loaded["blocks"].pop()
    with pytest.raises(ValueError):
        make_design(loaded, sample_size=24, claim="superiority", null_ratio=1)


def test_test_matches_scipy_and_refuses_incomplete_or_degenerate_samples():
    design = make_design(pilot_fixture(), sample_size=6, claim="noninferiority", null_ratio=.95)
    ratios = [.98, 1.01, .99, 1.02, 1.03, 1.00]
    result = confirmatory_test(ratios, design)
    expected = stats.ttest_1samp(np.log(ratios), np.log(.95), alternative="greater")
    assert result["executed"]
    assert result["statistic"] == pytest.approx(expected.statistic)
    assert result["pvalue"] == pytest.approx(expected.pvalue)
    assert result["degrees_of_freedom"] == 5
    assert not confirmatory_test(ratios[:-1], design)["executed"]
    assert not confirmatory_test([1.0] * 6, design)["executed"]


def test_checksum_and_post_hoc_margin_or_order_changes_fail_closed():
    design = make_design(pilot_fixture(), sample_size=6, claim="superiority", null_ratio=1)
    changed = copy.deepcopy(design)
    changed["null_ratio"] = .95
    with pytest.raises(ValueError, match="checksum"):
        validate_design(changed)
    changed["sha256"] = canonical_hash({key: value for key, value in changed.items() if key != "sha256"})
    with pytest.raises(ValueError, match="superiority"):
        validate_design(changed)
    changed = copy.deepcopy(design)
    changed["ordering"]["1"] = ["aether", "mmap"]
    changed["sha256"] = canonical_hash({key: value for key, value in changed.items() if key != "sha256"})
    with pytest.raises(ValueError, match="balance"):
        validate_design(changed)
