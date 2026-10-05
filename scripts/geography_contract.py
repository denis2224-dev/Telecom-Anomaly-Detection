"""Offline Day 1 companion authority and coverage contract, not a producer or detector."""
import copy
import hashlib
import json
from datetime import datetime, timedelta
from observation_contract import ROOT, read_json, validate_observation, ObservationBatch
from jsonschema import Draft202012Validator, FormatChecker

ROLES = {'VOLTE_IMS': ('VOLTE', 'IMS', True), 'VOLTE_TRANSPORT': ('VOLTE', 'TRANSPORT', True),
         'SMS_SMSC': ('SMS', 'SMSC', True), 'SMS_TRANSPORT': ('SMS', 'TRANSPORT', False)}
def require(valid, reason):
    if not valid: raise ValueError(reason)

def schema_validate(kind, value):
    path = 'geography/geography-catalogue-v1.schema.json' if kind == 'geography' else 'coverage/scope-window-coverage-v1.schema.json'
    schema = read_json(ROOT / 'contracts' / path)
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(value)

def authority_scopes(authority):
    require(set(authority) == {'topologyVersion', 'scopes'} and isinstance(authority['topologyVersion'], str)
            and authority['topologyVersion'].strip(), 'Invalid topology fields')
    scopes = {}
    import re
    def identifier(value):
        require(isinstance(value, str) and len(value) <= 64
                and re.fullmatch(r'[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*', value), 'Invalid authority identifier')
    require(isinstance(authority['scopes'], list) and authority['scopes'], 'Empty authority')
    for scope in authority['scopes']:
        require(set(scope) == {'scopeId', 'service', 'serviceSourceId', 'nodes'}, 'Invalid scope fields')
        identifier(scope['scopeId']); identifier(scope['serviceSourceId'])
        require(scope['scopeId'] not in scopes, 'Duplicate scopeId')
        require(scope['service'] in {'VOLTE', 'SMS'} and isinstance(scope['nodes'], list) and scope['nodes'], 'Invalid service/dependencies')
        nodes, sources = set(), set()
        for node in scope['nodes']:
            require(set(node) == {'nodeId', 'sourceId'}, 'Invalid node fields')
            identifier(node['nodeId']); identifier(node['sourceId'])
            require(node['nodeId'] not in nodes, 'Duplicate authority nodeId')
            require(node['sourceId'] not in sources, 'Ambiguous NODE reporter')
            nodes.add(node['nodeId']); sources.add(node['sourceId'])
        scopes[scope['scopeId']] = scope
    return scopes

def validate_catalogue(catalogue, authority):
    schema_validate('geography', catalogue)
    scopes = authority_scopes(authority)
    require(catalogue['topologyVersion'] == authority['topologyVersion'], 'Topology version mismatch')
    activation = catalogue['activation']
    require((activation['status'] == 'ACTIVE') == (activation['effectiveFrom'] is not None), 'Invalid activation')
    cities = {}
    for city in catalogue['cities']:
        require(city['cityId'] not in cities, 'Duplicate cityId'); cities[city['cityId']] = city
    require(len(cities) == 10, 'Exactly ten cities required')
    nodes = {}
    for node in catalogue['nodes']:
        require(node['nodeId'] not in nodes, 'Duplicate nodeId'); nodes[node['nodeId']] = node
    roots = [n for n in nodes.values() if n['parentId'] is None]
    require(len(roots) == 1 and roots[0] == {'nodeId':'MD','parentId':None,'cityId':None,'type':'COUNTRY'}, 'One Moldova root required')
    for node in nodes.values():
        if node['parentId'] is None: continue
        require(node['parentId'] in nodes, 'Dangling parent')
        seen, target = set(), node
        while target is not None:
            require(target['nodeId'] not in seen, 'Containment cycle')
            seen.add(target['nodeId'])
            target = nodes.get(target['parentId'])
        parent = nodes[node['parentId']]
        require(parent['cityId'] is None or node['cityId'] == parent['cityId'], 'Cross-city parent')
        require(node['cityId'] is None or node['cityId'] in cities, 'Unknown city ownership')
        expected = {'CITY':'COUNTRY', 'AGGREGATION':'CITY', 'SITE':'AGGREGATION', 'CELL':'SITE',
                    'IMS':'CITY', 'SMSC':'CITY', 'TRANSPORT':'CITY'}.get(node['type'])
        if node['cityId'] is None:
            require(node['type'] in {'IMS','SMSC','TRANSPORT'}, 'Only legacy dependencies may be unallocated')
            expected = 'COUNTRY'
        require(parent['type'] == expected, 'Invalid containment depth')
    for city in cities:
        for kind in ['CITY','AGGREGATION','SITE','CELL','IMS','SMSC','TRANSPORT']:
            require(sum(n['cityId'] == city and n['type'] == kind for n in nodes.values()) == 1, 'Equal minimum footprint required')
    legacy = authority_scopes(read_json(ROOT / 'contracts/topology/demo-scopes-v2.json'))
    for key, value in legacy.items(): require(scopes.get(key) == value, 'Legacy authority changed')
    bindings = {}
    for binding in catalogue['scopes']:
        sid, city = binding['scopeId'], binding['cityId']
        require(sid not in bindings, 'Duplicate scopeId')
        require(sid in scopes, 'Unknown scope')
        scope = scopes[sid]
        require(all(n['sourceId'] != scope['serviceSourceId'] for n in scope['nodes']), 'SERVICE/NODE source collision in coverage profile')
        require(binding['legacy'] == (sid in legacy) == (city is None), 'Invalid legacy mapping')
        if not binding['legacy']: require(city in cities and sid == scope['service']+'-MD-'+city, 'Invalid city/service scope')
        roles = {}
        dependencies = {n['nodeId']:n for n in scope['nodes']}
        for role in binding['roles']:
            name, target = role['role'], role['nodeId']
            require(ROLES[name][0] == scope['service'], 'Wrong service role')
            require(name not in roles, 'Ambiguous role')
            require(target in dependencies and target in nodes, 'Unknown node')
            require(nodes[target]['type'] == ROLES[name][1], 'Wrong role capability')
            require(nodes[target]['cityId'] == city, 'Cross-city role target')
            roles[name] = target
        for role, (service, _, required) in ROLES.items():
            if service == scope['service'] and required: require(role in roles, 'Missing role')
        require(set(roles.values()) == set(dependencies), 'Unmapped authority dependency')
        footprint = binding['footprintNodeIds']
        require(not footprint if binding['legacy'] else len(footprint) == 1 and footprint[0] in nodes
                and nodes[footprint[0]]['type'] == 'CELL' and nodes[footprint[0]]['cityId'] == city, 'Invalid footprint')
        bindings[sid] = binding
    require(set(bindings) == set(scopes), 'Catalogue/authority scope mismatch')
    require(sum(not b['legacy'] for b in bindings.values()) == 20, 'Exactly twenty city scopes required')
    for city in cities:
        for service in ['VOLTE', 'SMS']: require(service+'-MD-'+city in bindings, 'Both services required')
    require({n['nodeId'] for s in scopes.values() for n in s['nodes']} ==
            {n['nodeId'] for n in nodes.values() if n['type'] in {'IMS','SMSC','TRANSPORT'}}, 'Dependency metadata/authority mismatch')
    return bindings

def resolve(catalogue, authority, scope_id, role):
    bindings = validate_catalogue(catalogue, authority)
    scopes = authority_scopes(authority)
    require(role in ROLES and ROLES[role][0] == scopes[scope_id]['service'], 'Wrong service role')
    targets = [r['nodeId'] for r in bindings[scope_id]['roles'] if r['role'] == role]
    require(len(targets) == 1, 'Missing/ambiguous role')
    return next(n for n in scopes[scope_id]['nodes'] if n['nodeId'] == targets[0])

def expected_sources(catalogue, authority, scope_id):
    scope = authority_scopes(authority)[scope_id]
    binding = next(b for b in catalogue['scopes'] if b['scopeId'] == scope_id)
    nodes = {n['nodeId']:n['sourceId'] for n in scope['nodes']}
    return sorted({scope['serviceSourceId']} | {nodes[r['nodeId']] for r in binding['roles'] if ROLES[r['role']][2]})

def digest(values):
    return hashlib.sha256(json.dumps(values, ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest()

def coverage_id(scope_id, start, topology_version, catalogue_version):
    return digest(['scope-window-coverage-v1', scope_id, start, topology_version, catalogue_version])

def coverage_snapshot(catalogue, authority, scope_id, start, receipts):
    """Golden-fixture reference only: callers supply accepted receipts from ONE finalized UTC minute."""
    validate_catalogue(catalogue, authority)
    scopes = authority_scopes(authority)
    scope = scopes[scope_id]
    end = (datetime.fromisoformat(start) + timedelta(minutes=1)).isoformat().replace('+00:00','Z')
    received, usable, reasons = set(), set(), {}
    expected = expected_sources(catalogue, authority, scope_id)
    binding = next(b for b in catalogue['scopes'] if b['scopeId'] == scope_id)
    node_roles = {r['nodeId']:r['role'] for r in binding['roles']}
    batch = ObservationBatch(scopes)
    for event in receipts:
        validate_observation(event, scopes)
        require(event['scopeId'] == scope_id and event['windowStart'] == start and event['windowEnd'] == end, 'Wrong coverage interval/scope')
        batch.accept(event)
        if event['kind'] == 'HEARTBEAT': continue
        source = event['sourceId']; received.add(source)
        if event['quality'] != 'COMPLETE':
            reasons[source] = 'REPORTED_MISSING' if event['quality'] == 'MISSING' else 'INCOMPLETE'
            continue
        metrics = event.get('metrics', {})
        required = [] if event['kind'] == 'SERVICE' else {
            'VOLTE_IMS':['cpuPct'], 'VOLTE_TRANSPORT':['packetLossRatio'],
            'SMS_SMSC':['queueDepth','oldestPendingAgeSeconds'], 'SMS_TRANSPORT':['packetLossRatio']
        }[node_roles[event['nodeId']]]
        if any(k not in metrics or not isinstance(metrics[k], (int,float)) for k in required):
            reasons[source] = 'MEASUREMENT_MISSING'
        else: usable.add(source)
    for source in set(expected)-received: reasons[source] = 'NOT_RECEIVED'
    result = {'schemaVersion':1,'coverageId':coverage_id(scope_id,start,authority['topologyVersion'],catalogue['catalogueVersion']),
              'windowId':digest([scope_id,start,2]), 'scopeId':scope_id,'service':scope['service'],'windowStart':start,'windowEnd':end,
              'topologyVersion':authority['topologyVersion'],'catalogueVersion':catalogue['catalogueVersion'],
              'expectedSourceIds':expected,'receivedSourceIds':sorted(received),'usableSourceIds':sorted(usable),
              'sourceIssues':[{'sourceId':s,'reason':reasons[s]} for s in sorted(reasons)],'synthetic':True}
    validate_coverage(result, catalogue, authority)
    return result

def validate_coverage(value, catalogue, authority):
    validate_catalogue(catalogue, authority)
    schema_validate('coverage', value)
    scope = authority_scopes(authority)[value['scopeId']]
    require(value['service'] == scope['service'], 'Coverage service mismatch')
    require((datetime.fromisoformat(value['windowEnd'])-datetime.fromisoformat(value['windowStart'])).total_seconds() == 60, 'Expected one minute coverage')
    require(value['topologyVersion'] == authority['topologyVersion'] and value['catalogueVersion'] == catalogue['catalogueVersion'], 'Coverage version mismatch')
    require(value['coverageId'] == coverage_id(value['scopeId'],value['windowStart'],value['topologyVersion'],value['catalogueVersion']), 'Coverage identity mismatch')
    for field in ['expectedSourceIds','receivedSourceIds','usableSourceIds']:
        require(value[field] == sorted(set(value[field])), 'Coverage sets must be sorted/unique')
    require(value['expectedSourceIds'] == expected_sources(catalogue,authority,value['scopeId']), 'Coverage expected source mismatch')
    expected, received, usable = (set(value[k]) for k in ['expectedSourceIds','receivedSourceIds','usableSourceIds'])
    authorized = {scope['serviceSourceId']} | {n['sourceId'] for n in scope['nodes']}
    require(usable <= received <= authorized, 'Coverage unauthorized/subset violation')
    issues = value['sourceIssues']
    issue_ids = [i['sourceId'] for i in issues]
    require(issue_ids == sorted(set(issue_ids)) and set(issue_ids) == (expected | received)-usable, 'Coverage missing quality reasons')
    for issue in issues:
        require(issue['sourceId'] in authorized and (issue['sourceId'] in expected-received
                if issue['reason'] == 'NOT_RECEIVED' else issue['sourceId'] in received-usable), 'Coverage issue receipt mismatch')

def patch(value, updates):
    result = copy.deepcopy(value)
    for pointer, replacement in updates.items():
        parts = pointer.lstrip('/').split('/'); target = result
        for p in parts[:-1]: target = target[int(p)] if isinstance(target,list) else target[p]
        key = int(parts[-1]) if isinstance(target,list) else parts[-1]
        target[key] = replacement
    return result

def validate_day1_contracts():
    catalogue = read_json(ROOT / 'contracts/geography/demo-geography-v1.json')
    authority = read_json(ROOT / 'contracts/topology/geographic-scopes-v2.json')
    validate_catalogue(catalogue,authority)
    events = read_json(ROOT / 'contracts/fixtures/geography/complete-city-observations-v1.json')
    batch = ObservationBatch(authority_scopes(authority))
    for event in events: require(batch.accept(event) == 'ACCEPTED', 'Duplicate golden observation')
    invalid = read_json(ROOT / 'contracts/fixtures/geography/invalid-catalogue-cases-v1.json')
    for case in invalid:
        try: validate_catalogue(patch(catalogue,case['patch']),authority)
        except ValueError as error: require(case['reason'] in str(error), 'Wrong rejection reason: '+case['name'])
        else: raise ValueError('Invalid catalogue accepted: '+case['name'])
    golden = read_json(ROOT / 'contracts/fixtures/coverage/coverage-cases-v1.json')
    for case in golden:
        require(coverage_snapshot(catalogue,authority,case['coverage']['scopeId'],case['coverage']['windowStart'],case['receipts']) == case['coverage'], 'Coverage golden mismatch')
    print(f'PASS: 10 cities, 20 geographic scopes + 2 legacy, {len(events)} observations, {len(invalid)} rejected catalogues, {len(golden)} coverage cases')
