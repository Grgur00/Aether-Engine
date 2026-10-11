from h4.kaggle_storage_revision import remote


def test_cpu_candidate_runs_ordered_capacity_and_regression_gates():
    source = remote()
    compile("CONFIG = {}\n" + source, "candidate", "exec")
    assert "for gib in (1, 2, 4, 8)" in source
    assert "--heap-samples" in source
    assert "--prior-preflight" in source
    assert ":modules:aether-engine:test" in source
    assert "if code != 0:" in source
    assert "primary_h4_eligible=False, out_of_core_verified=False" in source
