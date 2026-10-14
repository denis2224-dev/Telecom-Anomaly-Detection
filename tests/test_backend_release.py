import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('backend_release', ROOT / 'scripts/check-backend-release.py')
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class BackendReleaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.repository = Path(cls.temporary.name)
        cls.migration = 'services/incident-service/src/main/resources/db/migration/V001__fixture.sql'
        inputs = {
            'contracts/openapi/incident-api.yaml': 'openapi: 3.0.3\n',
            cls.migration: 'CREATE TABLE fixture (id integer);\n',
            'docs/evidence/gate-fixture.md': 'Synthetic validator test only; not release acceptance.\n',
            'dataset.json': '{"fixture":true}\n'
        }
        for name, content in inputs.items():
            path = cls.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        for command in (['git', 'init', '--quiet'], ['git', 'add', '--', *inputs],
                        ['git', '-c', 'user.name=Release test', '-c', 'user.email=release-test@example.invalid',
                         'commit', '--quiet', '-m', 'test: create disposable release inputs']):
            subprocess.run(command, cwd=cls.repository, check=True, capture_output=True)
        cls.revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=cls.repository, text=True).strip()
        sha = lambda name: hashlib.sha256((cls.repository / name).read_bytes()).hexdigest()
        artifact = {'path': 'docs/evidence/gate-fixture.md', 'sha256': sha('docs/evidence/gate-fixture.md')}
        cls.document = {
            'codeRevision': cls.revision,
            'imageDigest': 'example.invalid/backend@sha256:' + 'a' * 64,
            'apiSha256': sha('contracts/openapi/incident-api.yaml'),
            'datasetManifestSha256': sha('dataset.json'),
            'identityConfigurationHash': 'b' * 64,
            'policyVersion': 'fixture-policy', 'topologyVersion': 'fixture-topology', 'modelVersion': 'fixture-model',
            'datasetId': 'trace-link-md-30d-v1', 'pushEnabled': False,
            'migrationChecksums': {cls.migration: sha(cls.migration)},
            'capacityEvidence': artifact, 'grantsEvidence': artifact, 'knownLimitations': []
        }
        identity = {name: cls.document[name] for name in CHECKER.IDENTITY_FIELDS}
        cls.document['rehearsals'] = [
            {'runId': name, 'status': 'passed', 'identity': identity, 'evidence': artifact}
            for name in ('fixture-run-one', 'fixture-run-two')]
        cls.document['signoffs'] = {
            name: {'status': 'accepted', 'codeRevision': cls.revision, 'identity': identity, 'evidence': artifact}
            for name in CHECKER.OWNERS}

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_consistent_synthetic_fixture_and_shared_wrapper_are_valid(self):
        self.assertEqual([], CHECKER.validate(self.document, self.repository))
        self.assertEqual([], CHECKER.validate({'backend': self.document}, self.repository))

    def test_draft_missing_identities_and_owners_is_rejected_without_crashing(self):
        document = copy.deepcopy(self.document)
        document.update(imageDigest=None, identityConfigurationHash=None, rehearsals=[], signoffs={})
        failures = CHECKER.validate(document, self.repository)
        self.assertIn('Pin the deployed image digest', failures)
        self.assertIn('Two rehearsal records are required', failures)
        self.assertEqual(5, sum('Missing sign-off' in message for message in failures))

    def test_migration_omission_and_changed_api_are_rejected(self):
        document = copy.deepcopy(self.document)
        document['migrationChecksums']['unrelated.sql'] = document['migrationChecksums'].pop(self.migration)
        document['apiSha256'] = 'c' * 64
        failures = CHECKER.validate(document, self.repository)
        self.assertIn('Migration set differs from code revision', failures)
        self.assertIn('API differs from code revision', failures)

    def test_evidence_mutation_and_unsafe_paths_are_rejected(self):
        document = copy.deepcopy(self.document)
        document['capacityEvidence'] = {'path': 'docs/evidence/gate-fixture.md', 'sha256': 'c' * 64}
        document['grantsEvidence'] = {'path': '../private-backup', 'sha256': 'c' * 64}
        failures = CHECKER.validate(document, self.repository)
        self.assertTrue(any('Evidence checksum changed' in message for message in failures))
        self.assertIn('Evidence must be inside repository docs/evidence', failures)

    def test_replayed_run_and_different_owner_identity_are_rejected(self):
        document = copy.deepcopy(self.document)
        document['rehearsals'][1]['runId'] = document['rehearsals'][0]['runId']
        document['signoffs']['denis']['identity'] = {**document['signoffs']['denis']['identity'], 'modelVersion': 'changed'}
        failures = CHECKER.validate(document, self.repository)
        self.assertIn('Rehearsals require distinct nonempty run IDs', failures)
        self.assertIn('Missing sign-off on candidate identity: denis', failures)

    def test_enabled_push_requires_browser_evidence(self):
        document = copy.deepcopy(self.document)
        document['pushEnabled'] = True
        self.assertIn('Evidence entry must include path and sha256', CHECKER.validate(document, self.repository))

    def test_capture_does_not_overwrite_or_accept_a_draft(self):
        output = self.repository / 'draft.json'
        command = [sys.executable, str(ROOT / 'scripts/capture-backend-release.py'), '--revision', self.revision,
                   '--image-digest', self.document['imageDigest'], '--dataset-manifest', 'dataset.json',
                   '--output', str(output)]
        subprocess.run(command, cwd=self.repository, check=True, capture_output=True)
        original = output.read_bytes()
        self.assertTrue(CHECKER.validate(json.loads(original), self.repository))
        repeated = subprocess.run(command, cwd=self.repository, capture_output=True, text=True)
        self.assertNotEqual(0, repeated.returncode)
        self.assertIn('Refusing to replace', repeated.stderr)
        self.assertEqual(original, output.read_bytes())


if __name__ == '__main__':
    unittest.main()
