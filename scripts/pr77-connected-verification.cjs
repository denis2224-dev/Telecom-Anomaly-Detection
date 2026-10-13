// Disposable, current-source PR77 verification. Never consumes repository .env.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const ROOT = path.resolve(__dirname, '..');
const PORTS = { proxy: 18080, postgres: 25432, kafka: 29094, generator: 18081, incident: 18082 };
const OWNER = 'io.telecom.pr77.owner';
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
const IGNORED_OUTPUT_DIRECTORIES = [
  'apps/dashboard/node_modules', 'apps/dashboard/dist', 'apps/dashboard/.angular',
  'apps/dashboard/coverage', 'apps/dashboard/test-results', 'apps/dashboard/playwright-report',
  '.venv', 'target', ...['incident-service', 'event-generator', 'processor', 'streaming-support'].map(name => `services/${name}/target`)
];
function isWithin(directory, target) {
  const relative = path.relative(directory, target);
  return relative === '' || (relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative));
}
function geographyEffectiveFrom(now = Date.now()) {
  if (!Number.isFinite(now)) throw Error('Geography activation requires a valid timestamp.');
  // GeographyCatalog accepts only complete UTC minute boundaries.
  return new Date(Math.floor(now / 60000) * 60000 - 15 * 60000).toISOString();
}

function parseArgs(args, root = ROOT) {
  const values = {};
  const destinations = {};
  const realRoot = fs.realpathSync(root);
  for (let i = 0; i < args.length; i += 2) {
    if (!['--project', '--private-dir', '--output-dir'].includes(args[i]) || !args[i + 1] || values[args[i]]) throw Error('Use --project, --private-dir and --output-dir once each.');
    values[args[i]] = args[i + 1];
  }
  if (!/^pr77-[a-z0-9][a-z0-9-]{0,48}$/.test(values['--project'] || '')) throw Error('Project must be a unique pr77- name using lowercase letters, digits and hyphens (maximum 54 characters).');
  for (const key of ['--private-dir', '--output-dir']) {
    if (!values[key] || !path.isAbsolute(values[key])) throw Error(`${key} must be absolute.`);
    values[key] = path.resolve(values[key]);
    if (fs.existsSync(values[key])) throw Error(`${key} must be fresh, including on failed-run retry.`);
    if (!fs.statSync(path.dirname(values[key])).isDirectory()) throw Error(`${key} parent must exist.`);
    // Resolve existing parents so a junction/symlink cannot disguise an in-checkout output.
    destinations[key] = path.join(fs.realpathSync(path.dirname(values[key])), path.basename(values[key]));
    if (isWithin(realRoot, destinations[key])) throw Error(`${key} must be outside the repository; generated credentials and evidence cannot enter source inputs.`);
  }
  const privateDir = values['--private-dir'], outputDir = values['--output-dir'];
  if (isWithin(destinations['--private-dir'], destinations['--output-dir']) || isWithin(destinations['--output-dir'], destinations['--private-dir'])) throw Error('Private and public output directories must be separate.');
  return { projectName: values['--project'], privateDir, outputDir };
}

function composeArgs(context, args) {
  return ['compose', '--project-name', context.projectName, '--env-file', context.envFile,
    ...context.composeFiles.flatMap(file => ['--file', file]), '--profile', 'app', ...args];
}

function assertOwned(labels, context) {
  if (labels?.['com.docker.compose.project'] !== context.projectName || labels?.[context.ownerLabelName] !== context.ownerLabelValue) throw Error('Resource ownership mismatch; no outage or cleanup is permitted.');
}

function createCandidate(original, context, root) {
  const compose = structuredClone(original);
  compose.name = context.projectName;
  const label = { [context.ownerLabelName]: context.ownerLabelValue };
  for (const [name, service] of Object.entries(compose.services)) {
    service.labels = { ...service.labels, ...label };
    if (service.build) {
      service.build.context = path.resolve(root);
      service.build.labels = { ...service.build.labels, ...label, 'org.opencontainers.image.revision': context.sourceSha };
      service.image = `${context.projectName}-${name}:${context.sourceSha.slice(0, 12)}`;
    }
    service.volumes = (service.volumes || []).map(volume => {
      if (typeof volume === 'object') return { ...volume, source: volume.type === 'bind' ? path.resolve(root, volume.source) : volume.source };
      if (!volume.startsWith('./')) return volume;
      const [source, target, mode] = volume.split(':');
      return { type: 'bind', source: path.resolve(root, source), target, read_only: mode === 'ro' };
    });
  }
  for (const section of ['networks', 'volumes']) for (const [name, raw] of Object.entries(compose[section] || {})) {
    const item = compose[section][name] = raw || {};
    if (item.external || item.name) throw Error('Named/external resources are unsupported by the disposable runner.');
    item.labels = { ...item.labels, ...label };
  }
  const s = compose.services;
  s.postgres.ports = [`127.0.0.1:${PORTS.postgres}:5432`];
  s.kafka.ports = [`127.0.0.1:${PORTS.kafka}:9094`];
  s['event-generator'].ports = [`127.0.0.1:${PORTS.generator}:8081`];
  s['incident-service'].ports = [`127.0.0.1:${PORTS.incident}:8082`];
  s.proxy.ports = [`127.0.0.1:${PORTS.proxy}:${PORTS.proxy}`];
  s.keycloak.environment.KC_HOSTNAME = context.baseURL + '/auth';
  s.keycloak.volumes = s.keycloak.volumes.filter(v => v.target !== '/opt/keycloak/data/import/telecom-realm.json');
  s.keycloak.volumes.push({ type: 'bind', source: path.join(context.privateDir, 'realm.json'), target: '/opt/keycloak/data/import/telecom-realm.json', read_only: true });
  // Main can select a host backend via an interpolated short-form mount.
  // Disposable verification always owns its backend and pins the container route.
  s.proxy.volumes = s.proxy.volumes.filter(v => typeof v === 'string'
    ? !v.includes(':/etc/nginx/backend.conf:')
    : !['/etc/nginx/conf.d/default.conf', '/etc/nginx/backend.conf'].includes(v.target));
  s.proxy.volumes.push({ type: 'bind', source: path.join(context.privateDir, 'nginx.conf'), target: '/etc/nginx/conf.d/default.conf', read_only: true });
  s.proxy.volumes.push({ type: 'bind', source: path.resolve(root, 'infra/nginx/backend-container.conf'), target: '/etc/nginx/backend.conf', read_only: true });
  s.proxy.healthcheck = { test: ['CMD-SHELL', `wget -q -O /dev/null http://127.0.0.1:${PORTS.proxy}/auth/realms/telecom/.well-known/openid-configuration && wget -q -O /dev/null http://127.0.0.1:${PORTS.proxy}/dashboard`], interval: '5s', timeout: '3s', retries: 30 };
  Object.assign(s['incident-service'].environment, { APP_PUBLIC_ORIGIN: context.baseURL, KEYCLOAK_ISSUER: context.baseURL + '/auth/realms/telecom' });
  return compose;
}

function run(command, args, { cwd = ROOT, env = process.env, input, logFile, timeout = 20 * 60000, preserveOutput = false } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const log = logFile ? fs.createWriteStream(logFile, { flags: 'a', mode: 0o600 }) : null;
    let stdout = '';
    child.stdout.on('data', data => { stdout += data; log?.write(data); });
    child.stderr.on('data', data => log?.write(data));
    child.on('error', () => { clearTimeout(timer); log?.end(); reject(Error(`Could not start ${path.basename(command)}.`)); });
    const timer = setTimeout(() => child.kill(), timeout);
    child.on('close', code => {
      clearTimeout(timer);
      // Flush private writes before finally redacts the shared log.
      const finish = () => {
        if (code !== 0) reject(Error(`${path.basename(command)} failed (exit ${code}); diagnostic output is private.`));
        else resolve(preserveOutput ? stdout : stdout.trim());
      };
      if (log) log.end(finish);
      else finish();
    });
    child.stdin.end(input);
  });
}

async function assertCandidateSource(root, expectedSha) {
  const git = args => run('git', args, { cwd: root, preserveOutput: true });
  const sourceSha = (await git(['rev-parse', 'HEAD'])).trim();
  if (expectedSha !== undefined && sourceSha !== expectedSha) throw Error('Source HEAD changed during verification; candidate acceptance is invalid.');
  const status = await git(['status', '--porcelain=v1', '--untracked-files=all', '-z']);
  if (status) {
    const entry = status.split('\0')[0];
    const kind = entry.startsWith('?? ') ? 'Untracked' : 'Tracked modified';
    throw Error(`${kind} source candidate input ${JSON.stringify(entry.slice(3))}; commit intended source changes and use a clean checkout before verification.`);
  }
  // Git ignores (including local/global rules) do not exclude files from application builds.
  // Exclude approved directories before enumeration; collapsing ignored ancestors
  // could otherwise conceal unexpected files beside an approved output directory.
  const ignored = await git(['ls-files', '--others', '--ignored', '--exclude-standard', '-z', '--', '.',
    ...IGNORED_OUTPUT_DIRECTORIES.map(directory => `:(top,literal,exclude)${directory}/`)]);
  const ignoredInput = ignored.split('\0').find(Boolean);
  if (ignoredInput) throw Error(`Ignored untracked source candidate input ${JSON.stringify(ignoredInput)}; move it outside the checkout or commit intended source before verification.`);
  if ((await git(['rev-parse', 'HEAD'])).trim() !== sourceSha) throw Error('Source HEAD changed during the integrity check; retry from a stable committed candidate.');
  return sourceSha;
}

async function checkPorts() {
  for (const port of Object.values(PORTS)) await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', () => reject(Error(`Reserved loopback port ${port} is occupied; no stack was started.`)));
    server.listen(port, '127.0.0.1', () => server.close(resolve));
  });
}

async function resources(context, env, logFile) {
  const filter = `label=com.docker.compose.project=${context.projectName}`;
  const result = [];
  for (const [kind, args] of [['container', ['ps', '--all', '--quiet', '--filter', filter]], ['network', ['network', 'ls', '--quiet', '--filter', filter]], ['volume', ['volume', 'ls', '--quiet', '--filter', filter]]]) {
    const ids = (await run('docker', args, { env, logFile })).split(/\s+/).filter(Boolean);
    for (const id of ids) {
      const inspectArgs = kind === 'container' ? ['inspect', id] : [kind, 'inspect', id];
      const item = JSON.parse(await run('docker', inspectArgs, { env, logFile }))[0];
      result.push({ kind, id, labels: kind === 'container' ? item.Config.Labels : item.Labels, imageId: item.Image, health: item.State?.Health?.Status });
    }
  }
  return result;
}

function writePrivate(filename, value) { fs.writeFileSync(filename, typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' }); }

function removeCredentials(context, expectedDirectory, secrets, { retainCredentials = false } = {}) {
  if (fs.realpathSync(context.privateDir) !== expectedDirectory || !fs.lstatSync(context.privateDir).isDirectory()) throw Error('Private directory identity changed; credential cleanup refused.');
  const marker = JSON.parse(fs.readFileSync(path.join(expectedDirectory, 'ownership.json'), 'utf8'));
  if (marker.directory !== expectedDirectory || marker.owner !== context.ownerLabelValue || marker.project !== context.projectName) throw Error('Private directory ownership mismatch; credential cleanup refused.');
  const logFile = path.join(expectedDirectory, 'commands.log');
  if (fs.existsSync(logFile)) {
    let log = fs.readFileSync(logFile, 'utf8');
    for (const secret of secrets.filter(Boolean).sort((a, b) => b.length - a.length)) log = log.replaceAll(secret, '[REDACTED]');
    log = log.replace(/(authorization|set-cookie|cookie)(\s*[:=]\s*)[^\r\n]+/gi, '$1$2[REDACTED]')
      .replace(/("(?:access_token|refresh_token|id_token|token|password|secret|JSESSIONID)"\s*:\s*)"[^"]*"/gi, '$1"[REDACTED]"')
      .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?\b/g, '[REDACTED]');
    fs.writeFileSync(logFile, log, { mode: 0o600 });
  }
  if (retainCredentials) return;
  // Explicit names only; do not recurse or follow a supplied deletion target.
  for (const filename of ['stack.env', 'realm.json', 'stack-context.json']) {
    const target = path.join(expectedDirectory, filename);
    if (fs.existsSync(target)) fs.unlinkSync(target);
  }
}
function assetHashes(directory) {
  const result = {};
  function visit(dir) { for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const filename = path.join(dir, entry.name);
    if (entry.isDirectory()) visit(filename);
    else if (entry.isFile()) result[path.relative(directory, filename).replaceAll(path.sep, '/')] = sha256(fs.readFileSync(filename));
  } }
  visit(directory);
  return result;
}

async function main(args) {
  const context = parseArgs(args);
  context.sourceSha = await assertCandidateSource(ROOT);
  context.ownerLabelName = OWNER;
  context.ownerLabelValue = crypto.randomUUID();
  context.baseURL = `http://telecom.test:${PORTS.proxy}`;
  context.envFile = path.join(context.privateDir, 'stack.env');
  context.composeFiles = [path.join(context.privateDir, 'compose.json')];
  context.scenarioResultFile = path.join(context.outputDir, 'scenarios.json');
  const secrets = Object.fromEntries(['POSTGRES_PASSWORD', 'PROCESSING_DB_PASSWORD', 'PROCESSING_MIGRATOR_PASSWORD', 'INCIDENT_DB_PASSWORD', 'INCIDENT_MIGRATOR_PASSWORD', 'KEYCLOAK_DB_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_CLIENT_SECRET'].map(key => [key, crypto.randomBytes(24).toString('hex')]));
  const variables = { ...secrets, POSTGRES_PORT: PORTS.postgres, KAFKA_HOST_PORT: PORTS.kafka, GENERATOR_HOST_PORT: PORTS.generator,
    TELECOM_GEOGRAPHY_ENABLED: 'true', TELECOM_GEOGRAPHY_EFFECTIVE_FROM: geographyEffectiveFrom(),
    HISTORICAL_BOOTSTRAP_ENABLED: 'false', CONTINUOUS_TELEMETRY_ENABLED: 'true', ML_SMS_SHADOW_ENABLED: 'true' };
  const composeText = fs.readFileSync(path.join(ROOT, 'compose.yaml'), 'utf8');
  // Host environment must not override --env-file or enable unrelated Compose files.
  const env = { ...process.env };
  for (const key of [...Object.keys(variables), ...[...composeText.matchAll(/\$\{([A-Z_0-9]+)/g)].map(match => match[1]), 'COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_PROFILES', 'COMPOSE_PATH_SEPARATOR']) delete env[key];
  if ((await resources(context, env)).length) throw Error('Project already owns Docker resources; select a fresh unique name.');
  await checkPorts();
  fs.mkdirSync(context.privateDir, { mode: 0o700 });
  fs.mkdirSync(context.outputDir, { mode: 0o700 });
  const privateDirectoryIdentity = fs.realpathSync(context.privateDir);
  const logFile = path.join(context.privateDir, 'commands.log');
  const report = { schemaVersion: 1, sourceSha: context.sourceSha, projectName: context.projectName, baseURL: context.baseURL,
    startedAt: new Date().toISOString(), status: 'INCOMPLETE', configHashes: {}, cleanup: 'NOT_STARTED' };
  let stackAttempted = false;
  try {
    writePrivate(path.join(context.privateDir, 'ownership.json'), { directory: privateDirectoryIdentity, owner: context.ownerLabelValue, project: context.projectName });
    writePrivate(context.envFile, Object.entries(variables).map(([key, value]) => `${key}=${value}`).join('\n') + '\n');
    const realm = JSON.parse(fs.readFileSync(path.join(ROOT, 'infra/keycloak/telecom-realm.json'), 'utf8'));
    const client = realm.clients.find(item => item.clientId === 'telecom-web');
    client.secret = secrets.KEYCLOAK_CLIENT_SECRET;
    client.redirectUris = [context.baseURL + '/login/oauth2/code/keycloak'];
    client.webOrigins = [context.baseURL];
    client.attributes['post.logout.redirect.uris'] = context.baseURL + '/signed-out';
    realm.users = ['analyst', 'supervisor'].map(role => {
      const id = crypto.randomUUID(), username = `pr77-${role}`, password = crypto.randomBytes(24).toString('hex');
      context[role] = { username, password, subject: id };
      return { id, username, enabled: true, emailVerified: true, email: `${username}@example.invalid`, firstName: 'PR77', lastName: role,
        realmRoles: [role.toUpperCase()], credentials: [{ type: 'password', value: password, temporary: false }], requiredActions: [] };
    });
    writePrivate(path.join(context.privateDir, 'realm.json'), realm);
    const nginx = fs.readFileSync(path.join(ROOT, 'infra/nginx/default.conf'), 'utf8')
      .replace('http://host.docker.internal:8082', 'http://incident-service:8082')
      .replace('listen 8080;', `listen ${PORTS.proxy};`)
      .replaceAll('telecom.test:8080', `telecom.test:${PORTS.proxy}`)
      .replace('X-Forwarded-Port 8080;', `X-Forwarded-Port ${PORTS.proxy};`);
    writePrivate(path.join(context.privateDir, 'nginx.conf'), nginx);
    const yaml = require(path.join(ROOT, 'apps/dashboard/node_modules/yaml'));
    writePrivate(context.composeFiles[0], createCandidate(yaml.parse(composeText, { merge: true }), context, ROOT));
    writePrivate(path.join(context.privateDir, 'stack-context.json'), context);
    for (const file of ['compose.json', 'nginx.conf', 'realm.json', 'stack.env']) report.configHashes[file] = sha256(fs.readFileSync(path.join(context.privateDir, file)));
    const compose = args => run('docker', composeArgs(context, args), { env, logFile });
    await compose(['config', '--quiet']);
    console.log('Building production dashboard from the recorded candidate.');
    if (process.platform === 'win32') await run('cmd.exe', ['/d', '/s', '/c', 'npm.cmd run build'], { cwd: path.join(ROOT, 'apps/dashboard'), env, logFile });
    else await run('npm', ['run', 'build'], { cwd: path.join(ROOT, 'apps/dashboard'), env, logFile });
    report.dashboardAssets = assetHashes(path.join(ROOT, 'apps/dashboard/dist/dashboard/browser'));
    console.log('Building every application image from the recorded candidate.');
    await compose(['build']);
    await assertCandidateSource(ROOT, context.sourceSha);
    // Recheck immediately before creation; cleanup is never allowed to claim a preexisting project.
    if ((await resources(context, env, logFile)).length) throw Error('Project resources appeared during preparation; startup refused.');
    stackAttempted = true;
    console.log('Starting the isolated stack and waiting for readiness.');
    await compose(['up', '--detach', '--wait', '--wait-timeout', '420']);
    const owned = await resources(context, env, logFile);
    owned.forEach(item => assertOwned(item.labels, context));
    report.runtime = owned.filter(item => item.kind === 'container').map(item => ({ containerId: item.id, imageId: item.imageId, service: item.labels['com.docker.compose.service'], health: item.health }));
    for (const item of report.runtime.filter(item => ['incident-service', 'event-generator', 'processor', 'history-bootstrap', 'ml-service'].includes(item.service))) {
      const image = JSON.parse(await run('docker', ['image', 'inspect', item.imageId], { env, logFile }))[0];
      if (image.Config.Labels?.['org.opencontainers.image.revision'] !== context.sourceSha || image.Config.Labels?.[OWNER] !== context.ownerLabelValue) throw Error('Application image source or run ownership does not match the candidate.');
      item.sourceSha = image.Config.Labels['org.opencontainers.image.revision'];
    }
    for (const role of ['analyst', 'supervisor']) {
      const sql = "INSERT INTO app.analysts (id, issuer, subject, display_name, enabled) VALUES (:'id'::uuid, :'issuer', :'subject', :'name', true);\n";
      await run('docker', composeArgs(context, ['exec', '-T', 'postgres', 'bash', '-ec', 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d incidents_db "$@"', '--',
        '-v', `id=${crypto.randomUUID()}`, '-v', `issuer=${context.baseURL}/auth/realms/telecom`, '-v', `subject=${context[role].subject}`, '-v', `name=PR77 ${role}`]), { env, logFile, input: sql });
    }
    console.log('Running real authentication, expiry, reconnect and geographic browser verification.');
    await run(process.execPath, [path.join(ROOT, 'apps/dashboard/node_modules/@playwright/test/cli.js'), 'test', '--config=playwright.pr77.config.ts'], {
      cwd: path.join(ROOT, 'apps/dashboard'), env: { ...env, PR77_STACK_CONTEXT: path.join(context.privateDir, 'stack-context.json') }, logFile, timeout: 90 * 60000 });
    // A browser worker must produce machine-readable assertions; no zero-test success.
    const acceptance = JSON.parse(fs.readFileSync(path.join(context.outputDir, 'verification.json'), 'utf8'));
    if (acceptance.status !== 'PASSED' || acceptance.sourceSha !== context.sourceSha) throw Error('Browser verification did not produce candidate-bound PASSED acceptance.');
    await assertCandidateSource(ROOT, context.sourceSha);
    report.status = 'PASSED';
    report.verificationSha256 = sha256(fs.readFileSync(path.join(context.outputDir, 'verification.json')));
  } catch (error) {
    report.status = 'FAILED'; report.failure = error.message;
    if (stackAttempted) {
      try {
        const owned = await resources(context, env, logFile);
        owned.forEach(item => assertOwned(item.labels, context));
        await run('docker', composeArgs(context, ['logs', '--no-color', '--timestamps', '--tail', '200']), { env, logFile });
        report.privateFailureLogsCollected = true;
      } catch { report.privateFailureLogsCollected = false; }
    }
  }
  finally {
    if (stackAttempted) {
      try {
        for (const file of ['compose.json', 'nginx.conf', 'realm.json', 'stack.env']) {
          if (sha256(fs.readFileSync(path.join(context.privateDir, file))) !== report.configHashes[file]) throw Error('Generated configuration changed; cleanup refused.');
        }
        const owned = await resources(context, env, logFile);
        owned.forEach(item => assertOwned(item.labels, context));
        await run('docker', composeArgs(context, ['down', '--volumes', '--remove-orphans']), { env, logFile });
        if ((await resources(context, env, logFile)).length) throw Error('Owned resources remain after cleanup.');
        report.cleanup = 'PASSED';
      } catch { report.cleanup = 'BLOCKED_OWNERSHIP_OR_DOCKER_FAILURE'; report.status = 'FAILED'; }
    }
    try {
      const retainCredentials = report.cleanup === 'BLOCKED_OWNERSHIP_OR_DOCKER_FAILURE';
      removeCredentials(context, privateDirectoryIdentity, [...Object.values(secrets), context.analyst?.password, context.supervisor?.password], { retainCredentials });
      report.credentialCleanup = retainCredentials ? 'RETAINED_FOR_OWNED_STACK_RECOVERY' : 'PASSED';
    } catch { report.credentialCleanup = 'BLOCKED_DIRECTORY_OWNERSHIP'; report.status = 'FAILED'; }
    report.finishedAt = new Date().toISOString();
    fs.writeFileSync(path.join(context.outputDir, 'runtime.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(`Verification ${report.status}; cleanup ${report.cleanup}. Public result: ${path.join(context.outputDir, 'runtime.json')}`);
  }
  if (report.status !== 'PASSED') throw Error('Connected verification failed; retain this attempt and inspect private diagnostics.');
}

module.exports = { parseArgs, composeArgs, assertOwned, createCandidate, removeCredentials, geographyEffectiveFrom, assertCandidateSource };
if (require.main === module) main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
