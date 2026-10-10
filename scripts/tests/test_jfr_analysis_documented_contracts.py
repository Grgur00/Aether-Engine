import hashlib
import json

import pytest

import analyze_cache_jfr as cache
from bulk_jfr_analyze import seconds, verification_detail


@pytest.mark.parametrize('value, expected', [(None, 0), ('', 0), ('PT', 0),
                                           ('PT1H2M3.5S', 3723.5), (-2, -2), (True, 1)])
def test_bulk_duration_parser_boundaries(value, expected):
    assert seconds(value) == expected


def test_verifier_boundary_counts_full_weight_and_global_gc():
    def event(kind, offset, **values):
        return {'type': kind, 'values': {'startTime': f'2026-10-01T00:00:0{offset}Z', **values}}

    marker = event('aether.BulkPhase', 1, phase='SSTABLE_VERIFY', duration='PT1S',
                   eventThread={'javaThreadId': 7})
    allocation = event('jdk.ObjectAllocationSample', 2, eventThread={'javaThreadId': 7},
                       weight=100, objectClass={'name': '[B'})
    unrelated = event('jdk.ObjectAllocationSample', 1, eventThread={'javaThreadId': 8},
                      weight=999, objectClass={'name': '[B'})
    gc = event('jdk.GarbageCollection', 0, duration='PT3S', eventThread={'javaThreadId': 8})
    detail = verification_detail([marker, allocation, unrelated, gc])
    assert detail['weightedAllocationBytes'] == {'[B': 100}
    assert detail['eventCounts']['jdk.ObjectAllocationSample'] == 1
    assert detail['eventOverlapSeconds']['jdk.ObjectAllocationSample'] == 0
    assert detail['eventOverlapSeconds']['jdk.GarbageCollection'] == 1


def test_cache_first_unknown_thread_sample_is_sensitivity_not_raw_removal(tmp_path):
    recording = tmp_path / 'recording.jfr'
    recording.write_bytes(b'fixture')
    case = {'recording': recording.name, 'sha256': hashlib.sha256(b'fixture').hexdigest(),
            'case': 'fixture', 'baseline': {}, 'recorded': {}}
    events = [{'type': 'jdk.ObjectAllocationSample',
               'values': {'weight': weight, 'startTime': '2026-10-01T00:00:00Z',
                          'objectClass': {'name': '[B'}}} for weight in (10, 30)]
    (tmp_path / 'events.json').write_text(json.dumps({'recording': {'events': events}}))
    assert cache.summarize(tmp_path, case) is None
    result = json.loads((tmp_path / 'analysis-summary.json').read_text())
    assert result['excludedFirstSampleWeightBytes'] == 10
    assert result['postFirstAllocationWeightBytes'] == 30
    assert result['allocationFirstAetherFrameRaw'] == [['<none>', 40]]
    assert len(result['firstAllocationSamples']) == 1


def test_cache_recording_hash_gate_precedes_export_read(tmp_path):
    (tmp_path / 'recording.jfr').write_bytes(b'fixture')
    with pytest.raises(ValueError, match='checksum mismatch'):
        cache.summarize(tmp_path, {'recording': 'recording.jfr', 'sha256': 'wrong'})
    assert not (tmp_path / 'analysis-summary.json').exists()


def test_cache_rejects_hour_minute_duration_even_though_bulk_supports_it(tmp_path):
    recording = tmp_path / 'recording.jfr'
    recording.write_bytes(b'fixture')
    (tmp_path / 'events.json').write_text(json.dumps({'recording': {'events': [
        {'type': 'jdk.ThreadPark', 'values': {'duration': 'PT1M1S'}}]}}))
    with pytest.raises(ValueError):
        cache.summarize(tmp_path, {'recording': recording.name,
                                 'sha256': hashlib.sha256(b'fixture').hexdigest()})
    assert not (tmp_path / 'analysis-summary.json').exists()
