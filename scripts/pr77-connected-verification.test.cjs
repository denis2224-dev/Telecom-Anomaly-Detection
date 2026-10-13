const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { execFileSync, spawnSync } = require('node:child_process');
const { parseArgs, composeArgs, assertOwned, createCandidate, removeCredentials, geographyEffectiveFrom, assertCandidateSource } = require('./pr77-connected-verification.cjs');

function candidateRepository(t, ignore = '') {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'pr77-source-test-'));
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }));
  const root = path.join(parent, 'candidate');
  fs.mkdirSync(root);
  const git = (...args) => execFileSync('git', ['-c', 'core.autocrlf=false', '-c', 'commit.gpgsign=false', ...args], { cwd: root, encoding: 'utf8', windowsHide: true }).trim();
  const write = (relative, value = 'candidate input\n') => {
    const filename = path.join(root, relative);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, value);
  };
  git('init', '--quiet');
  git('config', 'user.name', 'PR77 regression');
  git('config', 'user.email', 'pr77-regression@example.invalid');
  git('config', 'core.excludesFile', path.join(parent, 'empty-global-ignore'));
  fs.writeFileSync(path.join(parent, 'empty-global-ignore'), '');
  write('.gitignore', ignore);
  write('services/incident-service/src/main/java/Committed.java');
  git('add', '.');
  git('commit', '--quiet', '-m', 'Committed candidate fixture');
  return { parent, root, git, write, sha: git('rev-parse', 'HEAD') };
}

function identifiesCandidatePath(error, filename) {
  const quotedPath = error.message.match(/"(?:\\.|[^"\\])*"/);
  assert.ok(quotedPath, error.message);
  const reported = JSON.parse(quotedPath[0]);
  assert.ok(reported === filename || (reported.endsWith('/') && filename.startsWith(reported)), error.message);
  return true;
}

test('contaminated CLI rejects an untracked build input before setup or generated directories', t => {
  const fixture = candidateRepository(t);
  fixture.write('scripts/pr77-connected-verification.cjs', fs.readFileSync(path.join(__dirname, 'pr77-connected-verification.cjs')));
  fixture.git('add', 'scripts/pr77-connected-verification.cjs');
  fixture.git('commit', '--quiet', '-m', 'Commit production runner');
  const source = 'services/incident-service/src/main/java/UntrackedConfiguration.java';
  fixture.write(source);
  const privateDir = path.join(fixture.parent, 'private'), outputDir = path.join(fixture.parent, 'output');
  const result = spawnSync(process.execPath, [path.join(fixture.root, 'scripts/pr77-connected-verification.cjs'), '--project', 'pr77-contamination-test', '--private-dir', privateDir, '--output-dir', outputDir], { cwd: fixture.root, encoding: 'utf8', windowsHide: true, timeout: 15000 });
  assert.equal(result.error, undefined);
  assert.notEqual(result.status, 0);
  assert.ok(result.stderr.includes(source), result.stderr);
  assert.equal(fs.existsSync(privateDir), false);
  assert.equal(fs.existsSync(outputDir), false);
});

test('source provenance accepts only the clean committed candidate', async t => {
  const fixture = candidateRepository(t);
  assert.equal(await assertCandidateSource(fixture.root), fixture.sha);
  assert.equal(await assertCandidateSource(fixture.root, fixture.sha), fixture.sha);
});

test('source provenance rejects tracked changes in the index and working tree', async t => {
  for (const change of ['unstaged modification', 'staged modification', 'staged addition', 'deletion']) {
    await t.test(change, async t => {
      const fixture = candidateRepository(t);
      let filename = 'services/incident-service/src/main/java/Committed.java';
      if (change === 'deletion') fs.unlinkSync(path.join(fixture.root, filename));
      else {
        if (change === 'staged addition') filename = 'services/incident-service/src/main/java/Added.java';
        fixture.write(filename, 'changed source\n');
        if (change.startsWith('staged')) fixture.git('add', filename);
      }
      await assert.rejects(() => assertCandidateSource(fixture.root, fixture.sha), error => {
        assert.ok(error.message.includes(filename), error.message);
        return true;
      });
    });
  }
});

test('source provenance rejects every untracked source, configuration and unrelated file', async t => {
  for (const filename of [
    'services/incident-service/src/main/java/UntrackedConfiguration.java',
    'apps/dashboard/src/extra.ts',
    'apps/dashboard/tsconfig.extra.json',
    'infra/nginx/extra.conf',
    'notes.txt',
    ' leading-source.java',
    'apps/dashboard/src/ leading name.ts',
    'apps/dashboard/src/Șir cu spații.ts',
  ]) {
    await t.test(filename, async t => {
      const fixture = candidateRepository(t);
      fixture.write(filename);
      await assert.rejects(() => assertCandidateSource(fixture.root), error => {
        assert.ok(error.message.includes(filename), error.message);
        return true;
      });
    });
  }
});

test('source provenance also rejects repository, local and global-style ignored inputs', async t => {
  for (const ignoreSource of ['repository', 'local', 'global-style']) {
    await t.test(ignoreSource, async t => {
      const filename = 'services/incident-service/src/main/java/IgnoredConfiguration.java';
      const fixture = candidateRepository(t, ignoreSource === 'repository' ? `${filename}\n` : '');
      if (ignoreSource === 'local') fixture.write('.git/info/exclude', `${filename}\n`);
      if (ignoreSource === 'global-style') fs.writeFileSync(path.join(fixture.parent, 'empty-global-ignore'), `${filename}\n`);
      fixture.write(filename);
      assert.equal(fixture.git('check-ignore', filename), filename);
      await assert.rejects(() => assertCandidateSource(fixture.root), error => identifiesCandidatePath(error, filename));
    });
  }
});

test('source provenance preserves leading whitespace in ignored candidate filenames', async t => {
  const filename = ' ignored-runtime.java';
  const fixture = candidateRepository(t, `${filename}\n`);
  fixture.write(filename);
  await assert.rejects(() => assertCandidateSource(fixture.root), error => {
    assert.ok(error.message.includes(JSON.stringify(filename)), error.message);
    return true;
  });
});

test('source provenance allows ignored build artifacts only in approved directory boundaries', async t => {
  const allowed = [
    'apps/dashboard/node_modules/package/index.js',
    'apps/dashboard/dist/dashboard/browser/index.html',
    'apps/dashboard/.angular/cache/result',
    'apps/dashboard/coverage/report.json',
    'apps/dashboard/test-results/result.json',
    'apps/dashboard/playwright-report/index.html',
    '.venv/lib/dependency.py',
    'target/result.bin',
    ...['incident-service', 'event-generator', 'processor', 'streaming-support'].map(service => `services/${service}/target/classes/Compiled.class`),
  ];
  const fixture = candidateRepository(t, 'node_modules/\ndist/\n.angular/\ncoverage/\ntest-results/\nplaywright-report/\n.venv/\ntarget/\n');
  for (const filename of allowed) fixture.write(filename);
  assert.equal(await assertCandidateSource(fixture.root, fixture.sha), fixture.sha);
});

test('source provenance does not mistake similarly named or misplaced ignored directories for outputs', async t => {
  for (const filename of [
    'apps/dashboard/node_modules-extra/injected.ts',
    'apps/dashboard/dist-extra/injected.ts',
    'apps/dashboard/src/target/injected.ts',
    'services/unknown-service/target/injected.java',
    'services/processor/target-extra/injected.java',
    'output/runtime.json',
    'tmp/runtime.json',
  ]) {
    await t.test(filename, async t => {
      const fixture = candidateRepository(t, `${filename}\n`);
      fixture.write(filename);
      await assert.rejects(() => assertCandidateSource(fixture.root), error => {
        assert.ok(error.message.includes(JSON.stringify(filename)), error.message);
        return true;
      });
    });
  }
});

test('source provenance never allows nonignored inputs merely because they occupy an artifact directory', async t => {
  const fixture = candidateRepository(t);
  const filename = 'apps/dashboard/node_modules/untracked/index.js';
  fixture.write(filename);
  await assert.rejects(() => assertCandidateSource(fixture.root), error => {
    assert.ok(error.message.includes(filename), error.message);
    return true;
  });
});

test('source provenance invalidates acceptance when HEAD or build inputs change after preflight', async t => {
  await t.test('different clean HEAD', async t => {
    const fixture = candidateRepository(t);
    assert.equal(await assertCandidateSource(fixture.root), fixture.sha);
    fixture.write('services/incident-service/src/main/java/Committed.java', 'committed next candidate\n');
    fixture.git('add', '.');
    fixture.git('commit', '--quiet', '-m', 'Different candidate');
    await assert.rejects(() => assertCandidateSource(fixture.root, fixture.sha), /candidate|HEAD|revision|source/i);
  });
  await t.test('new source after clean preflight', async t => {
    const fixture = candidateRepository(t);
    assert.equal(await assertCandidateSource(fixture.root), fixture.sha);
    const filename = 'apps/dashboard/src/appeared-during-verification.ts';
    fixture.write(filename);
    await assert.rejects(() => assertCandidateSource(fixture.root, fixture.sha), error => {
      assert.ok(error.message.includes(filename), error.message);
      return true;
    });
  });
});

test('generated private and public directories must resolve outside the candidate checkout', t => {
  const fixture = candidateRepository(t);
  const externalPrivate = path.join(fixture.parent, 'private'), externalOutput = path.join(fixture.parent, 'output');
  const args = (privateDir, outputDir) => ['--project', 'pr77-path-test', '--private-dir', privateDir, '--output-dir', outputDir];
  assert.doesNotThrow(() => parseArgs(args(externalPrivate, externalOutput), fixture.root));
  assert.throws(() => parseArgs(args(path.join(fixture.root, 'private'), externalOutput), fixture.root), /outside|repository|checkout/i);
  assert.throws(() => parseArgs(args(externalPrivate, path.join(fixture.root, 'output')), fixture.root), /outside|repository|checkout/i);
  const alias = path.join(fixture.parent, 'candidate-alias');
  fs.symlinkSync(fixture.root, alias, process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => parseArgs(args(path.join(alias, 'private'), externalOutput), fixture.root), /outside|repository|checkout/i);
});

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
