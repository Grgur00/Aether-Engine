import contextlib
import json

import pytest

import profile_cache_jfr as recording
import profile_cache_requests as requests


@pytest.mark.parametrize('kwargs', [dict(windows=0), dict(operations=0),
                                    dict(payload_bytes=0), dict(batch_size=4097),
                                    dict(payload_bytes=64 * 1024 ** 2)])
def test_request_validation_precedes_output_creation(tmp_path, kwargs):
    output = tmp_path / 'profile'
    with pytest.raises(ValueError):
        requests.profile(output, **kwargs)
    assert not output.exists()


@pytest.mark.parametrize('durations', [(0, 1, 1), (1, -1, 1), (1, 1, 0)])
def test_jfr_duration_validation_precedes_output_creation(tmp_path, durations):
    output = tmp_path / 'jfr'
    with pytest.raises(ValueError):
        recording.run(output, *durations)
    assert not output.exists()


def test_missing_jfr_tools_leave_created_directory(tmp_path, monkeypatch):
    monkeypatch.setattr(recording.shutil, 'which', lambda name: None)
    output = tmp_path / 'jfr'
    with pytest.raises(RuntimeError, match='jcmd and jfr'):
        recording.run(output, 1, 1, 1)
    assert output.is_dir()
    assert not (output / 'runs.json').exists()


def test_request_report_separates_trace_warmup_from_protocol_counters(tmp_path, monkeypatch):
    store = {}
    connections = []

    class Client:
        def __init__(self, *, port, trace_sink=None, server_trace=False):
            self.sink = trace_sink
            self.server_trace = server_trace
            self.calls = 0
            self.trace_errors = []
            connections.append(self)

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def record(self):
            self.calls += 1
            if self.sink:
                self.sink({'fixture': True, 'serverTrace': self.server_trace})

        def put_many(self, values):
            self.record()
            store.update(values)

        def get_many(self, keys):
            self.record()
            return {key: store[key] for key in keys if key in store}

        def protocol_metrics(self):
            return {'fixtureCalls': self.calls}

    @contextlib.contextmanager
    def daemon(path):
        yield {'port': 1}

    monkeypatch.setattr(requests, 'java_daemon', daemon)
    monkeypatch.setattr(requests, 'AetherTrainingCache', Client)
    monkeypatch.setattr(requests, 'environment', lambda: {'fixture': True})
    output = tmp_path / 'requests'
    requests.profile(output, windows=2, operations=2, payload_bytes=4, batch_size=2,
                     server_trace=True)
    report = json.loads((output / 'profile.json').read_text())
    rows = report['rows']
    assert len(rows) == 12
    assert len(connections) == 13
    assert all(row['protocol']['fixtureCalls'] == 5 for row in rows)
    assert all(row['traceCount'] == (2 if row['instrumented'] else 0) for row in rows)
    assert len((output / 'traces.jsonl').read_text().splitlines()) == 12
    assert len(store) == 2 + 2 * 2 * 5 * 2
    assert report['stageAComplete'] is False
    assert all(len(value['pairedWindowWallRatios']) == 2
               for value in report['instrumentationOverhead'].values())
