"""Auxiliary schema/boundary checks; no runtime auxiliary correlator is enabled."""

import copy
import json
from pathlib import Path
import sys
import unittest

from jsonschema import ValidationError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from day1_evidence_contract import validator
from observation_contract import ObservationBatch, validate_observation


class Day4AuxiliaryBoundaryTests(unittest.TestCase):
    def test_failure_examples_are_planned_auxiliary_only(self):
        suite = json.loads((ROOT / 'contracts/fixtures/geography/day4-auxiliary-boundary-cases.json').read_text())
        self.assertEqual(suite['status'], 'PLANNED')
        self.assertEqual(suite['coverage'], 'CONTRACT_AND_INGESTION_BOUNDARY_ONLY')
        self.assertEqual({c['type'] for c in suite['cases']}, {'PING_RESULT', 'PROBE_WORKER_HEALTH'})
        auxiliary = validator('contracts/auxiliary/auxiliary-network-evidence-v1.schema.json')
        batch = ObservationBatch()
        service = json.loads((ROOT / 'contracts/fixtures/observations/degraded-volte.json').read_text())
        self.assertEqual(batch.accept(service), 'ACCEPTED')
        before = copy.deepcopy((batch.by_id, batch.by_interval))
        for case in suite['cases']:
            with self.subTest(type=case['type']):
                self.assertEqual(case['status'], 'FAILURE')
                auxiliary.validate(case)
                with self.assertRaises(ValidationError):
                    batch.accept(case)
                self.assertEqual((batch.by_id, batch.by_interval), before)

    def test_auxiliary_and_oracle_fields_cannot_enter_v2(self):
        service = json.loads((ROOT / 'contracts/fixtures/observations/degraded-volte.json').read_text())
        validate_observation(service)
        for field, value in [('auxiliaryEvidence', []), ('groundTruth', 'POWER_OFF'),
                             ('scenario', 'power-fault'), ('runId', 'oracle')]:
            with self.subTest(field=field), self.assertRaises(ValidationError):
                validate_observation(dict(service, **{field: value}))
