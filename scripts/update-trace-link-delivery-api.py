from pathlib import Path
import re
import textwrap
import yaml

class NoAliasesDumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True

path = Path('contracts/openapi/incident-api.yaml')
original = path.read_text()
doc = yaml.safe_load(original)
paths = doc['paths']
for route, method in (('/api/v2/exports/metrics.csv', 'get'),
                      ('/api/notifications/push/config', 'get'),
                      ('/api/notifications/push-subscriptions', 'post'),
                      ('/api/notifications/push-subscriptions/{id}', 'delete')):
    operation = paths[route][method]
    operation['x-implementation-status'] = 'backend-implemented'
    for status, ref in (('400', 'BadRequest'), ('401', 'Unauthenticated'), ('403', 'Forbidden')):
        operation['responses'][status] = {'$ref': '#/components/responses/' + ref}
paths['/api/v2/exports/metrics.csv']['get']['responses'].update({
    '404': {'$ref': '#/components/responses/NotFound'},
    '409': {'description': 'Source contract mismatch'}})
paths['/api/notifications/push-subscriptions']['post']['responses'].update({
    '201': {'description': 'Current analyst subscription stored', 'content': {'application/json': {
        'schema': {'type': 'object', 'required': ['id'], 'properties': {'id': {'type': 'string', 'format': 'uuid'}}}}}},
    '409': {'description': 'Push disabled, subscription limit, or endpoint owned by another analyst'}})
for name in ('endpoint', 'p256dh', 'auth'):
    doc['components']['schemas']['PushSubscriptionInput']['properties'][name]['minLength'] = 1
paths['/api/simulator/scenario-definitions'] = {'get': {
    'operationId': 'listScenarioDefinitions', 'tags': ['Simulator'],
    'responses': {'200': {'description': 'Implemented public scenario definitions', 'content': {
        'application/json': {'schema': {'type': 'array', 'items': {'type': 'object',
            'required': ['type', 'scopeTypes', 'durationSeconds', 'minimumSeed', 'maximumConcurrentPerScope'],
            'properties': {'type': {'type': 'string'}, 'scopeTypes': {'type': 'array', 'items': {'type': 'string'}},
                           'durationSeconds': {'type': 'integer', 'enum': [480]},
                           'minimumSeed': {'type': 'integer', 'enum': [0]},
                           'maximumConcurrentPerScope': {'type': 'integer', 'enum': [1]}}}}}}},
        '401': {'$ref': '#/components/responses/Unauthenticated'},
        '403': {'$ref': '#/components/responses/Forbidden'}}}}
types = """VOLTE_SETUP_FAILURE VOLTE_SETUP_DELAY VOLTE_MEDIA_QUALITY VOLTE_HANDOVER_FAILURE
TRANSPORT_LINK_OVERLOAD TRANSPORT_PACKET_LOSS TRANSPORT_RTT_INCREASE TRANSPORT_CAPACITY_REDUCTION
ROAMING_REGISTRATION_FAILURE ROAMING_DATA_FAILURE ROAMING_PARTNER_DEGRADATION
MOBILE_DATA_ACTIVATION_FAILURE MOBILE_DATA_DNS_FAILURE SMS_DELIVERY_DELAY SMS_QUEUE_CONGESTION
SMS_ROUTE_FAILURE NORMAL_CONTROL TELEMETRY_GAP VOLTE_IMS_OVERLOAD SMS_QUEUE_DELAY""".split()

def extend(node):
    if isinstance(node, dict):
        if isinstance(node.get('enum'), list) and 'VOLTE_IMS_OVERLOAD' in node['enum']:
            node['enum'] = types
        for value in node.values():
            extend(value)
    elif isinstance(node, list):
        for value in node:
            extend(value)

extend(doc)
def contains_scenario_enum(node):
    if isinstance(node, dict):
        return (isinstance(node.get('enum'), list) and 'VOLTE_IMS_OVERLOAD' in node['enum']) or any(
            contains_scenario_enum(value) for value in node.values())
    if isinstance(node, list):
        return any(contains_scenario_enum(value) for value in node)
    return False

def replace_stanza(text, key, value, indent):
    block = textwrap.indent(yaml.dump({key: value}, Dumper=NoAliasesDumper, sort_keys=False, allow_unicode=True, width=120), ' ' * indent)
    pattern = re.compile(r'^' + ' ' * indent + re.escape(key) + r':\n.*?(?=^ {0,' + str(indent) + r'}\S|\Z)', re.M | re.S)
    if pattern.search(text):
        return pattern.sub(lambda match: block, text, count=1)
    if indent == 2:
        return text.replace('components:\n', block + 'components:\n', 1)
    raise RuntimeError('Missing contract stanza: ' + key)

text = original
updated_routes = {'/api/v2/exports/metrics.csv', '/api/notifications/push/config',
                  '/api/notifications/push-subscriptions', '/api/notifications/push-subscriptions/{id}',
                  '/api/simulator/scenario-definitions'}
for key, value in doc['paths'].items():
    if key in updated_routes or contains_scenario_enum(value):
        text = replace_stanza(text, key, value, 2)
for key, value in doc['components']['schemas'].items():
    if key == 'PushSubscriptionInput' or contains_scenario_enum(value):
        text = replace_stanza(text, key, value, 4)
if yaml.safe_load(text) != doc:
    raise RuntimeError('Preserved contract differs from intended API update')
path.write_text(text)
Path('apps/dashboard/api/incident-api.yaml').write_text(text)
