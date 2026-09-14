"""Revision 3 reference boundary; intentionally no detector or finalizer."""

import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from observation_contract import (  # noqa: E402
    ROOT, ObservationBatch, patched, read_json, validate_observation,
)
from jsonschema import ValidationError

FIXTURES = ROOT / "contracts/fixtures/observations"


class ObservationContractTests(unittest.TestCase):
    def test_normal_degraded_missing_and_heartbeat(self):
        for path in FIXTURES.glob("*.json"):
            with self.subTest(fixture=path.name):
                validate_observation(read_json(path))

    def test_shared_validation_cases(self):
        for case in read_json(ROOT / "contracts/fixtures/validation/observation-cases-v2.json"):
            with self.subTest(case=case["name"]):
                event = patched(read_json(FIXTURES / case["base"]), case["patch"])
                if case["valid"]:
                    validate_observation(event)
                else:
                    with self.assertRaises((ValidationError, ValueError)):
                        validate_observation(event)

    def test_bounded_samples(self):
        event = read_json(FIXTURES / "normal-sms.json")
        event["metrics"].update(deliveryDelayMs=[1] * 10001, deliveredMessages=10001,
                                deliveryAttempts=10001, deliverySuccesses=10001)
        with self.assertRaises(ValidationError):
            validate_observation(event)

    def test_duplicate_and_conflicts_do_not_add_traffic(self):
        event = read_json(FIXTURES / "normal-volte.json")
        batch = ObservationBatch()
        self.assertEqual(batch.accept(event), "ACCEPTED")
        # Object key order and whitespace are not content differences.
        self.assertEqual(batch.accept(dict(reversed(list(event.items())))), "DUPLICATE")
        for change in [
            {"eventId": "00000000-0000-4000-8000-000000000001"},
            {"emittedAt": "2026-09-15T08:01:01Z"},
            {"windowStart": "2026-09-15T08:01:00Z", "windowEnd": "2026-09-15T08:02:00Z",
             "emittedAt": "2026-09-15T08:02:00Z"},
        ]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                batch.accept(dict(event, **change))
        degraded = read_json(FIXTURES / "degraded-volte.json")
        with self.assertRaises(ValueError):
            batch.accept(degraded)
        self.assertEqual(len(batch.by_interval), 1)
        self.assertEqual(batch.accept(event), "DUPLICATE")
        # Caller mutation must not change the stored receipt.
        event["metrics"]["sip503Count"] = 1
        with self.assertRaises(ValueError):
            batch.accept(event)

    def test_zero_completions_can_have_backlog(self):
        service = read_json(FIXTURES / "sms-no-completions.json")
        queue = read_json(FIXTURES / "degraded-smsc.json")
        batch = ObservationBatch()
        self.assertEqual(batch.accept(service), "ACCEPTED")
        self.assertEqual(batch.accept(queue), "ACCEPTED")
        self.assertEqual(service["metrics"]["deliveredMessages"], 0)
        self.assertEqual(queue["metrics"]["queueDepth"], 250)


if __name__ == "__main__":
    unittest.main()
