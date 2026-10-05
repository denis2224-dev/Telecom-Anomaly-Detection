"""Protect the Day 1 numeric/model freeze, including actual bytes and mapping digest."""

from hashlib import sha256
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Day1DetectionInvariantTests(unittest.TestCase):
    def test_frozen_existing_contracts_and_model_bytes_match_manifest(self):
        manifest = json.loads((ROOT / 'docs/evidence/2026-10-05-sergiu-day1-contract-manifest.json').read_text())
        for item in manifest['unchangedFiles'] + [dict(manifest['peerMapping'], normalization='LF')]:
            with self.subTest(path=item['path']):
                raw = (ROOT / item['path']).read_bytes()
                content = raw if item['normalization'] == 'BINARY' else raw.replace(b'\r\n', b'\n')
                self.assertEqual(sha256(content).hexdigest(), item['normalizedSha256'])

    def test_peer_candidate_preserves_regime_and_published_city_scope_set(self):
        def read(name):
            return json.loads((ROOT / name).read_text(encoding='utf-8'))
        original = read('contracts/baselines/demo-baseline-v2.json')
        candidate = read('contracts/baselines/geographic-peer-baseline-v2.json')
        geography = read('contracts/geography/demo-geography-v1.json')
        authority = read('contracts/topology/geographic-scopes-v2.json')
        self.assertEqual(candidate['baselineVersion'], original['baselineVersion'])
        self.assertEqual(candidate['baselines'], original['baselines'])
        expected = {p['scopeId'] for p in geography['scopes'] if not p['legacy']}
        actual = [p['scopeId'] for p in candidate['peerFallbacks']]
        self.assertEqual(len(actual), 20)
        self.assertEqual(set(actual), expected)
        services = {p['scopeId']: p['service'] for p in authority['scopes']}
        for p in candidate['peerFallbacks']:
            self.assertEqual(services[p['scopeId']], services[p['peerScopeId']])
        self.assertEqual(geography['activation'], {'status': 'CONTRACT_ONLY', 'effectiveFrom': None})
