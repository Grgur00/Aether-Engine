from types import SimpleNamespace
import struct

import pytest

import hit_path_profile as hit


def test_distribution_interpolates_and_empty_is_unavailable():
    assert hit.distribution([]) is None
    result = hit.distribution([0, 100])
    assert result == {'mean': 50, 'median': 50, 'p95': 95, 'p99': 99, 'count': 2}


def test_throughput_uses_epoch_wall_not_sum_of_batch_durations():
    rows = [{'samples': 2, 'bytesReturned': 20, 'durationNs': 100},
            {'samples': 1, 'bytesReturned': 10, 'durationNs': 100}]
    result = hit.summarize(rows, 1000)
    assert result['samplesPerSecond'] == 3e6
    assert result['msPerSample']['mean'] == pytest.approx(.000075)
    assert hit.summarize(rows, 0)['samplesPerSecond'] is None


def test_jobs_are_complete_unique_and_seeded():
    plan = {'requestSizes': [1, 16], 'prefetchDepths': [0, 2], 'trainingBatchSize': 16}
    jobs = hit.jobs(plan, 42)
    assert jobs == hit.jobs(plan, 42)
    assert len(jobs) == len({job['id'] for job in jobs}) == 10
    assert all(job['requestSize'] == 16 for job in jobs if job['layer'] == 'full-input')


def test_binary_workload_uses_utf8_lengths_and_raw_digest(tmp_path):
    path = tmp_path / 'keys.bin'
    key = SimpleNamespace(namespace='n', sample_id='sample', transform=SimpleNamespace(digest=b'x' * 32))
    hit.write_workload(path, [key])
    expected = struct.pack('>II', 1, 1) + b'n' + struct.pack('>I', 6) + b'sample' + b'x' * 32
    assert path.read_bytes() == expected


def test_comparison_retains_negative_difference_and_suppresses_segment_java():
    def report(layer, cost, **extra):
        return {'job': {'backend': 'aether', 'layer': layer, 'requestSize': 16, 'prefetchDepth': 0},
                'summary': {'durationNs': {'mean': cost}}, **extra}

    rows = [report('java-database', 10), report('rpc-bytes', 8), report('full-input', 9)]
    assert hit.comparisons(rows, 16)['rpcMinusJavaMeanNs'] == -2
    rows[0]['segmentBackedEntries'] = 1
    result = hit.comparisons(rows, 16)
    assert result['rpcMinusJavaMeanNs'] is None
    assert result['fullMinusRpcMeanNs'] == 1


def test_completed_trace_with_retry_is_not_explicitly_rejected():
    info = {'state': 'IDLE', 'debtBytes': 0, **{key: 0 for key in hit.ACTIVITY}}
    trace = {'outcome': 'complete', 'events': [{'stage': 'attempt_failed'}]}
    hit.require_no_activity(info, info.copy(), traces=[trace])
