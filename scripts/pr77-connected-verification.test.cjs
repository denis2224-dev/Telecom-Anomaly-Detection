const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { parseArgs, composeArgs, assertOwned, createCandidate, removeCredentials, geographyEffectiveFrom } = require('./pr77-connected-verification.cjs');

test('geography activation always aligns complete UTC minute boundaries', () => {
  const inputs = ['2026-10-10T16:37:59.999Z', '2026-01-01T00:00:00.001Z', '2026-06-07T03:42:17.123Z'];
  for (const input of inputs) {
    const now = Date.parse(input), effective = geographyEffectiveFrom(now);
    assert.match(effective, /:00\.000Z$/);
    assert.equal(Date.parse(effective) % 60000, 0);
    assert.equal(Date.parse(effective), Math.floor(now / 60000) * 60000 - 15 * 60000);
  }
  assert.throws(() => geographyEffectiveFrom(NaN));
});

test('requires explicit fresh absolute directories and a bounded project name', () => {
  assert.throws(() => parseArgs([]));
  assert.throws(() => parseArgs(['--project', '../other', '--private-dir', os.tmpdir(), '--output-dir', os.tmpdir()]));
  assert.throws(() => parseArgs(['--project', 'pr77-test', '--private-dir', 'relative', '--output-dir', os.tmpdir()]));
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'pr77-unit-'));
  try {
    const privateDir = path.join(parent, 'private');
    const outputDir = path.join(parent, 'output');
    const parsed = parseArgs(['--project', 'pr77-test', '--private-dir', privateDir, '--output-dir', outputDir]);
    assert.equal(parsed.projectName, 'pr77-test');
    assert.throws(() => parseArgs(['--project', 'pr77-test', '--private-dir', privateDir, '--output-dir', path.join(privateDir, 'nested')]));
    fs.mkdirSync(privateDir);
    assert.throws(() => parseArgs(['--project', 'pr77-test', '--private-dir', privateDir, '--output-dir', outputDir]));
  } finally { fs.rmSync(parent, { recursive: true }); }
});

test('every compose operation carries explicit project, environment and config', () => {
  const args = composeArgs({ projectName: 'pr77-unit', envFile: '/private/env', composeFiles: ['/private/compose.json'] }, ['down', '--volumes']);
  assert.deepEqual(args, ['compose', '--project-name', 'pr77-unit', '--env-file', '/private/env', '--file', '/private/compose.json', '--profile', 'app', 'down', '--volumes']);
});

test('cleanup refuses resources lacking both project and nonce ownership', () => {
  const context = { projectName: 'pr77-unit', ownerLabelName: 'io.telecom.pr77.owner', ownerLabelValue: 'nonce' };
  assert.doesNotThrow(() => assertOwned({ 'com.docker.compose.project': 'pr77-unit', 'io.telecom.pr77.owner': 'nonce' }, context));
  assert.throws(() => assertOwned({ 'com.docker.compose.project': 'pr77-unit' }, context));
  assert.throws(() => assertOwned({ 'com.docker.compose.project': 'other', 'io.telecom.pr77.owner': 'nonce' }, context));
});

test('candidate rewrites production dependencies and isolated ports consistently', () => {
  const services = Object.fromEntries(['postgres', 'kafka', 'event-generator', 'processor', 'history-bootstrap', 'incident-service', 'ml-service', 'keycloak', 'proxy'].map(name => [name, { environment: {}, volumes: [] }]));
  services.processor.build = { context: '.', dockerfile: 'services/processor/Dockerfile' };
  const original = { services, networks: { 'telecom-private': {} }, volumes: { 'postgres-data': null } };
  const c = { projectName: 'pr77-unit', privateDir: os.tmpdir(), sourceSha: 'a'.repeat(40), ownerLabelName: 'io.telecom.pr77.owner', ownerLabelValue: 'nonce', baseURL: 'http://telecom.test:18080' };
  const candidate = createCandidate(original, c, '/repo');
  assert.equal(candidate.services.keycloak.environment.KC_HOSTNAME, c.baseURL + '/auth');
  assert.equal(candidate.services['incident-service'].environment.KEYCLOAK_ISSUER, c.baseURL + '/auth/realms/telecom');
  assert.deepEqual(candidate.services.proxy.ports, ['127.0.0.1:18080:18080']);
  assert.equal(candidate.services.processor.build.context, path.resolve('/repo'));
  assert.equal(candidate.services.processor.labels[c.ownerLabelName], 'nonce');
  assert.equal(candidate.networks['telecom-private'].labels[c.ownerLabelName], 'nonce');
  assert.equal(candidate.volumes['postgres-data'].labels[c.ownerLabelName], 'nonce');
  assert.equal(original.services.keycloak.environment.KC_HOSTNAME, undefined);
});

test('current Compose backend selection cannot route verification into a host stack', () => {
  const root = path.resolve(__dirname, '..');
  const yaml = require(path.join(root, 'apps/dashboard/node_modules/yaml'));
  const original = yaml.parse(fs.readFileSync(path.join(root, 'compose.yaml'), 'utf8'), { merge: true });
  const context = { projectName: 'pr77-unit', privateDir: os.tmpdir(), sourceSha: 'a'.repeat(40), ownerLabelName: 'io.telecom.pr77.owner', ownerLabelValue: 'nonce', baseURL: 'http://telecom.test:18080' };
  const oldOverride = process.env.PROXY_BACKEND_CONFIG;
  process.env.PROXY_BACKEND_CONFIG = '/unrelated/host-backend.conf';
  try {
    const candidate = createCandidate(original, context, root);
    const mounts = candidate.services.proxy.volumes;
    const backend = mounts.filter(v => typeof v === 'string' ? v.includes(':/etc/nginx/backend.conf') : v.target === '/etc/nginx/backend.conf');
    assert.deepEqual(backend, [{ type: 'bind', source: path.join(root, 'infra/nginx/backend-container.conf'), target: '/etc/nginx/backend.conf', read_only: true }]);
    assert.equal(JSON.stringify(mounts).includes('PROXY_BACKEND_CONFIG'), false);
    assert.equal(JSON.stringify(mounts).includes('/unrelated/host-backend.conf'), false);
    assert.match(fs.readFileSync(backend[0].source, 'utf8'), /^set \$backend http:\/\/incident-service:8082;\s*$/);
    assert.match(fs.readFileSync(path.join(root, 'infra/nginx/default.conf'), 'utf8'), /include \/etc\/nginx\/backend\.conf;/);
  } finally {
    if (oldOverride === undefined) delete process.env.PROXY_BACKEND_CONFIG;
    else process.env.PROXY_BACKEND_CONFIG = oldOverride;
  }
});

test('credential cleanup checks directory and nonce before unlinking only generated secrets', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pr77-private-test-'));
  const real = fs.realpathSync(directory);
  const context = { privateDir: real, projectName: 'pr77-unit', ownerLabelValue: 'nonce' };
  const write = (name, data) => fs.writeFileSync(path.join(real, name), data);
  try {
    write('stack.env', 'PASSWORD=private-password');
    write('realm.json', 'private-password');
    write('stack-context.json', 'private-password');
    write('commands.log', 'PASSWORD=private-password\nSet-Cookie: opaque-cookie\n{"token":"opaque-token"}');
    write('keep.txt', 'diagnostics');
    assert.throws(() => removeCredentials(context, real, ['private-password']));
    write('ownership.json', JSON.stringify({ directory: real, owner: 'different-nonce', project: 'pr77-unit' }));
    assert.throws(() => removeCredentials(context, real, ['private-password']));
    assert.equal(fs.existsSync(path.join(real, 'stack.env')), true);
    write('ownership.json', JSON.stringify({ directory: real, owner: 'nonce', project: 'pr77-unit' }));
    assert.throws(() => removeCredentials({ ...context, privateDir: path.dirname(real) }, real, ['private-password']));
    removeCredentials(context, real, ['private-password'], { retainCredentials: true });
    assert.equal(fs.existsSync(path.join(real, 'stack.env')), true);
    assert.equal(fs.readFileSync(path.join(real, 'commands.log'), 'utf8').includes('private-password'), false);
    removeCredentials(context, real, ['private-password']);
    for (const name of ['stack.env', 'realm.json', 'stack-context.json']) assert.equal(fs.existsSync(path.join(real, name)), false);
    assert.equal(fs.readFileSync(path.join(real, 'keep.txt'), 'utf8'), 'diagnostics');
    const log = fs.readFileSync(path.join(real, 'commands.log'), 'utf8');
    for (const secret of ['private-password', 'opaque-cookie', 'opaque-token']) assert.equal(log.includes(secret), false);
  } finally { fs.rmSync(real, { recursive: true }); }
});
