import json

import pytest

from cache_workspace import payload_floor
from experiment_output import freeze_metadata


@pytest.mark.parametrize('dataset, expected', [('oct5k', 48), ('coco', 136),
                                              ('imagenet', 100), ('unknown', 100)])
def test_payload_floor_is_two_uncompressed_stores(dataset, expected):
    assert payload_floor(dataset, 2, 2, 10) == expected


def test_resume_compares_selected_identity_fields_not_all_provenance(tmp_path):
    provenance = {'measurementIdentity': {'host': 'fixture'}, 'sourceSha256': {'a': 'hash'},
                  'other': 'original'}
    freeze_metadata(tmp_path, {'epochs': 1}, 'fixture', provenance, False)
    freeze_metadata(tmp_path, {'epochs': 1}, 'fixture', {**provenance, 'other': 'changed'}, True)
    saved = json.loads((tmp_path / 'environment-fixture.json').read_text())
    assert saved['other'] == 'original'


def test_resume_without_recognized_measurements_can_initialize(tmp_path):
    (tmp_path / 'unrelated.json').write_text('{}')
    freeze_metadata(tmp_path, {}, 'fixture', {'measurementIdentity': {}, 'sourceSha256': {}}, True)
    assert (tmp_path / 'protocol.json').exists()


def test_metadata_pair_write_failure_leaves_orphan_rejected_on_retry(tmp_path, monkeypatch):
    import experiment_output as output
    original = output.write_json

    def write(path, value):
        if path.name == 'protocol.json':
            raise OSError('fixture write failure')
        original(path, value)

    monkeypatch.setattr(output, 'write_json', write)
    with pytest.raises(OSError, match='fixture'):
        output.freeze_metadata(tmp_path, {}, 'fixture', {}, False)
    assert (tmp_path / 'environment-fixture.json').exists()
    monkeypatch.setattr(output, 'write_json', original)
    with pytest.raises(ValueError, match='no frozen protocol'):
        output.freeze_metadata(tmp_path, {}, 'fixture', {}, True)
