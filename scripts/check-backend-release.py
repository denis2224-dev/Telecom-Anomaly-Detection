import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


IDENTITY_FIELDS = ('codeRevision', 'imageDigest', 'apiSha256', 'datasetManifestSha256',
                   'identityConfigurationHash', 'policyVersion', 'topologyVersion', 'modelVersion')
OWNERS = ('denis', 'ion', 'sergiu', 'david', 'stanislav')


def validate(document, repository):
    repository = Path(repository).resolve()
    backend = document.get('backend', document) if isinstance(document, dict) else None
    if not isinstance(backend, dict):
        return ['Backend release must be an object']
    failures = []

    def require(condition, reason):
        if not condition:
            failures.append(reason)
        return bool(condition)

    def matches(pattern, value):
        return isinstance(value, str) and re.fullmatch(pattern, value) is not None

    def committed(path):
        return subprocess.check_output(['git', 'show', backend['codeRevision'] + ':' + path],
                                       cwd=repository, stderr=subprocess.DEVNULL)

    def artifact(entry):
        if not require(isinstance(entry, dict), 'Evidence entry must include path and sha256'):
            return
        name = entry.get('path')
        if not require(isinstance(name, str), 'Evidence path must be a repository-relative string'):
            return
        path = Path(name)
        if not require(not path.is_absolute() and '..' not in path.parts
                       and path.parts[:2] == ('docs', 'evidence'),
                       'Evidence must be inside repository docs/evidence'):
            return
        full = (repository / path).resolve()
        if not require(full.is_relative_to(repository / 'docs' / 'evidence') and full.is_file(),
                       'Evidence file is missing or outside the evidence directory'):
            return
        require(hashlib.sha256(full.read_bytes()).hexdigest() == entry.get('sha256'),
                'Evidence checksum changed: ' + name)

    require(backend.get('status') != 'superseded-unaccepted', 'Candidate identity is superseded')
    revision_valid = require(matches(r'[0-9a-f]{40}', backend.get('codeRevision')), 'Pin a full code revision')
    require(matches(r'.+@sha256:[0-9a-f]{64}', backend.get('imageDigest')), 'Pin the deployed image digest')
    for field in ('apiSha256', 'datasetManifestSha256', 'identityConfigurationHash'):
        require(matches(r'[0-9a-f]{64}', backend.get(field)), 'Missing ' + field)
    for field in ('policyVersion', 'topologyVersion', 'modelVersion'):
        require(isinstance(backend.get(field), str) and bool(backend[field].strip()), 'Missing ' + field)
    require(backend.get('datasetId') == 'trace-link-md-30d-v1', 'Dataset identity differs')
    require(isinstance(backend.get('pushEnabled'), bool), 'Record effective push flag')
    checksums = backend.get('migrationChecksums')
    checksums_valid = require(isinstance(checksums, dict) and bool(checksums), 'Migration checksums are missing')
    if revision_valid:
        try:
            migrations = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', backend['codeRevision'],
                'services/incident-service/src/main/resources/db/migration',
                'services/incident-service/src/main/resources/db/compatibility/foundation'], cwd=repository,
                text=True, stderr=subprocess.DEVNULL).splitlines()
            expected = {path for path in migrations if path.endswith('.sql')}
            if checksums_valid and require(set(checksums) == expected and bool(expected),
                                          'Migration set differs from code revision'):
                for path, checksum in checksums.items():
                    require(hashlib.sha256(committed(path)).hexdigest() == checksum,
                            'Migration checksum differs: ' + path)
            require(hashlib.sha256(committed('contracts/openapi/incident-api.yaml')).hexdigest()
                    == backend.get('apiSha256'), 'API differs from code revision')
        except subprocess.CalledProcessError:
            require(False, 'Code revision or committed release inputs are unavailable')
    artifact(backend.get('capacityEvidence'))
    artifact(backend.get('grantsEvidence'))
    identity = {name: backend.get(name) for name in IDENTITY_FIELDS}
    rehearsals = backend.get('rehearsals')
    if require(isinstance(rehearsals, list) and len(rehearsals) == 2, 'Two rehearsal records are required'):
        ids = [run.get('runId') for run in rehearsals if isinstance(run, dict)]
        require(len(ids) == 2 and all(isinstance(value, str) and value.strip() for value in ids)
                and len(set(value for value in ids if isinstance(value, str))) == 2,
                'Rehearsals require distinct nonempty run IDs')
        for run in rehearsals:
            if require(isinstance(run, dict), 'Rehearsal must be an object'):
                require(run.get('status') == 'passed' and run.get('identity') == identity,
                        'Rehearsals must pass on one identity')
                artifact(run.get('evidence'))
    signoffs = backend.get('signoffs')
    for owner in OWNERS:
        entry = signoffs.get(owner) if isinstance(signoffs, dict) else None
        if require(isinstance(entry, dict) and entry.get('status') == 'accepted'
                   and entry.get('codeRevision') == backend.get('codeRevision')
                   and entry.get('identity') == identity, 'Missing sign-off on candidate identity: ' + owner):
            artifact(entry.get('evidence'))
    require(isinstance(backend.get('knownLimitations'), list), 'Record known limitations')
    if backend.get('pushEnabled') is True:
        artifact(backend.get('browserPushEvidence'))
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest')
    args = parser.parse_args()
    try:
        failures = validate(json.loads(Path(args.manifest).read_text()), Path.cwd())
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit('Cannot read release manifest: ' + str(error)) from error
    if failures:
        raise SystemExit('\n'.join(failures))
    print('Backend release evidence is complete and internally consistent; owner observations require review')


if __name__ == '__main__':
    main()
