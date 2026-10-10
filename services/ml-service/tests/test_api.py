"""HTTP contract for the private packaged-model endpoint."""

import math
from pathlib import Path
import sys
import unittest

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / "services/ml-service"), str(ROOT / "services/ml-service/training")]
from app.inference.api import app
from generate_history import START, make_window


class InferenceApiTests(unittest.TestCase):
    def test_valid_and_ineligible_windows(self):
        with TestClient(app) as client:
            for service in ("VOLTE", "SMS"):
                window = make_window(service, START, 100)
                response = client.post("/internal/inference", json=window)
                self.assertEqual(200, response.status_code)
                result = response.json()
                self.assertEqual("OK", result["mlStatus"])
                self.assertTrue(math.isfinite(result["anomalyRank"]))
                self.assertEqual("isoforest-v2-synthetic-1", result["modelVersion"])
                for bad in (dict(window, featureNames=list(reversed(window["featureNames"]))),
                            dict(window, baselineVersion='baseline-v2-changed'),
                            dict(window, mlEligible=False, featureNames=[], featureValues=[]),
                            dict(window, service=[service])):
                    response = client.post("/internal/inference", json=bad)
                    self.assertEqual(422, response.status_code)
                    self.assertEqual({"mlStatus": "INSUFFICIENT_DATA", "modelVersion": None,
                                      "anomalyRank": None}, response.json())
            self.assertEqual(200, client.get("/health/ready").status_code)


if __name__ == "__main__":
    unittest.main()
