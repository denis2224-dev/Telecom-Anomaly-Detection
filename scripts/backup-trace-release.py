#!/usr/bin/env python3
"""Create verified private PostgreSQL dumps. A failed command never produces a success manifest."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def backup(container, output, databases, username='postgres'):
    for name in [container, username, *databases]:
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', name):
            raise ValueError('Invalid container, role or database identifier')
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    files = []
    commands = [('roles.sql', ['pg_dumpall', '-U', username, '--globals-only', '--no-role-passwords'])]
    commands += [(name + '.dump', ['pg_dump', '-U', username, '--format=custom', '--dbname', name]) for name in databases]
    for name, command in commands:
        partial = output / (name + '.partial')
        with partial.open('xb') as stream:
            os.chmod(partial, 0o600)
            subprocess.run(['docker', 'exec', container, *command], stdout=stream, check=True)
        if partial.stat().st_size == 0:
            raise ValueError('Empty backup rejected')
        if name.endswith('.dump'):
            # pg_restore validates the actual custom archive, not a placeholder.
            with partial.open('rb') as stream:
                subprocess.run(['docker', 'exec', '-i', container, 'pg_restore', '--list'], stdin=stream, stdout=subprocess.DEVNULL, check=True)
        target = output / name
        partial.rename(target)
        files.append({'file': name, 'bytes': target.stat().st_size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    manifest = {'createdAt': datetime.now(timezone.utc).isoformat(), 'container': container, 'files': files,
                'rolePasswordsIncluded': False, 'restoreAcceptance': 'PENDING',
                'consistency': 'Separate database snapshots; quiesce writers for a coordinated release backup.'}
    target = output / 'backup-manifest.json'
    target.write_text(json.dumps(manifest, indent=2) + '\n')
    os.chmod(target, 0o600)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--database', action='append', dest='databases')
    parser.add_argument('--username', default='postgres')
    args = parser.parse_args()
    backup(args.container, args.output, args.databases or ['incidents_db', 'processing_db', 'keycloak'], args.username)
    print('Backup files verified; restore and identity secret acceptance remain pending.')
