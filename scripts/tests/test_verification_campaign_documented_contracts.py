import pytest

import profile_bulk_verification as diagnostic
from test_bulk_verification import report, streaming_report


def test_population_residual_can_be_negative_without_invalid_population():
    row = report('candidate', 0, 1)
    diagnostic.validate_report(row, 'candidate', 'reference')
    summary = diagnostic.summarize([row], 3)
    assert summary['arms']['candidate']['storageOverheadExcludingPreprocessingMs']['median'] == -10999
    assert not summary['complete']
    assert summary['medianImprovementMs'] is None


def test_baseline_per_table_check_is_vacuous_for_empty_list():
    row = report('baseline', 0)
    row['bulkCommit']['storage']['sstableFinishes'] = []
    diagnostic.validate_report(row, 'baseline', 'reference')


def test_candidate_verification_equality_rejects_additional_fields():
    row = report('candidate', 0)
    row['bulkCommit']['storage']['verification']['extra'] = 1
    with pytest.raises(ValueError, match='exactly once'):
        diagnostic.validate_report(row, 'candidate', 'reference')


def test_streaming_direct_summary_does_not_enforce_five_pairs():
    rows = [streaming_report(arm, 0, 100 if arm == 'baseline' else 75)
            for arm in ('baseline', 'candidate')]
    result = diagnostic.summarize_streaming(rows, 1)
    assert result['complete'] and result['performanceGatePassed']
    assert result['candidateToBaselineMedianRatio'] == .75
    assert 'thresholdMs' not in result and 'medianImprovementMs' not in result


def test_streaming_secondary_metrics_are_positive_not_finite_checked():
    row = streaming_report('candidate', 0, 75)
    metrics = row['bulkCommit']['storage']['streamingVerification']
    for field in ('logicalValueBytes', 'bytesRead', 'elapsedNs'):
        metrics[field] = float('inf')
    diagnostic.validate_streaming_report(row, 'candidate', 'reference')
