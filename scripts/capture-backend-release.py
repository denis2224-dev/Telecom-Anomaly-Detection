import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def capture(revision, image_digest, dataset_manifest, image_distribution):
    revision = subprocess.check_output(
        ['git', 'rev-parse', '--verify', '--end-of-options', revision + '^{commit}'],
        text=True).strip()
    if not re.fullmatch(r'.+@sha256:[0-9a-f]{64}', image_digest):
        raise ValueError('Use the deployed incident-service repository@sha256 content digest')

    def committed(path):
        return subprocess.check_output(['git', 'show', revision + ':' + path])

    def sha(data):
        return hashlib.sha256(data).hexdigest()

    migrations = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', revision,
        'services/incident-service/src/main/resources/db/migration'], text=True).splitlines()
    return {
        'status': 'draft-unaccepted',
        'codeRevision': revision,
        'imageDigest': image_digest,
        'imageDistribution': image_distribution,
        'apiSha256': sha(committed('contracts/openapi/incident-api.yaml')),
        'datasetId': 'trace-link-md-30d-v1',
        'datasetManifestSha256': sha(Path(dataset_manifest).read_bytes()),
        'migrationChecksums': {path: sha(committed(path)) for path in migrations if path.endswith('.sql')},
        'pushEnabled': False,
        'capacityEvidence': None,
        'grantsEvidence': None,
        'policyVersion': None,
        'topologyVersion': None,
        'modelVersion': None,
        'identityConfigurationHash': None,
        'knownLimitations': [],
        'rehearsals': [],
        'signoffs': {name: None for name in ('denis', 'ion', 'sergiu', 'david', 'stanislav')}
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--image-digest', required=True)
    parser.add_argument('--image-distribution', choices=('registry', 'local-content-store'), default='registry')
    parser.add_argument('--dataset-manifest', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output)
    if output.exists():
        raise SystemExit('Refusing to replace existing release identity')
    try:
        result = capture(args.revision, args.image_digest, args.dataset_manifest, args.image_distribution)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x') as stream:
        stream.write(json.dumps(result, indent=2) + '\n')
    print('Draft backend identity captured; acceptance fields remain unverified')


if __name__ == '__main__':
    main()
