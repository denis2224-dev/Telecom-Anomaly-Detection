import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';

const root = resolve(__dirname, '../../../../..');
function command(file: string, args: string[], input?: string) {
  try { return execFileSync(file, args, { cwd: root, input, encoding: 'utf8', timeout: 60000, stdio: ['pipe', 'pipe', 'pipe'] }).trim(); }
  catch { throw new Error(`Local G1 prerequisite failed (${file}); inspect service health locally. Sensitive output omitted.`); }
}
function admin(args: string[], input?: string) {
  return command('docker', ['compose', 'exec', '-T', 'keycloak', 'bash', '-ec', `
    config=$(mktemp)
    trap 'rm -f "$config"' EXIT
    /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080/auth \
      --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
    /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
  `, '--', ...args], input);
}
function sql(database: string, query: string) {
  return command('docker', ['compose', 'exec', '-T', 'postgres', 'bash', '-ec',
    'exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"', '--', database], query);
}

test('G1 real login, generated voice episode, backend parity and exact replay', async ({ page, context }, info) => {
  // Only targets the repository's local Compose stack. Credentials exist in memory/stdin only.
  const username = 'g1-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  let userId = '';
  const from = process.env.G1_WINDOW_START ?? new Date(Math.floor(Date.now() / 60000) * 60000 - 600000).toISOString().replace('.000Z', 'Z');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00Z$/.test(from)) throw new Error('G1_WINDOW_START must be an aligned UTC minute.');
  const to = new Date(Date.parse(from) + 480000).toISOString();
  try {
    const anonymous = await context.request.get('/api/incidents', { maxRedirects: 0 });
    expect(anonymous.status()).toBe(401);
    expect(anonymous.headers()['content-type']).toContain('application/json');
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'G1', lastName: 'Verification', email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary user creation returned an unexpected ID.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command(process.platform === 'win32' ? 'python' : 'python3',
      ['scripts/provision-analyst', '--username', username, '--display-name', 'G1 verification']);
    try {
      await page.goto('/login');
      await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect.poll(() => new URL(page.url()).pathname).toBe('/dashboard');
    } catch { throw new Error('Real Keycloak login failed; sensitive details omitted.'); }
    await expect(page.locator('.identity')).toContainText('G1 verification');
    console.log('Real login verified; generating voice measurements.');
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
    command('bash', ['scripts/voice-scenario', from]);
    const firstObservedAt = new Date(Date.parse(from) + 60000).toISOString();
    const incidents = async () => {
      const response = await context.request.get('/api/incidents?scopeId=VOLTE-MD-CENTRAL&size=100');
      expect(response.status()).toBe(200);
      return (await response.json()).items.filter((item: any) => Date.parse(item.firstObservedAt) === Date.parse(firstObservedAt));
    };
    await expect.poll(async () => (await incidents()).map((item: any) => item.technicalState), { timeout: 60000 }).toEqual(['RECOVERED']);
    const [incident] = await incidents();
    console.log('Recovered incident received; checking KPI parity.');
    expect(incident.status).toBe('OPEN');
    const historyResponse = await context.request.get(`/api/services/VOLTE-MD-CENTRAL/kpis?from=${from}&to=${to}&size=100`);
    expect(historyResponse.status()).toBe(200);
    const history = await historyResponse.json();
    expect(history.items).toHaveLength(8);
    expect(history.items[4].quality).toBe('MISSING');
    const updates = await (await context.request.get(`/api/incidents/${incident.id}/detections?size=100`)).json();
    expect(updates.items.map((item: any) => item.phase)).toEqual(['OPEN', 'UPDATE', 'UNKNOWN', 'UPDATE', 'UPDATE', 'RECOVERY']);
    const opening = updates.items[0];
    expect(opening.severity).toBe('HIGH');
    expect(opening.mlStatus).toBe('INSUFFICIENT_DATA');
    expect(opening.causeConfidence).toBe('MEDIUM');
    expect(opening.probableCause).toContain('IMS capacity pressure');
    const imsEvidence = opening.evidence.find((item: any) => item.code === 'IMS_CAPACITY_CORRELATION');
    expect(imsEvidence).toBeDefined();
    const sourceIds = sql('processing_db', `SELECT event_id FROM app.observation_receipt
      WHERE scope_id='VOLTE-MD-CENTRAL' AND window_start='${opening.windowStart}'
      AND (kind='SERVICE' OR (kind='NODE' AND payload->>'nodeId'='IMS-A')) ORDER BY event_id;`)
      .split('\n').filter(Boolean);
    expect(sourceIds).toHaveLength(2);
    expect([...imsEvidence.sourceEventIds].sort()).toEqual(sourceIds);
    for (const detection of updates.items) {
      const window = history.items.find((item: any) => item.windowStart === detection.windowStart);
      expect(detection.kpis).toEqual(window.kpis);
      expect(Date.parse(detection.detectedAt)).toBeGreaterThanOrEqual(Date.parse(detection.windowEnd));
    }
    await page.reload();
    console.log('Opening the service and incident screens.');
    await page.locator('a[href="/services/VOLTE-MD-CENTRAL"]').first().click();
    await page.getByLabel('From (UTC)', { exact: true }).fill(from.slice(0,16));
    await page.getByLabel('To (UTC, exclusive)').fill(to.slice(0,16));
    const rangeLoaded = page.waitForResponse(response => response.url().includes('/kpis?')
      && Date.parse(new URL(response.url()).searchParams.get('from') ?? '') === Date.parse(from));
    await page.getByRole('button', { name: 'Apply time range' }).click();
    await rangeLoaded;
    await expect(page.getByText('Loading service evidence…', { exact: true })).toHaveCount(0);
    const card = page.locator(`[data-episode-id="${incident.episodeId}"]`);
    await expect(card).toHaveCount(1);
    await expect(card).toBeVisible();
    await expect(page.locator('.episode-card')).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath('voice-trend.png'), fullPage: true });
    await card.getByRole('link', { name: 'Open incident detail' }).click();
    for (const detection of updates.items) {
      const article = page.locator(`[data-detection-id="${detection.detectionId}"]`);
      await expect(article).toBeVisible();
      const cssr = detection.kpis.find((item: any) => item.name === 'cssrPct');
      await expect(article.locator('[data-kpi="cssrPct"] td').nth(0)).toHaveText(cssr.observed === null ? 'Unavailable' : String(cssr.observed));
      await expect(article.locator('[data-kpi="cssrPct"] td').nth(4)).toHaveText(cssr.denominator === null ? '—' : String(cssr.denominator));
    }
    await expect(page.getByText(/Synthetic preview/)).toHaveCount(0);
    await page.screenshot({ path: info.outputPath('incident-detail.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const countQuery = `SELECT count(*) FROM app.observation_receipt WHERE scope_id='VOLTE-MD-CENTRAL' AND window_start >= '${from}' AND window_start < '${to}';`;
    expect(sql('processing_db', countQuery)).toBe('16');
    command('bash', ['scripts/voice-scenario', from]);
    console.log('Replay published; waiting for consumer acknowledgement.');
    await expect.poll(() => {
      const output = command('docker', ['compose', 'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:9092', '--group', 'telecom-processor-v2', '--describe']);
      const rows = output.split('\n').filter(row => row.trim().startsWith('telecom-processor-v2 '));
      return rows.length > 0 && rows.every(row => {
        const columns = row.trim().split(/\s+/);
        return columns[5] === '0' || (columns[3] === '-' && columns[4] === '0');
      });
    }, { timeout: 60000 }).toBe(true);
    expect(sql('processing_db', countQuery)).toBe('16');
    expect(await incidents()).toEqual([incident]);
    const replayHistory = await (await context.request.get(`/api/services/VOLTE-MD-CENTRAL/kpis?from=${from}&to=${to}&size=100`)).json();
    expect(replayHistory.items).toEqual(history.items);
    expect(await (await context.request.get(`/api/incidents/${incident.id}/detections?size=100`)).json()).toEqual(updates);
    await page.reload();
    await expect(page.locator('[data-detection-id]')).toHaveCount(6);
    console.log(`G1 verified: ${from} to ${to}; incident ${incident.id}; 8 windows, 16 observations, 6 evidence updates; replay unchanged.`);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect((await context.request.get('/api/auth/me', { maxRedirects: 0 })).status()).toBe(401);
  } finally {
    await context.close();
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql('incidents_db', `DELETE FROM app.analysts WHERE subject='${userId}' AND display_name='G1 verification';`);
    }
  }
});
