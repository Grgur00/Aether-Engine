import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np
from scipy import stats
from statsmodels.stats.weightstats import ttost_paired
from statsmodels.stats.multitest import multipletests
from analyze import paired_analysis, holm


class StatisticalTests(unittest.TestCase):
    def test_matches_scipy_and_statsmodels(self):
        rng = np.random.default_rng(74)
        baseline = rng.uniform(80, 140, size=24)
        aether = baseline * np.exp(rng.normal(0, .01, size=24))
        report = paired_analysis(aether, baseline, resamples=1000)
        log_ratio = np.log(aether) - np.log(baseline)
        expected = stats.ttest_1samp(log_ratio, 0)
        self.assertAlmostEqual(report["pairedTTestTwoSidedP"], expected.pvalue)
        p, _, _ = ttost_paired(np.log(aether), np.log(baseline), np.log(.97), np.log(1.03))
        self.assertAlmostEqual(report["tost"]["p"], p)
        np.testing.assert_allclose(report["ratioCI95"], np.exp(expected.confidence_interval()))
        self.assertTrue(report["tost"]["equivalentUnadjusted"])

    def test_non_significance_does_not_imply_equivalence(self):
        values = np.exp(np.linspace(-.5, .5, 24))
        report = paired_analysis(values, np.ones(24), resamples=100)
        self.assertGreater(report["pairedTTestTwoSidedP"], .05)
        self.assertFalse(report["tost"]["equivalentUnadjusted"])

    def test_holm_matches_reference(self):
        p = [.03, .005, .2, .01]
        np.testing.assert_allclose(holm(p), multipletests(p, method="holm")[1])

    def test_invalid_pairs_rejected(self):
        for a, b in [([1], [1]), ([1, 2], [1]), ([1, 0], [1, 2]), ([1, float('nan')], [1, 2])]:
            with self.assertRaises(ValueError):
                paired_analysis(a, b)


if __name__ == "__main__":
    unittest.main()
