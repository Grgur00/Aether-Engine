import pytest

import profile_population as population


def test_layout_cases_keep_batch_fixed_and_change_only_target():
    rows = population.layout_cases()
    assert [row['targetSstableBytes'] for row in rows] == [size * 1024 ** 2 for size in (32, 64, 128)]
    assert all(row['lookupBatch'] == row['putBatch'] == 16 and not row['trace'] for row in rows)


def test_publication_chunks_restart_at_lookup_boundaries():
    assert population.expected_puts(10, 3, 2) == 7
    assert population.expected_puts(1200, 64, 32) == 38


def test_codec_records_failed_time_but_only_successful_bytes(monkeypatch):
    ticks = iter([10, 20, 30, 50, 60, 90])
    monkeypatch.setattr(population.time, 'perf_counter_ns', lambda: next(ticks))

    class Codec:
        def encode(self, value):
            if value is None:
                raise ValueError('encode')
            return b'abc'

        def decode(self, value):
            raise ValueError('decode')

    codec = population.TimedCodec(Codec())
    assert codec.encode(1) == b'abc'
    with pytest.raises(ValueError, match='encode'):
        codec.encode(None)
    with pytest.raises(ValueError, match='decode'):
        codec.decode(b'abc')
    assert (codec.bytes, codec.encode_ns, codec.decode_ns) == (3, 30, 30)


def test_worker_request_mode_bypasses_parent_manifest_verification(tmp_path, monkeypatch):
    import json
    request = tmp_path / 'request.json'
    request.write_text(json.dumps({'fixture': True}))
    output = tmp_path / 'worker.json'
    monkeypatch.setattr(population, 'run_case', lambda request: {'received': request})
    monkeypatch.setattr(population, 'verify', lambda *args: pytest.fail('parent gate entered'))
    population.main(['--request', str(request), '--output', str(output)])
    assert json.loads(output.read_text()) == {'received': {'fixture': True}}


def test_summary_omits_unknown_cases_and_reports_descriptive_medians():
    def report(name, cost):
        return {'case': {'name': name}, 'timingsMs': {'population': cost},
                'totalMs': cost + 5, 'sourceLoadAndPreprocessMs': 2}

    result = population.summarize([report('mmap', 10), report('mmap', 20), report('unknown', 1)])
    assert set(result['cases']) == {'mmap'}
    assert result['cases']['mmap']['medianPopulationMs'] == 15
    assert result['cases']['mmap']['medianTotalMs'] == 20
    assert result['measurementRole'] == 'population-only diagnostic; no confirmatory claim'
