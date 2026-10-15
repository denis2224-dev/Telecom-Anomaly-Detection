import json
import unittest
from pathlib import Path

import yaml
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate_spec


ROOT = Path(__file__).resolve().parents[1]


class TraceLinkApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.canonical = (ROOT / "contracts/openapi/incident-api.yaml").read_bytes()
        cls.spec = yaml.safe_load(cls.canonical)

    def test_openapi_and_dashboard_copy(self):
        validate_spec(self.spec)
        self.assertEqual(self.canonical, (ROOT / "apps/dashboard/api/incident-api.yaml").read_bytes())
        self.assertEqual("0.5.0", self.spec["info"]["version"])

    def test_trace_link_routes_are_declared(self):
        paths = self.spec["paths"]
        for route in (
            "/api/v2/inventory/cities",
            "/api/v2/inventory/links",
            "/api/v2/inventory/partners",
            "/api/v2/kpi-definitions",
            "/api/v2/metrics/series",
            "/api/v2/overview",
            "/api/v2/transport/snapshot",
            "/api/v2/roaming/snapshot",
            "/api/v2/exports/metrics.csv",
            "/api/notifications",
            "/api/notifications/{id}/read",
            "/api/notifications/push/config",
            "/api/notifications/push-subscriptions",
            "/api/notifications/push-subscriptions/{id}",
        ):
            self.assertIn(route, paths)

    def test_notification_examples(self):
        schema = self.spec["components"]["schemas"]["NotificationItem"]
        items = json.loads((ROOT / "contracts/fixtures/trace-link/notification-items-v1.json").read_text())
        self.assertEqual(["OPEN", "ESCALATED", "RECOVERED", "ASSIGNED", "OPEN"],
                         [item["kind"] for item in items])
        validator = OAS30Validator(schema)
        for item in items:
            validator.validate(item)
            self.assertTrue(item["incidentUrl"].startswith("/incidents/"))
            if item["causeStatus"] == "UNDETERMINED":
                self.assertIsNone(item["probableCause"])
                self.assertIsNone(item["causeConfidence"])


if __name__ == "__main__":
    unittest.main()
