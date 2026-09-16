"""Check the service-assurance API and its shared contract fixtures."""

import copy
from datetime import datetime
import json
from pathlib import Path
import sys
import unittest

from jsonschema import Draft202012Validator
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_utils import FORMAT_CHECKER, ROOT, read_json


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
        cls.api_path = ROOT / "contracts/openapi/incident-api.yaml"
        cls.api = yaml.safe_load(cls.api_path.read_text(encoding="utf-8"))
        cls.schemas = cls.api["components"]["schemas"]
        cls.incident = read_json(ROOT / "contracts/fixtures/incidents/incident-open.json")
        cls.incident_validator = cls.api_validator(cls.schemas["Incident"])
        cls.detection_validator = cls.api_validator(cls.schemas["ServiceDetection"])

    @classmethod
    def api_validator(cls, schema):
        return OAS30Validator(
            expand_local_refs(schema, cls.api), format_checker=FORMAT_CHECKER
        )

    def response_schema(self, route, media="application/json", method="get", status="200"):
        return self.api["paths"][route][method]["responses"][status]["content"][media]["schema"]

    def test_openapi_and_all_declared_examples_validate(self):
        self.assertEqual(self.api["info"]["version"], "0.3.0")
        validate(self.api)
        # Resolve every internal reference, including references outside examples.
        expand_local_refs(self.api, self.api)
        for node in walk(self.api):
            if "schema" in node and "example" in node:
                self.api_validator(node["schema"]).validate(node["example"])
            elif "type" in node and "example" in node:
                self.api_validator(node).validate(node["example"])
            if "schema" in node and "examples" in node:
                for example in node["examples"].values():
                    self.api_validator(node["schema"]).validate(example["value"])

    def test_service_assurance_routes_use_current_schemas(self):
        route_schemas = {
            "/api/incidents": "IncidentPage",
            "/api/incidents/{id}": "Incident",
            "/api/incidents/{id}/detections": "DetectionPage",
            "/api/incidents/{id}/timeline": "AuditPage",
            "/api/services/{scopeId}/kpis": "ServiceKpiPage",
        }
        for route, name in route_schemas.items():
            with self.subTest(route=route):
                self.assertEqual(
                    self.response_schema(route)["$ref"],
                    f"#/components/schemas/{name}",
                )

        service_items = self.response_schema("/api/services")["items"]
        self.assertEqual(service_items["$ref"], "#/components/schemas/ServiceSummary")
        filters = self.api["paths"]["/api/incidents"]["get"]["parameters"]
        names = {item["name"] for item in filters if "name" in item}
        self.assertEqual(names, {"service", "scopeId", "status", "technicalState"})

    def test_detection_evidence_references_observations_by_id(self):
        self.assertNotIn("EvidenceSample", self.schemas)
        incident_fields = set(self.schemas["Incident"]["properties"])
        self.assertTrue({"episodeId", "service", "scopeId", "latestDetection"} <= incident_fields)
        self.assertTrue(
            {"entityType", "entityId", "evidenceSamples", "evidenceCount"}.isdisjoint(
                incident_fields
            )
        )

        observation_schema = read_json(
            ROOT / "contracts/observations/telecom-observation-v2.schema.json"
        )
        observation = read_json(
            ROOT / "contracts/fixtures/observations/degraded-volte.json"
        )
        Draft202012Validator(
            observation_schema, format_checker=FORMAT_CHECKER
        ).validate(observation)

        detection = copy.deepcopy(self.incident["latestDetection"])
        detection["evidence"][0]["sourceEventIds"] = [observation["eventId"]]
        self.detection_validator.validate(detection)
        self.assertEqual(detection["service"], observation["service"])
        self.assertEqual(detection["scopeId"], observation["scopeId"])
        evidence_fields = self.schemas["ServiceDetection"]["properties"]["evidence"][
            "items"
        ]["properties"]
        self.assertIn("sourceEventIds", evidence_fields)
        self.assertNotIn("payload", evidence_fields)

    def test_incident_fixture_matches_rest_and_sse_contracts(self):
        self.api_validator(
            self.response_schema("/api/incidents/{id}")
        ).validate(self.incident)
        page = {"items": [self.incident], "total": 1, "page": 0, "size": 20}
        self.api_validator(self.response_schema("/api/incidents")).validate(page)
        detections = {
            "items": [self.incident["latestDetection"]],
            "total": 1,
            "page": 0,
            "size": 20,
        }
        self.api_validator(
            self.response_schema("/api/incidents/{id}/detections")
        ).validate(detections)

        update = {
            "id": self.incident["id"],
            "version": self.incident["version"],
        }
        sse = "event: incident-upsert\ndata: " + json.dumps(update) + "\n\n"
        sse_schema = self.response_schema(
            "/api/incidents/stream", "text/event-stream"
        )
        self.api_validator(sse_schema).validate(sse)
        data = [json.loads(line[6:]) for line in sse.splitlines() if line.startswith("data: ")]
        self.assertEqual(data, [update])

    def test_incident_fixture_keeps_episode_and_latest_detection_consistent(self):
        for path in (ROOT / "contracts/fixtures/incidents").glob("*.json"):
            incident = read_json(path)
            with self.subTest(fixture=path.name):
                self.incident_validator.validate(incident)
                detection = incident["latestDetection"]
                self.detection_validator.validate(detection)
                for field in ("episodeId", "service", "scopeId", "severity", "technicalState"):
                    self.assertEqual(incident[field], detection[field])
                self.assertEqual(incident["latestSequence"], detection["sequence"])
                self.assertEqual(incident["detectedAt"], detection["detectedAt"])
                self.assertEqual(incident["lastObservedAt"], detection["windowEnd"])

                first = datetime.fromisoformat(incident["firstObservedAt"])
                start = datetime.fromisoformat(detection["windowStart"])
                end = datetime.fromisoformat(detection["windowEnd"])
                detected = datetime.fromisoformat(detection["detectedAt"])
                self.assertLessEqual(first, start)
                self.assertEqual((end - start).total_seconds(), 60)
                self.assertLessEqual(end, detected)
                self.assertLessEqual(
                    datetime.fromisoformat(incident["createdAt"]),
                    datetime.fromisoformat(incident["updatedAt"]),
                )

    def test_invalid_incident_and_detection_are_rejected(self):
        cases = [
            (("id",), "invalid"),
            (("episodeId",), "not-a-sha256-id"),
            (("service",), "DATA"),
            (("latestSequence",), 0),
            (("latestDetection", "schemaVersion"), 1),
            (("latestDetection", "anomalyType"), "ACCOUNT_COMPROMISE"),
            (("latestDetection", "evidence", 0, "sourceEventIds", 0), "invalid"),
        ]
        for path, value in cases:
            incident = copy.deepcopy(self.incident)
            target = incident
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path):
                self.assertFalse(self.incident_validator.is_valid(incident))

        missing_detection = copy.deepcopy(self.incident)
        del missing_detection["latestDetection"]
        self.assertFalse(self.incident_validator.is_valid(missing_detection))

        raw_event = copy.deepcopy(self.incident["latestDetection"])
        raw_event["eventId"] = "c2a43404-17e1-59d0-9999-03f9b0adf7a0"
        self.assertFalse(self.detection_validator.is_valid(raw_event))

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
            "ChangeStatusRequest": {
                "status": "RESOLVED",
                "version": 0,
                "resolutionNote": "Reviewed",
            },
            "CommentRequest": {
                "text": "Reviewed",
                "version": 0,
                "requestId": self.incident["id"],
            },
        }
        for name, request in requests.items():
            validator = self.api_validator(self.schemas[name])
            validator.validate(request)
            for field in ["actorId", "roles"]:
                self.assertFalse(
                    validator.is_valid(dict(request, **{field: "untrusted"}))
                )
        for name, field in [
            ("CommentRequest", "text"),
            ("ChangeStatusRequest", "resolutionNote"),
        ]:
            request = dict(requests[name], **{field: "   "})
            self.assertFalse(self.api_validator(self.schemas[name]).is_valid(request))


if __name__ == "__main__":
    unittest.main()
