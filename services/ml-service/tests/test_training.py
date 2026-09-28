"""Focused checks for the synthetic training handoff and packaged scorer."""

import json
import math
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / "services/ml-service"), str(ROOT / "services/ml-service/training")]
from app.inference.scoring import load, score
from generate_history import START, make_window

MODELS = ROOT / "services/ml-service/models"
SPLIT = ROOT / "services/ml-service/training/split_manifest.json"


class TrainingTests(unittest.TestCase):
    def test_generation_is_deterministic_valid_and_faults_are_distinct(self):
        for service in ("VOLTE", "SMS"):
            normal = make_window(service, START, 100)
            self.assertEqual(normal, make_window(service, START, 100))
            self.assertNotEqual(normal["featureValues"], make_window(service, START, 101)["featureValues"])
            fault = make_window(service, START, 100, fault=True)
            self.assertTrue(normal["mlEligible"] and fault["mlEligible"])
            self.assertNotEqual(normal["featureValues"], fault["featureValues"])

    def test_split_manifest_has_four_training_weeks_and_no_overlap(self):
        manifest = json.loads(SPLIT.read_text(encoding="utf-8"))
        for service in ("VOLTE", "SMS"):
            runs = [r for r in manifest["runs"] if r["service"] == service]
            self.assertEqual([r["split"] for r in runs], ["train"] * 4 + ["calibration", "test", "test"])
            self.assertEqual(len({r["seed"] for r in runs}), 7)
            self.assertTrue(all(a["endExclusive"] <= b["start"] for a, b in zip(runs, runs[1:])))
            self.assertEqual(sum(r["rows"] for r in runs[:4]), 4 * 7 * 24 * 12)

    def test_packaged_models_score_and_reject_bad_inputs_or_artifacts(self):
        for service in ("VOLTE", "SMS"):
            loaded = load(service)
            window = make_window(service, START, 100)
            result = score(window, loaded)
            self.assertEqual(result["mlStatus"], "OK")
            self.assertTrue(math.isfinite(result["anomalyRank"]))
            self.assertGreaterEqual(result["anomalyRank"], 0)
            self.assertLessEqual(result["anomalyRank"], 1)
            self.assertGreater(score(make_window(service, START, 100, fault=True), loaded)["anomalyRank"],
                               result["anomalyRank"])
            bad = dict(window, featureNames=list(reversed(window["featureNames"])))
            with self.assertRaises(ValueError):
                score(bad, loaded)
            bad = dict(window, featureValues=[float("nan")] * 6)
            with self.assertRaises(ValueError):
                score(bad, loaded)
            other = "SMS" if service == "VOLTE" else "VOLTE"
            with self.assertRaises(ValueError):
                score(make_window(other, START, 100), loaded)
            with tempfile.TemporaryDirectory() as directory:
                for name in ("manifest.json", service + ".joblib", service + "-calibration.json"):
                    shutil.copyfile(MODELS / name, Path(directory) / name)
                with (Path(directory) / (service + ".joblib")).open("ab") as stream:
                    stream.write(b"corrupt")
                with self.assertRaises(ValueError):
                    load(service, directory)


if __name__ == "__main__":
    unittest.main()
