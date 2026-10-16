#!/usr/bin/env python3
"""Validate the full pinned archive and atomically project incident-owned history.

No source generation, catalogue activation, scenario truth ingestion, or schema DDL.
Database connection uses ordinary libpq environment variables; credentials are never printed.
"""
import argparse
import csv
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / 'contracts/datasets/trace-link-md-30d-v1.manifest.json'
LAYOUT = json.loads((ROOT / 'contracts/datasets/trace-link-archive-layout-v1.json').read_text())
TEXT = {'ts', 'city_id', 'link_id', 'country_id', 'partner_id', 'direction'}
COUNTER_SUFFIXES = ('attempts', 'success', 'drops', 'errors', 'submitted', 'delivered', 'expired', 'queue', 'active_users')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def validate(source, snapshot):
    expected = json.loads(PIN.read_text())
    manifest = source / 'manifest.json'
    require(manifest.is_file() and not manifest.is_symlink() and manifest.stat().st_size <= 65536, 'Missing or unsafe manifest')
    raw_manifest = manifest.read_bytes()
    require(json.loads(raw_manifest) == expected, 'Full archive manifest differs from the pinned contract')
    shutil.copyfile(manifest, snapshot / 'manifest.json')
    for item in expected['files']:
        file = source / item['name']
        require(file.is_file() and not file.is_symlink() and file.stat().st_size == item['bytes'], 'Missing or wrong-size archive file: ' + item['name'])
        target = snapshot / item['name']
        shutil.copyfile(file, target)
        require(hashlib.sha256(target.read_bytes()).hexdigest() == item['sha256'], 'Checksum mismatch: ' + item['name'])
        with target.open(newline='', encoding='utf-8') as stream:
            reader = csv.reader(stream)
            require(next(reader) == LAYOUT[item['name']], 'Wrong archive columns: ' + item['name'])
            count = sum(1 for row in reader if row)
            require(count == item['rows'], 'Wrong full archive row count: ' + item['name'])
    return expected, hashlib.sha256(raw_manifest).hexdigest()


def native_rows(snapshot, manifest):
    geo = json.loads((ROOT / 'contracts/geography/trace-link-geography-v2.json').read_text())
    topology = json.loads((ROOT / 'contracts/topology/trace-link-links-v1.json').read_text())
    cities = {c['referenceCityId'] for c in geo['cities']}
    links = {link['linkId'] for link in topology['links']}
    countries = {'RO', 'IT', 'DE', 'GB', 'FR', 'UA', 'ES', 'TR', 'PL', 'BG'}
    start, end = (datetime.fromisoformat(manifest[key].replace('Z', '+00:00')) for key in ('from', 'to_exclusive'))
    for file, kind in [('city_kpi_5m.csv', 'CITY'), ('link_kpi_5m.csv', 'LINK'), ('roaming_kpi_5m.csv', 'ROAMING')]:
        seen = set()
        with (snapshot / file).open(encoding='utf-8', newline='') as stream:
            header = stream.readline()
            for ordinal, raw in enumerate(stream, 1):
                fields = next(csv.reader([raw]))
                require(len(fields) == len(LAYOUT[file]), 'Wrong source row shape')
                source = dict(zip(LAYOUT[file], fields))
                stamp = datetime.fromisoformat(source['ts'].replace('Z', '+00:00'))
                require(source['ts'].endswith('Z') and start <= stamp < end and stamp.microsecond == 0 and stamp.timestamp() % 300 == 0, 'Invalid native UTC slot')
                if kind == 'CITY':
                    scope = source['city_id']; require(scope in cities, 'Unapproved city')
                elif kind == 'LINK':
                    require(source['link_id'] in links, 'Unapproved link'); scope = source['link_id'] + ':a-to-b'
                else:
                    require(source['country_id'] in countries and source['direction'] in ('in', 'out') and source['partner_id'] == source['country_id'] + '-DEMO-01', 'Unapproved partner/direction')
                    scope = source['partner_id'] + ':' + source['direction']
                identity = (scope, stamp)
                require(identity not in seen, 'Duplicate native window'); seen.add(identity)
                payload = {}
                for key, value in source.items():
                    if key in TEXT:
                        payload[key] = value
                    elif key.endswith('_hist'):
                        require(value.startswith('{') and value.endswith('}'), 'Invalid histogram')
                        bins = [int(x) for x in value[1:-1].split(',')]
                        require(len(bins) == 9 and all(0 <= x <= 9007199254740991 for x in bins), 'Invalid histogram bins')
                        payload[key] = bins
                    else:
                        number = Decimal(value)
                        require(number.is_finite() and number >= 0, 'Invalid numeric primitive')
                        if key.endswith(COUNTER_SUFFIXES):
                            require(number == number.to_integral_value() and number <= 9007199254740991, 'Unsafe counter')
                        payload[key] = value  # Decimal text retains the original precision in JSONB.
                for prefix in (('voice', 'data', 'dns', 'ho') if kind == 'CITY' else ('reg', 'data', 'voice', 'sms') if kind == 'ROAMING' else ()):
                    require(Decimal(source[prefix + '_success']) <= Decimal(source[prefix + '_attempts']), 'Success exceeds attempts')
                if kind == 'LINK':
                    require(Decimal(source['effective_capacity_gbps']) > 0 and Decimal(source['loss_pct']) <= 100, 'Invalid capacity/loss')
                require(kind != 'CITY' or sum(payload['voice_setup_hist']) == int(source['voice_success']), 'Setup histogram population mismatch')
                yield (manifest['dataset_id'], kind, scope, source['ts'], file, ordinal,
                       hashlib.sha256(raw.rstrip('\r\n').encode()).hexdigest(), json.dumps(payload, separators=(',', ':')))


def import_sql(manifest, checksum, stage):
    geo = json.loads((ROOT / 'contracts/geography/trace-link-geography-v2.json').read_text())
    topology = json.loads((ROOT / 'contracts/topology/trace-link-links-v1.json').read_text())
    dataset, catalogue = manifest['dataset_id'], geo['catalogueVersion']
    f, t = manifest['from'], manifest['to_exclusive']
    statements = ["\\set ON_ERROR_STOP on", 'BEGIN;', 'SELECT pg_advisory_xact_lock(hashtextextended(' + literal(dataset + ':reference-import') + ',0));',
        f"INSERT INTO app.trace_dataset VALUES ({literal(dataset)},{literal(manifest['generator_version'])},{manifest['seed']},true,{literal(f)},{literal(t)},300,{literal(manifest['timezone'])},{literal(checksum)},CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;",
        f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM app.trace_dataset WHERE dataset_id={literal(dataset)} AND manifest_sha256={literal(checksum)} AND generator_version={literal(manifest['generator_version'])} AND seed={manifest['seed']} AND timezone={literal(manifest['timezone'])} AND synthetic AND source_resolution_seconds=300 AND history_from={literal(f)} AND history_to_exclusive={literal(t)}) THEN RAISE EXCEPTION 'Conflicting immutable dataset'; END IF; END $$;",
        f"INSERT INTO app.trace_catalogue VALUES ({literal(catalogue)},{literal(dataset)},{literal(topology['topologyVersion'])},false,{literal(f)},NULL) ON CONFLICT DO NOTHING;",
        f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM app.trace_catalogue WHERE catalogue_version={literal(catalogue)} AND dataset_id={literal(dataset)} AND topology_version={literal(topology['topologyVersion'])}) THEN RAISE EXCEPTION 'Conflicting catalogue'; END IF; END $$;"
    ]
    def expect(table, condition):
        statements.append(f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM app.{table} WHERE {condition}) THEN RAISE EXCEPTION 'Conflicting immutable inventory'; END IF; END $$;")
    for city in geo['cities']:
        statements.append(f"INSERT INTO app.trace_inventory_city VALUES ({literal(catalogue)},{literal(city['referenceCityId'])},{literal(city['displayName'])},{city['latitude']},{city['longitude']},{city['demandShare']},true,{literal(f)},NULL,true) ON CONFLICT DO NOTHING;")
        expect('trace_inventory_city', f"catalogue_version={literal(catalogue)} AND city_id={literal(city['referenceCityId'])} AND display_name={literal(city['displayName'])} AND latitude={city['latitude']} AND longitude={city['longitude']} AND demand_share={city['demandShare']} AND active AND synthetic AND valid_from={literal(f)} AND valid_to IS NULL")
    for link in topology['links']:
        a, b = link['linkId'].split('-')
        statements.append(f"INSERT INTO app.trace_inventory_link VALUES ({literal(catalogue)},{literal(link['linkId'])},{literal(a)},{literal(b)},'SYNTHETIC_REFERENCE',{link['nominalCapacityGbps']},true,{literal(f)},NULL,true) ON CONFLICT DO NOTHING;")
        expect('trace_inventory_link', f"catalogue_version={literal(catalogue)} AND link_id={literal(link['linkId'])} AND endpoint_a_city_id={literal(a)} AND endpoint_b_city_id={literal(b)} AND nominal_capacity_gbps={link['nominalCapacityGbps']} AND active AND synthetic AND valid_from={literal(f)} AND valid_to IS NULL")
        for direction in link['directions']:
            statements.append(f"INSERT INTO app.trace_link_direction VALUES ({literal(catalogue)},{literal(link['linkId'])},{literal(direction['directionId'])},NULL,NULL,{link['nominalCapacityGbps']},{literal(f)},NULL,'declared-nominal-unmeasured') ON CONFLICT DO NOTHING;")
            expect('trace_link_direction', f"catalogue_version={literal(catalogue)} AND link_id={literal(link['linkId'])} AND direction_id={literal(direction['directionId'])} AND effective_capacity_gbps={link['nominalCapacityGbps']} AND capacity_valid_from={literal(f)} AND capacity_valid_to IS NULL AND measurement_source='declared-nominal-unmeasured'")
    for country in ('RO', 'IT', 'DE', 'GB', 'FR', 'UA', 'ES', 'TR', 'PL', 'BG'):
        for direction in ('in', 'out'):
            statements.append(f"INSERT INTO app.trace_partner VALUES ({literal(catalogue)},{literal(country+'-DEMO-01')},{literal(country)},{literal(direction)},true,{literal(f)},NULL,true) ON CONFLICT DO NOTHING;")
            expect('trace_partner', f"catalogue_version={literal(catalogue)} AND partner_id={literal(country+'-DEMO-01')} AND country_id={literal(country)} AND direction={literal(direction)} AND active AND synthetic AND valid_from={literal(f)} AND valid_to IS NULL")
    for table, count in [('trace_inventory_city',10),('trace_inventory_link',12),('trace_link_direction',24),('trace_partner',20)]:
        statements.append(f"DO $$ BEGIN IF (SELECT count(*) FROM app.{table} WHERE catalogue_version={literal(catalogue)})<>{count} THEN RAISE EXCEPTION 'Unexpected catalogue membership'; END IF; END $$;")
    statements += ['CREATE TEMP TABLE trace_import_stage (LIKE app.trace_reference_window INCLUDING DEFAULTS) ON COMMIT DROP;',
        "\\copy trace_import_stage FROM " + literal(stage) + ' CSV',
        "DO $$ BEGIN IF (SELECT count(*) FROM trace_import_stage)<>362880 OR EXISTS(SELECT 1 FROM trace_import_stage s JOIN app.trace_reference_window w USING(dataset_id,source_kind,scope_id,window_start) WHERE s.source_payload IS DISTINCT FROM w.source_payload OR s.source_row_sha256<>w.source_row_sha256 OR s.source_row_number<>w.source_row_number OR s.source_file<>w.source_file) THEN RAISE EXCEPTION 'Incomplete or conflicting archive projection'; END IF; END $$;",
        'INSERT INTO app.trace_reference_window SELECT * FROM trace_import_stage ON CONFLICT DO NOTHING;',
        "DO $$ BEGIN IF EXISTS(SELECT 1 FROM trace_import_stage s LEFT JOIN app.trace_reference_window w USING(dataset_id,source_kind,scope_id,window_start) WHERE w.dataset_id IS NULL OR s.source_payload IS DISTINCT FROM w.source_payload OR s.source_row_sha256 IS DISTINCT FROM w.source_row_sha256 OR s.source_row_number IS DISTINCT FROM w.source_row_number OR s.source_file IS DISTINCT FROM w.source_file) THEN RAISE EXCEPTION 'Archive identity collision or incomplete projection'; END IF; END $$;",
        f"DO $$ BEGIN IF (SELECT count(*) FROM app.trace_reference_window WHERE dataset_id={literal(dataset)})<>362880 THEN RAISE EXCEPTION 'Unexpected dataset membership'; END IF; END $$;",
        f"INSERT INTO app.trace_reference_import(dataset_id,manifest_sha256,native_rows) VALUES ({literal(dataset)},{literal(checksum)},362880) ON CONFLICT DO NOTHING;", f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM app.trace_reference_import WHERE dataset_id={literal(dataset)} AND manifest_sha256={literal(checksum)} AND native_rows=362880) THEN RAISE EXCEPTION 'Conflicting import identity'; END IF; END $$;", 'COMMIT;']
    return '\n'.join(statements) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--compose', action='store_true',
                        help='Import into the local Compose incident database without a host PostgreSQL client')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='trace-reference-import-') as directory:
        snapshot = Path(directory)
        manifest, checksum = validate(args.archive, snapshot)
        stage = snapshot / 'projection.csv'
        with stage.open('w', newline='', encoding='utf-8') as output:
            writer = csv.writer(output); count = 0
            for row in native_rows(snapshot, manifest): writer.writerow(row); count += 1
        require(count == 362880, 'Incomplete full archive')
        if not args.validate_only:
            run_import(manifest, checksum, stage, args.compose)
        print(json.dumps({'datasetId': manifest['dataset_id'], 'manifestSha256': checksum, 'nativeRows': count,
                          'mode': 'validation' if args.validate_only else 'incident-owned-import', 'catalogueActivated': False}))


def run_import(manifest, checksum, stage, compose=False):
    sql = import_sql(manifest, checksum, stage)
    if not compose:
        subprocess.run(['psql', '-X', '-q', '--no-password'], input=sql, text=True, check=True)
        return
    # Stream the validated snapshot through psql. Host paths need not exist in the
    # container, and no source files or credentials are copied into it.
    before, after = sql.split("\\copy trace_import_stage FROM " + literal(stage) + ' CSV\n', 1)
    with tempfile.TemporaryFile(mode='w+', encoding='utf-8', newline='') as script:
        script.write(before + 'COPY trace_import_stage FROM STDIN WITH (FORMAT csv);\n')
        # psql's end-of-copy marker must use the same line ending as the CSV.
        # Normalize csv.writer's CRLF records to the SQL script's LF convention.
        with stage.open(encoding='utf-8') as source:
            shutil.copyfileobj(source, script)
        script.write('\\.\n' + after)
        script.seek(0)
        subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-X', '-q',
                        '--no-password', '-U', 'incidents_app', '-d', 'incidents_db'],
                       stdin=script, cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
