import { test, expect } from '../helpers/test';
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { command, admin, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_DASHBOARD_LIVE !== '1', 'Opt-in local stack with the full recorded archive.');

test('real dashboard has fresh telemetry and populated history in all five domains', async ({ page, context }, info) => {
  test.setTimeout(180_000);
  const username = 'dashboard-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  let userId = '';
  const evidence: object[] = [];
  try {
    expect((await context.request.get('/api/v2/datasets')).status()).toBe(401);
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Dashboard', lastName: 'Check',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Unexpected temporary user identity.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Dashboard verification']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
    } catch { throw new Error('Real identity login failed; credentials omitted.'); }
    await expect(page.getByRole('heading', { name: 'Network overview', exact: true })).toBeVisible();
    // The publisher waits for a whole minute observed after readiness.
    await expect(page.locator('.headline-metrics')).not.toContainText('Unavailable', { timeout: 120_000 });
    await expect(page.locator('.service-panel .actual-line').first()).not.toHaveAttribute('d', '');
    await expect(page.locator('[aria-labelledby="sms-panel-title"]')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Roaming overview', exact: true })).toHaveCount(0);
    const summariesResponse = await context.request.get('/api/services');
    expect(summariesResponse.status()).toBe(200);
    const summaries = await summariesResponse.json();
    for (const service of ['VOLTE', 'SMS']) {
      const fresh = summaries.filter((item: any) => item.scope.service === service && item.freshness === 'FRESH'
        && item.latestWindow?.quality === 'COMPLETE'
        && Date.now() - Date.parse(item.latestWindow.windowEnd) < 180_000);
      expect(fresh.length, `${service} fresh complete measurements`).toBeGreaterThan(0);
      evidence.push({ service, freshCompleteScopes: fresh.length });
    }
    await page.getByLabel('Service', { exact: true }).selectOption('SMS');
    await expect(page.locator('.headline-metrics')).toContainText('Delivered messages');
    await expect(page.locator('.headline-metrics')).not.toContainText('Unavailable');
    await expect(page.locator('[aria-labelledby="volte-panel-title"]')).toBeHidden();
    await expect(page.locator('[aria-labelledby="sms-panel-title"]')).toBeVisible();
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.screenshot({ path: info.outputPath('dashboard-sms-desktop.png'), fullPage: true, mask: [page.locator('.identity')] });
    await page.getByLabel('Service', { exact: true }).selectOption('ALL');
    await page.getByRole('link', { name: 'Open backhaul history', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Approved topology' })).toContainText('10 active cities · 12 active links');
    await expect(page.getByRole('combobox', { name: 'Registered persisted dataset', exact: true })).toHaveValue('trace-link-md-30d-v1');
    const datasetsResponse = await context.request.get('/api/v2/datasets');
    expect(datasetsResponse.status()).toBe(200);
    const dataset = (await datasetsResponse.json()).find((item: any) => item.datasetId === 'trace-link-md-30d-v1');
    expect(dataset.archiveReady).toBe(true);
    for (const [domain, metric, scopeType, scopeId] of [
      ['volte', 'volte.setup.success', 'CITY', 'chi'],
      ['sms', 'sms.delivered', 'CITY', 'chi'],
      ['transport', 'transport.traffic', 'LINK_DIRECTION', 'chi-hin:a-to-b'],
      ['roaming', 'roaming.registration.success', 'PARTNER_DIRECTION', 'RO-DEMO-01:out'],
      ['mobile-data', 'mobileData.activation.success', 'CITY', 'chi'],
    ]) {
      const query = new URLSearchParams({ datasetId: dataset.datasetId, domain, metric, scopeType, scopeId,
        from: new Date(Date.parse(dataset.to) - 3600_000).toISOString(), to: dataset.to, resolution: '5m' });
      const response = await context.request.get('/api/v2/metrics/series?' + query);
      expect(response.status(), metric).toBe(200);
      const series = await response.json();
      expect(series.points).toHaveLength(12);
      expect(series.points.every((point: any) => point.value !== null), metric).toBe(true);
      evidence.push({ metric, measuredBuckets: series.points.length });
    }
    await page.getByRole('button', { name: 'chi-hin a-to-b', exact: true }).click();
    await page.getByRole('combobox', { name: 'Metric', exact: true }).selectOption('transport.traffic');
    await expect(page.getByRole('region', { name: 'Persisted metric chart' })).toContainText('transport.traffic');
    for (const width of [1366, 390]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    }
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect((await context.request.get('/api/v2/datasets')).status()).toBe(401);
  } finally {
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Dashboard verification';`);
    }
    writeFileSync(info.outputPath('dashboard-data.json'), JSON.stringify({ mode: 'Real OIDC and persisted local data; no request interception', evidence }, null, 2));
  }
});
