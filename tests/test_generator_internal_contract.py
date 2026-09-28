"""Validate the private handoff independently of the public simulator contract."""

import unittest

from openapi_spec_validator import validate
import yaml

from test_utils import ROOT


class GeneratorInternalContractTests(unittest.TestCase):
    def test_private_openapi_and_execution_only_payload(self):
        path = ROOT / 'contracts/openapi/generator-internal.yaml'
        spec = yaml.safe_load(path.read_text(encoding='utf-8'))
        validate(spec)
        self.assertEqual(set(spec['paths']), {
            '/internal/scenario-runs/{runId}',
            '/internal/scenario-runs/{runId}/stop',
        })
        command = spec['components']['schemas']['ExecutionCommand']
        self.assertFalse(command['additionalProperties'])
        self.assertEqual(set(command['required']), {
            'scenarioType', 'scopeId', 'seed', 'scheduledStartAt', 'scheduledEndAt',
        })
        self.assertNotIn('requestId', command['properties'])
        self.assertNotIn('bodyHash', command['properties'])
        self.assertNotIn('requestedBy', command['properties'])
        self.assertIn('SCOPE_WINDOW_CONFLICT', spec['components']['schemas']['Error']['properties']['code']['enum'])


if __name__ == '__main__':
    unittest.main()
