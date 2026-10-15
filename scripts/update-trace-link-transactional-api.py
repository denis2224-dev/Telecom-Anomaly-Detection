from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / 'contracts/openapi/incident-api.yaml'
text = path.read_text()
text = text.replace('  version: 0.4.0', '  version: 0.5.0', 1)
for route in ['/api/notifications', '/api/notifications/{id}/read', '/api/v2/transport/snapshot', '/api/v2/roaming/snapshot']:
    start = text.index('  ' + route + ':\n')
    next_path = text.find('\n  /', start + 1)
    if next_path == -1:
        next_path = text.index('\ncomponents:', start)
    block = text[start:next_path].replace('x-implementation-status: planned-day-3', 'x-implementation-status: backend-implemented')
    if route.endswith('/snapshot'):
        block = block.replace("#/components/schemas/TraceSnapshot'", "#/components/schemas/TraceSnapshotList'")
        if "        '400':" not in block:
            block = block.replace("        '401':", "        '400': {$ref: '#/components/responses/BadRequest'}\n        '404': {$ref: '#/components/responses/NotFound'}\n        '409': {description: Source contract mismatch}\n        '403': {$ref: '#/components/responses/Forbidden'}\n        '401':", 1)
    elif route == '/api/notifications':
        if "        '400':" not in block:
            block = block.replace("        '401':", "        '400': {$ref: '#/components/responses/BadRequest'}\n        '401':", 1)
    text = text[:start] + block + text[next_path:]
new_route = """
  /api/v2/domain-incidents/{id}:
    get:
      operationId: getTraceDomainIncident
      tags: [Incidents]
      x-implementation-status: backend-implemented
      parameters:
      - {name: id, in: path, required: true, schema: {type: string, format: uuid}}
      responses:
        '200': {description: Durable domain episode and primitive evidence, content: {application/json: {schema: {$ref: '#/components/schemas/TraceDomainIncident'}}}}
        '401': {$ref: '#/components/responses/Unauthenticated'}
        '403': {$ref: '#/components/responses/Forbidden'}
        '404': {$ref: '#/components/responses/NotFound'}
"""
if '  /api/v2/domain-incidents/{id}:\n' not in text:
    text = text.replace('\ncomponents:', new_route + '\ncomponents:', 1)
new_schemas = """
    TraceSnapshotList:
      type: object
      required: [datasetId, domain, at, synthetic, items]
      properties:
        datasetId: {type: string}
        domain: {type: string, enum: [transport, roaming]}
        at: {type: string, format: date-time}
        synthetic: {type: boolean}
        items: {type: array, maxItems: 100, items: {$ref: '#/components/schemas/TraceSnapshot'}}
    TraceDomainIncident:
      type: object
      required: [id, technicalState, severity, version, evidence]
      properties:
        id: {type: string, format: uuid}
        technicalState: {type: string, enum: [ONGOING, UNKNOWN, RECOVERED]}
        severity: {type: string, enum: [MEDIUM, HIGH, CRITICAL]}
        version: {type: integer, format: int64}
        evidence: {$ref: '#/components/schemas/TraceDomainIncidentEvidence'}
    TraceDomainIncidentEvidence:
      type: object
      required: [detection, primitiveEvidence, causeStatus, probableCause, causeConfidence]
      properties:
        detection:
          type: object
          required: [schemaVersion, datasetId, synthetic, domain, metric, metricVersion, scopeType, scopeId, policyVersion, topologyVersion, episodeId, detectionId, sequence, phase, severity, firstObservedAt, windowStart, windowEnd, detectedAt, revision]
          properties:
            schemaVersion: {type: integer, enum: [1]}
            datasetId: {type: string}
            synthetic: {type: boolean, enum: [true]}
            domain: {type: string, enum: [transport, roaming, mobile-data]}
            metric: {type: string}
            metricVersion: {type: string}
            scopeType: {type: string, enum: [CITY, LINK_DIRECTION, PARTNER_DIRECTION]}
            scopeId: {type: string}
            policyVersion: {type: string}
            topologyVersion: {type: string}
            episodeId: {type: string}
            detectionId: {type: string}
            sequence: {type: integer, format: int64}
            phase: {type: string, enum: [OPEN, UPDATE, UNKNOWN, RECOVERY]}
            severity: {type: string, enum: [MEDIUM, HIGH, CRITICAL]}
            firstObservedAt: {type: string, format: date-time}
            windowStart: {type: string, format: date-time}
            windowEnd: {type: string, format: date-time}
            detectedAt: {type: string, format: date-time}
            revision: {type: integer, minimum: 0}
        primitiveEvidence:
          type: object
          required: [payload, quality, coverage, numerator, denominator]
          properties:
            payload: {type: object, additionalProperties: true}
            quality: {type: string, enum: [VALID, PARTIAL, MISSING, STALE]}
            coverage: {type: number, minimum: 0, maximum: 1}
            numerator: {type: number, nullable: true}
            denominator: {type: number, nullable: true}
        causeStatus: {type: string, enum: [UNDETERMINED]}
        probableCause: {type: string, nullable: true}
        causeConfidence: {type: string, nullable: true}
"""
if '    TraceSnapshotList:\n' not in text:
    text = text.replace('    TraceSnapshot:\n', new_schemas + '    TraceSnapshot:\n', 1)
text = text.replace("quality: {type: string, enum: [VALID, PARTIAL, MISSING, STALE]}",
                    "quality: {type: string, enum: [VALID, PARTIAL, MISSING, STALE, INSUFFICIENT_EVIDENCE]}")
path.write_text(text)
for name in ['tests/test_trace_link_api_contract.py', 'tests/test_shared_contract.py']:
    test = root / name
    test.write_text(test.read_text().replace('"0.4.0"', '"0.5.0"'))
print('Updated canonical contract; copy it and regenerate Angular types next')
