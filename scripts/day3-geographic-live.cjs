// Run against a disposable stack: node scripts/day3-geographic-live.cjs <private-env-file> <output-dir>
// The real OIDC session stays in memory; output contains no credentials or cookies.
const { chromium, expect } = require('../apps/dashboard/node_modules/@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID, createHash } = require('node:crypto');

async function main() {
  const [envFile, outputDir] = process.argv.slice(2);
  if (!envFile || !outputDir) throw Error('Supply a private environment file and output directory.');
  const env = Object.fromEntries(fs.readFileSync(envFile, 'utf8').split(/\r?\n/)
    .filter(line => line.includes('=') && !line.startsWith('#'))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  if (!env.DAY3_USERNAME || !env.DAY3_PASSWORD) throw Error('DAY3_USERNAME and DAY3_PASSWORD are required.');
  fs.mkdirSync(outputDir, { recursive: true });
  const save = (name, value) => fs.writeFileSync(path.join(outputDir, name), JSON.stringify(value, null, 2) + '\n');
  const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP telecom.test 127.0.0.1', '--no-proxy-server'] });
  const result = { runs: [], snapshots: [], browserErrors: [], blockers: [] };
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    page.on('pageerror', error => result.browserErrors.push(error.message));
    const base = env.DAY3_BASE_URL || 'http://telecom.test:8080';
    await page.goto(base + '/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(env.DAY3_USERNAME);
    await page.getByLabel('Password', { exact: true }).fill(env.DAY3_PASSWORD);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL('**/dashboard', { timeout: 60000 });
    const request = async (method, url, data, csrf) => {
      const response = await page.request.fetch(base + url, {
        method, data, headers: csrf ? { 'X-CSRF-TOKEN': csrf } : {},
      });
      if (!response.ok()) throw Error(`${method} ${url}: HTTP ${response.status()} ${await response.text()}`);
      return response.json();
    };
    const catalogue = await request('GET', '/api/geography/cities');
    save('catalogue.json', catalogue);
    result.initialHistories = [];
    const initialTo = new Date().toISOString();
    const initialFrom = new Date(Date.now() - 15 * 60000).toISOString();
    for (const city of catalogue.cities) for (const service of ['VOLTE', 'SMS']) {
      result.initialHistories.push(await request('GET', `/api/geography/cities/${city.cityId}/kpis?` +
        new URLSearchParams({ service, from: initialFrom, to: initialTo, size: '100' })));
    }
    await page.screenshot({ path: path.join(outputDir, 'dashboard.png'), fullPage: true });
    save('live.json', result);
    const csrf = (await request('GET', '/api/auth/csrf')).token;
    const scenarios = [
      ['VOLTE-MD-CHI', 'VOLTE_IMS_OVERLOAD'], ['SMS-MD-CHI', 'SMS_QUEUE_DELAY'],
      ['VOLTE-MD-BAL', 'NORMAL_CONTROL'], ['SMS-MD-CAH', 'TELEMETRY_GAP'],
    ];
    for (const [scopeId, type] of scenarios) {
      const body = { requestId: randomUUID(), scopeId, seed: 7102026 };
      const run = await request('POST', '/api/simulator/scenarios/' + type, body, csrf);
      const retry = await request('POST', '/api/simulator/scenarios/' + type, body, csrf);
      expect(retry.runId).toBe(run.runId);
      expect(retry.scheduledStartAt).toBe(run.scheduledStartAt);
      result.runs.push({ ...run, requestId: body.requestId });
    }
    save('live.json', result);
    console.log('Four authenticated city scenarios started; waiting for scheduled eight-minute profiles.');
    const deadline = Date.now() + 12 * 60000;
    let completed = false;
    while (Date.now() < deadline) {
      const runs = await Promise.all(result.runs.map(run => request('GET', '/api/simulator/runs/' + run.runId)));
      result.snapshots.push({ at: new Date().toISOString(), runs });
      save('live.json', result);
      if (runs.some(run => ['FAILED', 'STOPPED'].includes(run.status))) throw Error('A scenario failed or stopped.');
      if (runs.every(run => run.status === 'COMPLETED')) { completed = true; break; }
      console.log('Scenario status: ' + runs.map(run => run.scopeId + '=' + run.status).join(', '));
      await new Promise(resolve => setTimeout(resolve, 30000));
    }
    if (!completed) throw Error('Scenario completion exceeded twelve minutes.');
    // Allow the final window's normal closure and downstream delivery to finish.
    await new Promise(resolve => setTimeout(resolve, 45000));
    result.incidents = {};
    result.detections = {};
    const firstStart = result.runs.map(run => run.scheduledStartAt).sort()[0];
    const sha = parts => createHash('sha256').update(JSON.stringify(parts)).digest('hex');
    for (const [scope, type] of scenarios) {
      const incidents = await request('GET', '/api/incidents?' + new URLSearchParams({ scopeId: scope, size: '100' }));
      result.incidents[scope] = incidents;
      const run = result.runs.find(run => run.scopeId === scope);
      const fault = type.endsWith('OVERLOAD') || type === 'SMS_QUEUE_DELAY';
      const matches = incidents.items.filter(item => item.firstObservedAt >= run.scheduledStartAt && item.firstObservedAt < run.scheduledEndAt);
      expect(matches.length).toBe(fault ? 1 : 0);
      for (const incident of matches) {
        const history = await request('GET', `/api/incidents/${incident.id}/detections?size=100`);
        result.detections[scope] = history;
        const detections = history.items;
        expect(detections.map(d => d.phase)).toEqual(['OPEN', 'UPDATE', 'UPDATE', 'UPDATE', 'RECOVERY']);
        const first = new Date(Date.parse(run.scheduledStartAt) + 2 * 60000).toISOString().replace('.000Z', 'Z');
        const correlation = sha([incident.service, scope, incident.service === 'VOLTE' ? 'VOLTE_SETUP_DEGRADATION' : 'SMS_DELIVERY_DELAY', 'service-rules-v2']);
        const episode = sha([correlation, first]);
        for (const [index, d] of detections.entries()) {
          expect(d.firstObservedAt).toBe(first);
          expect(d.episodeId).toBe(episode);
          expect(d.detectionId).toBe(sha([episode, d.windowStart, d.phase, 'service-rules-v2']));
          expect(d.sequence).toBe(index + 1);
          expect(d.scopeId).toBe(scope);
          expect(d.impact.uniqueSubscribers).toBeNull();
          expect(Date.parse(d.detectedAt)).toBeGreaterThanOrEqual(Date.parse(d.windowEnd));
        }
        await page.goto(base + '/incidents/' + incident.id);
        await expect(page.locator('.incident-summary-bar')).toBeVisible();
        await expect(page.locator('.evidence-entry').first()).toBeVisible();
        await page.screenshot({ path: path.join(outputDir, scope + '-incident.png'), fullPage: true });
      }
    }
    result.histories = [];
    for (const city of catalogue.cities) for (const service of ['VOLTE', 'SMS']) {
      const history = await request('GET', `/api/geography/cities/${city.cityId}/kpis?` + new URLSearchParams({
        service, from: firstStart, to: new Date().toISOString(), size: '100',
      }));
      expect(history.points.length).toBeGreaterThanOrEqual(8);
      expect(history.hasNext).toBe(false);
      result.histories.push(history);
    }
    await page.goto(base + '/dashboard');
    await page.screenshot({ path: path.join(outputDir, 'dashboard.png'), fullPage: true });
    expect(result.browserErrors).toEqual([]);
    result.acceptance = 'SCENARIO_TIMELINES_PASSED_RECEIPT_AND_DISPLAY_REVIEW_PENDING';
    save('live.json', result);
    console.log('City scenarios, canonical incident identities, unrelated controls and twenty histories passed.');
  } catch (error) {
    result.blockers.push(error.message);
    save('live.json', result);
    throw error;
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
