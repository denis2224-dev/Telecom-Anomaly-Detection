"""Independent native-observation and delivery fixture contracts."""
import copy
import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[1]


def read(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class TraceLinkDay3ContractTest(unittest.TestCase):
    def test_synthetic_native_inputs_and_original_observation_checksums(self):
        validator = Draft202012Validator(read("contracts/detections/trace-link-persisted-window-v1.schema.json"), format_checker=FormatChecker())
        sources = read("contracts/fixtures/trace-link/day3-source-observations.json")["observations"]
        index = {r["observation"]["measurementId"]: r for r in sources}
        rows = read("contracts/fixtures/trace-link/day3-persisted-windows.json")["windows"]
        self.assertEqual({r["domain"] for r in rows}, {"TRANSPORT", "ROAMING", "MOBILE_DATA"})
        for row in rows:
            validator.validate(row["input"])
            for evidence in row["input"]["context"]["evidence"]:
                source = index[evidence["measurementId"]]
                digest = hashlib.sha256(canonical(source["observation"]).encode()).hexdigest()
                self.assertEqual(digest, source["sha256"])
                self.assertEqual(digest, evidence["provenance"]["sourceChecksum"])
                self.assertEqual(source["observation"]["primitives"], evidence["primitives"])

    def test_wire_oracles_and_unknown_field_rejection(self):
        validator = Draft202012Validator(read("contracts/detections/trace-link-detection-v1.schema.json"), format_checker=FormatChecker())
        rows = read("contracts/fixtures/trace-link/day3-detection-envelopes.json")["detections"]
        self.assertEqual(len(rows), 21)
        for row in rows:
            validator.validate(row)
        bad = copy.deepcopy(rows[0])
        bad["expectedLabel"] = "OUTAGE"
        with self.assertRaises(ValidationError):
            validator.validate(bad)

    def test_native_dataset_isolation_does_not_relax_reference_provenance(self):
        schema = read("contracts/evidence/trace-link-evidence-v1.schema.json")
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        evidence = copy.deepcopy(read("contracts/fixtures/trace-link/day3-persisted-windows.json")["windows"][0]["input"]["context"]["evidence"][0])
        evidence["provenance"]["datasetId"] = "native-independent-run-v1"
        validator.validate(evidence)
        evidence["provenance"]["origin"] = "REFERENCE_CSV"
        with self.assertRaises(ValidationError):
            validator.validate(evidence)


if __name__ == "__main__":
    unittest.main()
