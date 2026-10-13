"""Reproduce strict rejection with packaged unchanged main detector classes."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--merged-worktree', type=Path, required=True)
    parser.add_argument('--measurements', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New output directory')
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    packaged = args.merged_worktree.resolve() / 'services/processor/target/processor-0.3.0-SNAPSHOT.jar'
    with zipfile.ZipFile(packaged) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/') and not name.endswith('/'):
                target = output / name
                if not target.resolve().is_relative_to(output):
                    raise ValueError('Unsafe archive entry')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    cp = os.pathsep.join(str(p) for p in [output, output / 'BOOT-INF/classes',
                                        *sorted((output / 'BOOT-INF/lib').glob('*.jar'))])
    source = ROOT / 'scripts/rehearsal/TraceLinkCompatibilityProbe.java'
    subprocess.run([str(args.java_home / 'bin/javac'), '-cp', cp, '-d', str(output), str(source)], check=True)
    fixture = args.merged_worktree.resolve() / 'contracts/fixtures/trace-link/detection-cases.json'
    subprocess.run([str(args.java_home / 'bin/java'), '-cp', cp, 'TraceLinkCompatibilityProbe',
                    str(args.measurements.resolve()), str(output / 'result.json'), str(fixture)], check=True)


if __name__ == '__main__':
    main()
