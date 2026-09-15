"""Checks for the EventV1 schemas and examples."""

import copy
from datetime import datetime
import json
import math
from pathlib import Path
import re
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts" / "events" / "v1"
EVENT_TYPES = {"CALL", "SMS", "DATA", "AUTH", "NETWORK"}
FORMAT_CHECKER = FormatChecker()


@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def valid_calendar_time(value):
    """Reject dates such as 30 February that match the timestamp pattern."""
    if isinstance(value, str):
        datetime.fromisoformat(value)
    return True


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON property: {key}")
        result[key] = value
    return result


def reject_non_json_number(value):
    raise ValueError(f"Not a JSON number: {value}")


def read_json(path):
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=reject_non_json_number,
    )


class EventContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = {
            path.name: read_json(path)
            for path in sorted(CONTRACTS.glob("*.schema.json"))
        }
        cls.examples = {
            path.name: read_json(path)
            for path in sorted((CONTRACTS / "examples").glob("*.json"))
        }
        # Resolve schema references offline.
        registry = Registry().with_resources(
            (schema["$id"], Resource.from_contents(schema))
            for schema in cls.schemas.values()
        )
        cls.validator = Draft202012Validator(
            cls.schemas["event.schema.json"],
            registry=registry,
            format_checker=FORMAT_CHECKER,
        )

    def example(self, name):
        return copy.deepcopy(self.examples[name])

    def assert_valid(self, event):
        errors = list(self.validator.iter_errors(event))
        self.assertFalse(errors, "\n".join(error.message for error in errors))

    def assert_invalid(self, event):
        self.assertTrue(list(self.validator.iter_errors(event)), event)

    def test_all_json_parses_and_all_schemas_are_valid(self):
        for path in ROOT.rglob("*.json"):
            if ".git" in path.parts or ".venv" in path.parts:
                continue
            with self.subTest(path=path.relative_to(ROOT)):
                read_json(path)
        expected = {f"{name.lower()}.schema.json" for name in EVENT_TYPES}
        self.assertEqual(set(self.schemas), expected | {"event.schema.json"})
        self.assertEqual(len({s["$id"] for s in self.schemas.values()}), 6)
        for name, schema in self.schemas.items():
            with self.subTest(schema=name):
                Draft202012Validator.check_schema(schema)

    def test_examples_cover_every_type_and_validate(self):
        self.assertEqual({e["eventType"] for e in self.examples.values()}, EVENT_TYPES)
        self.assertIn("normal-call.json", self.examples)
        self.assertIn("network-link-cut.json", self.examples)
        for name, event in self.examples.items():
            with self.subTest(example=name):
                self.assert_valid(event)

    def test_event_ids_are_distinct(self):
        ids = [event["eventId"] for event in self.examples.values()]
        self.assertEqual(len(ids), len(set(ids)))

    def test_required_fields_and_type_dispatch_reject_incomplete_records(self):
        for name, original in self.examples.items():
            payload_schema = self.schemas[f"{original['eventType'].lower()}.schema.json"]
            groups = [
                ((), self.schemas["event.schema.json"]["required"]),
                (("payload",), payload_schema["required"]),
            ]
            for parent, fields in groups:
                for field in fields:
                    with self.subTest(example=name, parent=parent, missing=field):
                        event = copy.deepcopy(original)
                        target = event["payload"] if parent else event
                        del target[field]
                        self.assert_invalid(event)
            for other_type in EVENT_TYPES - {original["eventType"]}:
                with self.subTest(example=name, wrong_type=other_type):
                    event = copy.deepcopy(original)
                    event["eventType"] = other_type
                    self.assert_invalid(event)

    def test_envelope_rejects_wrong_versions_identifiers_and_entity_kinds(self):
        cases = [
            ("schemaVersion", "2.0"), ("schemaVersion", 1),
            ("eventType", "UNKNOWN"), ("eventId", "not-a-uuid"),
            ("eventId", "a1000001-1111-1111-8111-000000000001"),
            ("scenarioRunId", "network-link-cut"), ("scenarioRunId", None),
            ("entityId", ""), ("entityId", "SUB-123"),
            ("entityId", "NODE-CHI-001"), ("entityType", "NETWORK_NODE"),
        ]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                event = self.example("normal-call.json")
                event[field] = value
                self.assert_invalid(event)
        event = self.example("normal-call.json")
        event["entityType"] = "NETWORK_NODE"
        event["entityId"] = "NODE-CHI-001"
        self.assert_invalid(event)

    def test_timestamp_requires_real_utc_date_and_supported_precision(self):
        invalid = [
            "2026-09-11T08:15:30", "2026-09-11T08:15:30+00:00",
            "2026-09-11T11:15:30+03:00", "2026-09-11t08:15:30z",
            "2026-09-11T08:15:30.1234Z", "2026-02-30T08:15:30Z",
            "2026-09-11T24:15:30Z", "2026-09-11T08:15:30Z\n",
        ]
        for value in invalid:
            with self.subTest(timestamp=value):
                event = self.example("normal-call.json")
                event["occurredAt"] = value
                self.assert_invalid(event)
        for value in ["2024-02-29T08:15:30Z", "2026-09-11T08:15:30.1Z", "2026-09-11T08:15:30.123Z"]:
            with self.subTest(valid_timestamp=value):
                event = self.example("normal-call.json")
                event["occurredAt"] = value
                self.assert_valid(event)

    def test_identifiers_reject_whitespace_that_would_change_kafka_keys_or_joins(self):
        for name, original in self.examples.items():
            fields = [(None, "entityId")]
            fields.extend(
                ("payload", field) for field in original["payload"]
                if field.endswith("Id") or field == "region"
            )
            for parent, field in fields:
                for suffix in ["\n", "\r\n", " ", "\t"]:
                    with self.subTest(example=name, field=field, suffix=repr(suffix)):
                        event = self.example(name)
                        target = event[parent] if parent else event
                        target[field] += suffix
                        self.assert_invalid(event)

    def test_integer_units_ranges_and_numeric_types(self):
        fields = {
            "normal-call.json": ["durationSeconds"],
            "normal-data.json": ["durationSeconds", "bytesUploaded", "bytesDownloaded"],
            "normal-network.json": ["sampleWindowSeconds", "bytesTransferred", "activeSubscriberCount"],
        }
        for name, names in fields.items():
            for field in names:
                for value in [-1, 1.5, "100", True, 9007199254740992]:
                    with self.subTest(example=name, field=field, value=value):
                        event = self.example(name)
                        event["payload"][field] = value
                        self.assert_invalid(event)
        for field, values in {
            "sampleWindowSeconds": [0], "packetLossRatio": [-0.01, 1.01, "0.1"],
            "latencyMs": [-1, "12"], "throughputMbps": [-1, "80"],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    event = self.example("normal-network.json")
                    event["payload"][field] = value
                    self.assert_invalid(event)

    def test_service_enums_booleans_and_failed_call_semantics(self):
        cases = [
            ("normal-call.json", "direction", "SIDEWAYS"),
            ("normal-call.json", "outcome", "PENDING"),
            ("normal-call.json", "roaming", "false"),
            ("normal-call.json", "destinationCountry", "md"),
            ("normal-sms.json", "deliveryStatus", "PENDING"),
            ("normal-auth.json", "success", "true"),
            ("normal-auth.json", "country", "MDA"),
            ("normal-network.json", "status", "ANOMALOUS"),
        ]
        for name, field, value in cases:
            with self.subTest(example=name, field=field):
                event = self.example(name)
                event["payload"][field] = value
                self.assert_invalid(event)
        event = self.example("normal-call.json")
        event["payload"]["outcome"] = "FAILED"
        self.assert_invalid(event)
        event["payload"]["durationSeconds"] = 0
        self.assert_valid(event)

    def test_unknown_fields_and_detection_labels_are_rejected(self):
        fields = ["isAnomaly", "expectedDetection", "scenarioName", "fraud", "impactedSubscribers", "lostGB", "typo"]
        for name in self.examples:
            for field in fields:
                for in_payload in [False, True]:
                    with self.subTest(example=name, field=field, payload=in_payload):
                        event = self.example(name)
                        target = event["payload"] if in_payload else event
                        target[field] = True
                        self.assert_invalid(event)

    def test_network_node_and_link_boundaries(self):
        event = self.example("normal-network.json")
        del event["payload"]["linkId"]
        self.assert_invalid(event)
        event["entityType"] = "NETWORK_NODE"
        event["entityId"] = event["payload"]["networkNodeId"]
        self.assert_valid(event)
        event["payload"]["linkId"] = "LINK-CHI-001"
        self.assert_invalid(event)

    def test_network_fixture_measurements_are_consistent(self):
        for name, event in self.examples.items():
            if event["eventType"] != "NETWORK":
                continue
            with self.subTest(example=name):
                payload = event["payload"]
                id_field = "linkId" if event["entityType"] == "NETWORK_LINK" else "networkNodeId"
                self.assertEqual(event["entityId"], payload[id_field])
                expected_rate = round(payload["bytesTransferred"] * 8 / (payload["sampleWindowSeconds"] * 1000000), 6)
                self.assertTrue(math.isclose(payload["throughputMbps"], expected_rate, rel_tol=0, abs_tol=0.000001))
                self.assertEqual(payload["latencyMs"] is None, payload["packetLossRatio"] == 1)
        outage = self.examples["network-link-cut.json"]["payload"]
        self.assertEqual(outage["status"], "DOWN")
        self.assertEqual(outage["bytesTransferred"], 0)
        self.assertEqual(outage["activeSubscriberCount"], self.examples["normal-network.json"]["payload"]["activeSubscriberCount"])

    def test_optional_trace_metadata_does_not_change_payload_validity(self):
        run_id = "b1000002-2222-4222-8222-000000000002"
        for name in self.examples:
            with self.subTest(example=name):
                event = self.example(name)
                event.pop("scenarioRunId", None)
                self.assert_valid(event)
                event["scenarioRunId"] = run_id
                self.assert_valid(event)
        self.assertEqual(
            self.examples["normal-network.json"]["scenarioRunId"],
            self.examples["network-link-cut.json"]["scenarioRunId"],
        )

    def test_documentation_links_resolve_and_every_field_is_documented(self):
        for path in ROOT.rglob("*.md"):
            if ".git" in path.parts or ".venv" in path.parts:
                continue
            for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
                if "://" not in target and not target.startswith("#"):
                    with self.subTest(document=path.relative_to(ROOT), target=target):
                        self.assertTrue((path.parent / target.split("#")[0]).exists())
        contract = (ROOT / "docs" / "streaming" / "event-v1-contract.md").read_text(encoding="utf-8")
        for name, schema in self.schemas.items():
            for field, definition in schema["properties"].items():
                with self.subTest(schema=name, field=field):
                    self.assertIn(f"| {field} |", contract)
                    self.assertTrue(definition.get("description"))


if __name__ == "__main__":
    unittest.main()
