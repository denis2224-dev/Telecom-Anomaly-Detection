"""Metric denominators stay tied to untouched test labels."""

import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / "training/evaluate.py"
SPEC = importlib.util.spec_from_file_location("evaluate", PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EvaluationTests(unittest.TestCase):
    def test_summary_counts_and_false_positive_rate(self):
        result = MODULE.summary(["NORMAL", "NORMAL", "FAULT", "FAULT"],
                                [False, True, True, False], [1, 2, 3, 4])
        self.assertEqual((1, 1, 1, 1), tuple(result[key] for key in
                         ("truePositives", "falsePositives", "falseNegatives", "trueNegatives")))
        self.assertEqual(0.5, result["precision"])
        self.assertEqual(0.5, result["recall"])
        self.assertEqual(500, result["falsePositivesPer1000Normal"])


if __name__ == "__main__":
    unittest.main()
