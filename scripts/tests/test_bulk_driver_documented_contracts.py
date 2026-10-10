import contextlib
import json
import sys

import pytest

import profile_background_compaction as background
import profile_bulk_jfr as recording


def test_candidate_smoke_cli_rejected_after_manifest_verification(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(recording, 'verify', lambda path: (calls.append(path) or {}, [tmp_path / 'v0.csv']))
    monkeypatch.setattr(recording, 'run', lambda *args: pytest.fail('worker must not launch'))
    monkeypatch.setattr(sys, 'argv', ['profile', '--output', str(tmp_path), '--candidate-only', '--smoke'])
    with pytest.raises(SystemExit) as error:
        recording.main()
    assert error.value.code == 2
    assert len(calls) == 1


def test_missing_jfr_tool_fails_before_loading_sources(tmp_path, monkeypatch):
    monkeypatch.setattr(recording.shutil, 'which', lambda name: None)
    monkeypatch.setattr(recording, 'workload_args', lambda *args: pytest.fail('preflight must not enter'))
    with pytest.raises(RuntimeError, match='jfr executable'):
        recording.run(tmp_path / 'output', tmp_path / 'manifest.csv')
    assert not (tmp_path / 'output').exists()


@pytest.mark.parametrize('completed', [0, 1])
def test_background_reopen_and_trace_evidence_boundary(tmp_path, monkeypatch, completed):
    output = tmp_path / 'output'
    values = {}
    starts = []

    @contextlib.contextmanager
    def daemon(path):
        starts.append(path)
        try:
            yield {'port': 1}
        finally:
            receipt = {'backgroundCompaction': {'completed': completed, 'failed': 0}}
            (output / f'store.compaction-{len(starts)}.json').write_text(json.dumps(receipt))

    class Client:
        def __init__(self, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def put_many(self, pairs):
            values.update(pairs)

        def get_many(self, keys):
            return {key: values[key] for key in keys}

    monkeypatch.setattr(background, 'java_daemon', daemon)
    monkeypatch.setattr(background, 'AetherTrainingCache', Client)
    monkeypatch.setattr(sys, 'argv', ['profile', '--output', str(output), '--samples', '17', '--payload-bytes', '4'])
    if not completed:
        with pytest.raises(RuntimeError, match='did not complete'):
            background.main()
        assert len(starts) == 1
        assert (output / 'requests.json').exists()
        assert not (output / 'summary.json').exists()
    else:
        background.main()
        report = json.loads((output / 'summary.json').read_text())
        assert len(starts) == 2 and starts[0] == starts[1]
        assert len(values) == 17
        assert report['reopenParityPassed'] is True
        assert report['foregroundCompactionCount'] == report['flushCount'] == 0
        assert json.loads((output / 'requests.json').read_text()) == []
