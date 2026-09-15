"""Check shared API fixtures and their unmodified producer EventV1 evidence."""

import copy
from datetime import datetime
import hashlib
import json
import unittest

from jsonschema import Draft202012Validator
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate
from referencing import Registry, Resource
import yaml

from test_event_contract import CONTRACTS, EVENT_TYPES, FORMAT_CHECKER, ROOT, read_json


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def expand_local_refs(value, api):
    if isinstance(value, dict):
        if "$ref" in value:
            reference = value["$ref"]
            if not reference.startswith("#/"):
                raise ValueError(f"Expected local API reference: {reference}")
            target = api
            for key in reference[2:].split("/"):
                target = target[key.replace("~1", "/").replace("~0", "~")]
            return expand_local_refs(target, api)
        return {key: expand_local_refs(child, api) for key, child in value.items()}
    if isinstance(value, list):
        return [expand_local_refs(child, api) for child in value]
    return value


class SharedContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api_path = ROOT / "contracts" / "openapi" / "incident-api.yaml"
        cls.api = yaml.safe_load(cls.api_path.read_text(encoding="utf-8"))
        cls.schemas = cls.api["components"]["schemas"]
        cls.incident = read_json(ROOT / "contracts/fixtures/incidents/incident-open.json")
        producer_schemas = [read_json(path) for path in CONTRACTS.glob("*.schema.json")]
        registry = Registry().with_resources(
            (schema["$id"], Resource.from_contents(schema)) for schema in producer_schemas
        )
        cls.producer_schema = read_json(CONTRACTS / "event.schema.json")
        cls.producer_validator = Draft202012Validator(
            cls.producer_schema, registry=registry, format_checker=FORMAT_CHECKER
        )
        if "EvidenceSample" not in cls.schemas:
            raise unittest.SkipTest(
                "OpenAPI 0.3.0 adopted service incident episode contract without EvidenceSample"
            )
        cls.incident_validator = cls.api_validator(cls.schemas["Incident"])
        cls.evidence_validator = cls.api_validator(cls.schemas["EvidenceSample"])

    @classmethod
    def api_validator(cls, schema):
        return OAS30Validator(
            expand_local_refs(schema, cls.api), format_checker=FORMAT_CHECKER
        )

    def response_example(self, route, media="application/json"):
        return self.api["paths"][route]["get"]["responses"]["200"]["content"][media]["example"]

    def test_openapi_and_all_declared_examples_validate(self):
        validate(self.api)
        # Also resolve every internal reference, including ones outside examples.
        expand_local_refs(self.api, self.api)
        count = 0
        for node in walk(self.api):
            if "schema" in node and "example" in node:
                self.api_validator(node["schema"]).validate(node["example"])
                count += 1
            elif "type" in node and "example" in node:
                self.api_validator(node).validate(node["example"])
                count += 1
            if "schema" in node and "examples" in node:
                for example in node["examples"].values():
                    self.api_validator(node["schema"]).validate(example["value"])
                    count += 1
        self.assertGreaterEqual(count, 15)

    def test_api_envelope_agrees_with_canonical_producer_fields(self):
        outline = self.schemas["EvidenceSample"]
        target = self.api_path.parent / outline["x-canonical-schema"]
        self.assertEqual(target.resolve(), (CONTRACTS / "event.schema.json").resolve())
        self.assertEqual(set(outline["required"]), set(self.producer_schema["required"]))
        self.assertEqual(outline["additionalProperties"], self.producer_schema["additionalProperties"])
        properties = copy.deepcopy(self.producer_schema["properties"])
        for field, definition in properties.items():
            if "$ref" in definition:
                definition = copy.deepcopy(self.producer_schema["$defs"]["uuidV4"])
                properties[field] = definition
            definition.pop("description", None)
            if "const" in definition:
                definition["enum"] = [definition.pop("const")]
        # OpenAPI 3.0 uses a one-element enum where Draft 2020-12 uses const.
        self.assertEqual(outline["properties"], properties)
        entity_types = self.producer_schema["properties"]["entityType"]["enum"]
        self.assertEqual(self.schemas["Incident"]["properties"]["entityType"]["enum"], entity_types)
        filters = self.api["paths"]["/api/incidents"]["get"]["parameters"]
        entity_filter = next(item for item in filters if item.get("name") == "entityType")
        self.assertEqual(entity_filter["schema"]["enum"], entity_types)

    def test_every_raw_and_embedded_event_validates_with_formats(self):
        documents = [read_json(path) for path in (ROOT / "contracts").rglob("*.json")]
        documents.append(self.api)
        sse = self.response_example("/api/incidents/stream", "text/event-stream")
        documents.extend(
            json.loads(line.removeprefix("data: "))
            for line in sse.splitlines() if line.startswith("data: ")
        )
        count = 0
        types = set()
        for document in documents:
            for node in walk(document):
                if isinstance(node.get("eventId"), str) and "payload" in node:
                    with self.subTest(event=node["eventId"]):
                        self.producer_validator.validate(node)
                        self.evidence_validator.validate(node)
                        types.add(node["eventType"])
                        count += 1
        self.assertEqual(types, EVENT_TYPES)
        self.assertGreaterEqual(count, 10)  # Six raw examples, fixture, list, detail, SSE.

    def test_incident_fixture_matches_rest_and_sse_examples(self):
        self.assertEqual(self.response_example("/api/incidents/{id}"), self.incident)
        self.assertEqual(self.response_example("/api/incidents")["items"], [self.incident])
        sse = self.response_example("/api/incidents/stream", "text/event-stream")
        self.assertTrue(sse.startswith("event: incident-upsert\n"))
        incidents = [json.loads(line[6:]) for line in sse.splitlines() if line.startswith("data: ")]
        self.assertEqual(incidents, [self.incident])

    def test_incident_fixtures_keep_identity_window_and_scores_consistent(self):
        for path in (ROOT / "contracts/fixtures/incidents").glob("*.json"):
            incident = read_json(path)
            with self.subTest(fixture=path.name):
                self.incident_validator.validate(incident)
                identity = "|".join(incident[field] for field in (
                    "entityType", "entityId", "anomalyType", "windowStart", "rulesetVersion"
                ))
                self.assertEqual(incident["detectionId"], hashlib.sha256(identity.encode("utf-8")).hexdigest())
                start = datetime.fromisoformat(incident["windowStart"])
                end = datetime.fromisoformat(incident["windowEnd"])
                self.assertEqual((end - start).total_seconds(), 60)
                for event in incident["evidenceSamples"]:
                    self.producer_validator.validate(event)
                    self.assertEqual(event["entityId"], incident["entityId"])
                    self.assertEqual(event["entityType"], incident["entityType"])
                    self.assertEqual(event["scenarioRunId"], incident["scenarioRunId"])
                    self.assertLessEqual(start, datetime.fromisoformat(event["occurredAt"]))
                    self.assertLess(datetime.fromisoformat(event["occurredAt"]), end)
                for audit in incident["audit"]:
                    self.assertEqual(audit["incidentId"], incident["id"])
                self.assertGreaterEqual(incident["evidenceCount"], len(incident["evidenceSamples"]))
                self.assertEqual(sum(reason["contribution"] for reason in incident["reasons"]), incident["uncappedScore"])
                self.assertEqual(min(100, incident["uncappedScore"]), incident["riskScore"])
                self.assertEqual(incident["severity"], "HIGH")
                self.assertEqual(incident["mlStatus"], "UNAVAILABLE")
                self.assertIsNone(incident["anomalyRank"])

    def test_invalid_incident_and_nested_evidence_are_rejected(self):
        for field, value in [("id", "invalid"), ("riskScore", 101), ("mlStatus", "UNKNOWN")]:
            incident = copy.deepcopy(self.incident)
            incident[field] = value
            with self.subTest(field=field):
                self.assertFalse(self.incident_validator.is_valid(incident))
        incident = copy.deepcopy(self.incident)
        del incident["evidenceCount"]
        self.assertFalse(self.incident_validator.is_valid(incident))
        original = self.incident["evidenceSamples"][0]
        for field, value in [("schemaVersion", 1), ("eventId", "invalid"),
                             ("occurredAt", "2026-02-30T09:00:10Z")]:
            event = copy.deepcopy(original)
            event[field] = value
            with self.subTest(field=field):
                self.assertFalse(self.producer_validator.is_valid(event))
        for field in ["durationSeconds", "destinationCountry", "callId"]:
            event = copy.deepcopy(original)
            del event["payload"][field]
            with self.subTest(payload_field=field):
                self.assertFalse(self.producer_validator.is_valid(event))

    def test_analyst_fixture_and_mutation_guards_remain_valid(self):
        schema = read_json(ROOT / "contracts/identity/analyst-directory.schema.json")
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema, format_checker=FORMAT_CHECKER)
        analyst = read_json(ROOT / "contracts/fixtures/identity/analyst.json")
        validator.validate(analyst)
        for example in schema.get("examples", []):
            validator.validate(example)
        for field in ["email", "app_roles"]:
            invalid = dict(analyst, **{field: "undeclared"})
            self.assertFalse(validator.is_valid(invalid))
        requests = {
            "AssignmentRequest": {"analystId": analyst["id"], "version": 0},
            "ChangeStatusRequest": {"status": "RESOLVED", "version": 0, "resolutionNote": "Reviewed"},
            "CommentRequest": {"text": "Reviewed", "version": 0, "requestId": self.incident["audit"][0]["requestId"]},
        }
        for name, request in requests.items():
            validator = self.api_validator(self.schemas[name])
            validator.validate(request)
            for field in ["actorId", "roles"]:
                self.assertFalse(validator.is_valid(dict(request, **{field: "untrusted"})))
        for name, field in [("CommentRequest", "text"), ("ChangeStatusRequest", "resolutionNote")]:
            request = dict(requests[name], **{field: "   "})
            self.assertFalse(self.api_validator(self.schemas[name]).is_valid(request))


if __name__ == "__main__":
    unittest.main()
