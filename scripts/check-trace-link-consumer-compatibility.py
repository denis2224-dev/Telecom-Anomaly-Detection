"""Legacy V006 storage comparison only; canonical V008 ingestion/API compatibility is not evaluated."""
import argparse
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import re
import subprocess

from jsonschema import Draft202012Validator

from trace_link_projection_contract import FIXTURE, validate_message

ROOT = Path(__file__).resolve().parents[1]
CONSUMER = '923a9f009ce402b84cc26882466e7c2e3989a136'
PREFIX = 'services/incident-service/src/main/'


def inspect(ref):
    files = {
        'migration': PREFIX + 'resources/db/migration/V006__trace_link_read_models.sql',
        'registry': PREFIX + 'java/md/utm/telecom/tracelink/metrics/MetricDefinitionRegistry.java',
        'response': PREFIX + 'java/md/utm/telecom/tracelink/metrics/MetricSeriesResponse.java',
        'query': PREFIX + 'java/md/utm/telecom/tracelink/metrics/MetricQueryService.java',
        'rollup': PREFIX + 'java/md/utm/telecom/tracelink/metrics/MetricRollupRepository.java',
    }
    blobs = {name: subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=ROOT)
             for name, path in files.items()}
    texts = {name: data.decode('utf-8') for name, data in blobs.items()}
    sql = texts['migration'].split('CREATE TABLE app.trace_metric_window (', 1)[1].split('\n);', 1)[0]

    def allowed(column):
        match = re.search(rf'CHECK \({column} IN \(([^)]+)\)\)', sql)
        if not match:
            raise ValueError('Consumer constraint changed: ' + column)
        return re.findall(r"'([^']+)'", match[1])

    quality_width = int(re.search(r'quality VARCHAR\((\d+)\)', sql)[1])
    definition = re.search(r'new Definition\("([^"]+)", "([^"]+)".*?"(trace-link-[^"]+)"\)',
                           texts['registry'], re.S)
    if not definition:
        raise ValueError('Consumer registry shape changed; inspect before comparing')
    domain, metric, policy = definition.groups()
    fixture = json.loads(FIXTURE.read_text(encoding='utf-8'), parse_float=Decimal)['nativeExample']
    validate_message(fixture)
    # These are isolated contract checks against the actual V006 enum/width restrictions.
    validator = Draft202012Validator({'type': 'object', 'properties': {
        'domain': {'enum': allowed('domain')},
        'quality': {'type': 'string', 'enum': allowed('quality'), 'maxLength': quality_width},
        'scope_type': {'enum': allowed('scope_type')},
        'aggregation_type': {'enum': allowed('aggregation_type')},
    }})
    row = {'domain': fixture['domain'], 'quality': fixture['quality']['state'],
           'scope_type': fixture['scope']['type'], 'aggregation_type': fixture['aggregation']}
    direct_errors = sorted({str(error.path[0]) + ': ' + error.message for error in validator.iter_errors(row)})
    mapped = dict(row, domain=domain)
    mapped_errors = sorted({str(error.path[0]) + ': ' + error.message for error in validator.iter_errors(mapped)})
    assert direct_errors and mapped_errors, 'Expected consumer incompatibility disappeared; obtain a new review'
    assert not list(validator.iter_errors(dict(mapped, quality='VALID'))), 'Reusable column shape changed'
    missing = [field for field in ('projectionId', 'sourceNamespace', 'sourceQuality', 'sourceCoverage',
                                    'nullReason', 'provenance', 'projectionIds', 'expectedSlots', 'receivedSlots')
               if not re.search(r'\b' + field + r'\b', texts['response'])]
    return {
        'consumerSha': ref, 'consumerAgreement': 'PENDING_REVIEW',
        'scope': 'LEGACY_V006_ONLY',
        'canonicalV008Compatibility': 'NOT_EVALUATED: execute canonical ingestion and API acceptance tests',
        'method': 'Read-only Git blobs and isolated JSON Schema checks of V006 restrictions; no consumer DB/API invoked',
        'compatibility': 'INCOMPATIBLE', 'directFixtureColumnErrors': direct_errors,
        'afterProposedDomainTranslationErrors': mapped_errors,
        'registry': {'domain': domain, 'metric': metric, 'policy': policy},
        'producer': {'domain': fixture['domain'], 'metric': fixture['metricId'], 'policy': fixture['policyVersion']},
        'qualityColumn': {'width': quality_width, 'allowed': allowed('quality')},
        'missingReadDtoFields': missing,
        'topologyComparison': 'NOT_EVALUATED: this checker inspects legacy V006 storage restrictions only',
        'historicalV006RollupWarnings': ['Non-VALID source is collapsed to PARTIAL',
                             'Zero denominator may be reported VALID',
                             'Effective bucket bounds include native slots outside requested partial edges',
                             'coverage uses SUM(source coverage), rather than independent slot count',
                             'Latest revision selection is not conflicting-retry validation'],
        'compatibleColumns': ['CITY', 'RATIO_OF_SUMS', 'PERCENT', '300 seconds',
                              'NUMERIC(22,6) fits supported integer primitives'],
        'legacyV006StorageLimitations': ['No dedicated projection ID, payload bytes/hash, source namespace, source quality/coverage or provenance columns',
                               'JSONB payload can retain provenance, but current read DTO does not expose it'],
        'sourceFiles': [{'path': files[name], 'sha256': hashlib.sha256(data).hexdigest()}
                        for name, data in blobs.items()],
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consumer-ref', default=CONSUMER)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch('[0-9a-f]{40}', args.consumer_ref):
        parser.error('Use an exact consumer commit SHA')
    report = inspect(args.consumer_ref)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('PASS: isolated compatibility checks reproduced consumer incompatibilities; approval PENDING_REVIEW')
