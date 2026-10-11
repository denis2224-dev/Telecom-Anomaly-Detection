"""Strict four-language Day 1 parity; optional isolated PostgreSQL export."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURES = ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json'
LANGUAGES = ('java', 'python', 'sql', 'javascript')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def decode(text):
    def invalid(value):
        raise ValueError('Non-finite JSON number: ' + value)
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid)


def suite_data(path):
    raw = Path(path).read_bytes()
    suite = decode(raw.decode('utf-8'))
    if suite.get('suiteVersion') != 'trace-link-arithmetic-v1' or not suite.get('cases'):
        raise ValueError('Expected nonempty Trace Link arithmetic suite')
    ids = [case['id'] for case in suite['cases']]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate fixture case ID')
    return suite, hashlib.sha256(raw).hexdigest()


def compare(expected, actual, location=''):
    """Exact structure/metadata/integers; absolute 1e-9 derived float tolerance."""
    if isinstance(expected, dict):
        if not isinstance(actual, dict) or expected.keys() != actual.keys():
            raise ValueError(f'{location}: object keys differ')
        return max((compare(value, actual[key], f'{location}/{key}') for key, value in expected.items()), default=0)
    if isinstance(expected, list):
        if not isinstance(actual, list) or len(expected) != len(actual):
            raise ValueError(f'{location}: array shape differs')
        return max((compare(a, b, f'{location}/{i}') for i, (a, b) in enumerate(zip(expected, actual))), default=0)
    if type(expected) in (int, float):
        if type(actual) not in (int, float) or not math.isfinite(actual):
            raise ValueError(f'{location}: expected finite numeric value')
        difference = abs(expected - actual)
        if (type(expected) is int and difference != 0) or (type(expected) is float and difference > 1e-9):
            raise ValueError(f'{location}: {actual!r} != {expected!r}')
        return difference
    if type(actual) is not type(expected) or actual != expected:
        raise ValueError(f'{location}: {actual!r} != {expected!r}')
    return 0


def contexts(suite):
    return {c['id']: {k: c[k] for k in ('scope', 'window', 'policyVersion', 'units')} for c in suite['cases']}


def check_exports(fixtures, exports):
    if set(exports) != set(LANGUAGES) or any(not path for path in exports.values()):
        raise ValueError('All four exports are required: java, python, sql, javascript')
    suite, digest = suite_data(fixtures)
    expected = {c['id']: c['expected'] if 'expected' in c else {'error': c['expectedError']} for c in suite['cases']}
    errors = {}
    for language in LANGUAGES:
        payload = decode(Path(exports[language]).read_text(encoding='utf-8'))
        if set(payload) != {'fixtureSha256', 'contexts', 'results'}:
            raise ValueError(f'{language}: invalid export envelope')
        if payload['fixtureSha256'] != digest:
            raise ValueError(f'{language}: fixture SHA-256 differs; regenerate export')
        # Metadata is identity, so use direct equality rather than numeric tolerance.
        if payload['contexts'] != contexts(suite):
            raise ValueError(f'{language}: scope/window/policy/unit contexts differ')
        errors[language] = compare(expected, payload['results'], language)
    return len(expected), digest, errors


def export_sql(fixtures, container, output):
    """Only JSON transport is Python; every calculation executes in PostgreSQL."""
    inspect = subprocess.run(['docker', 'inspect', container], check=True, capture_output=True, text=True)
    state = decode(inspect.stdout)[0]
    if (state['Config'].get('Labels', {}).get('codex.trace-link-parity') != 'true'
            or set(state['NetworkSettings']['Networks']) != {'none'}
            or not state['State']['Running']):
        raise ValueError('SQL export requires a running, network-none disposable parity container with codex.trace-link-parity=true label')
    suite, digest = suite_data(fixtures)
    # Expected/evaluator fields never enter the SQL calculator.
    inputs = [{k: case[k] for k in ('id', 'operation', 'input', 'window')} for case in suite['cases']]
    data = json.dumps(inputs, allow_nan=False, separators=(',', ':'))
    delimiter = '$tl_input_' + digest + '$'
    if delimiter in data:
        raise ValueError('Unexpected SQL delimiter collision')
    query = ('SET client_min_messages TO WARNING;\n' + (ROOT / 'scripts/trace-link-reference.sql').read_text(encoding='utf-8')
             + '\nSELECT jsonb_object_agg(c->>\'id\',tl_parity.calculate(c->>\'operation\',c->\'input\',c->\'window\'))'
             + ' FROM jsonb_array_elements(' + delimiter + data + delimiter + '::jsonb) c;\n')
    process = subprocess.run(['docker', 'exec', '-i', container, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres'],
                             input=query, check=True, capture_output=True, text=True, encoding='utf-8')
    results = decode(process.stdout.strip())
    if set(results) != {case['id'] for case in suite['cases']}:
        raise ValueError('SQL export has missing/extra case results')
    for case in suite['cases']:
        if bool(results[case['id']].get('error')) != bool(case.get('expectedError')):
            raise ValueError('SQL unexpected valid/invalid case: ' + case['id'])
    payload = {'fixtureSha256': digest, 'contexts': contexts(suite), 'results': results}
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    return len(results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', type=Path, default=DEFAULT_FIXTURES)
    for language in LANGUAGES:
        parser.add_argument(f'--{language}-output', type=Path)
    parser.add_argument('--export-sql', action='store_true')
    parser.add_argument('--container')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.export_sql:
        if not args.container or not args.output:
            parser.error('--export-sql requires --container and --output')
        count = export_sql(args.fixtures, args.container, args.output)
        print(f'PASS: PostgreSQL independently exported {count} cases')
    else:
        exports = {language: getattr(args, language + '_output') for language in LANGUAGES}
        if not all(exports.values()):
            parser.error('all four --java-output/--python-output/--sql-output/--javascript-output files are required')
        count, digest, errors = check_exports(args.fixtures, exports)
        for language, error in errors.items():
            print(f'PASS {language}: {count} hand-derived cases, maximum absolute float error={error:.17g}')
        print(f'PASS: four-language parity; fixture SHA-256={digest}')


if __name__ == '__main__':
    main()
