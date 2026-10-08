import { test, expect, type Page } from '@playwright/test';
import type { Incident } from '../../../src/app/core/api/telecom-client';

async function signIn(page: Page, username: string, password: string) {
  try {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect.poll(() => new URL(page.url()).pathname).toBe('/dashboard');
  } catch {
    throw new Error('G3 login failed; credential details omitted.');
  }
}

test('G3 real analyst investigation from login to logout', async ({ page, context }) => {
  test.skip(!process.env.E2E_REAL_LOGIN, 'Requires the protected local stack');
  test.setTimeout(15 * 60_000);
  const username = process.env.SESSION_USERNAME;
  const password = process.env.SESSION_PASSWORD;
  const incidentId = process.env.G3_INCIDENT_ID;
  if (!username || !password || !incidentId || !process.env.E2E_BASE_URL) {
    throw new Error('Set SESSION_USERNAME, SESSION_PASSWORD, G3_INCIDENT_ID and E2E_BASE_URL.');
  }

  const anonymous = await context.request.get('/api/auth/me', { maxRedirects: 0 });
  expect(anonymous.status()).toBe(401);
  await signIn(page, username, password);

  const me = await context.request.get('/api/auth/me');
  expect(me.status()).toBe(200);
  const actor = await me.json();
  expect(actor.roles).toContain('ANALYST');
  expect(actor.roles).not.toContain('SUPERVISOR');
  expect(actor.roles).not.toContain('ADMIN');

  const response = await context.request.get(`/api/incidents/${encodeURIComponent(incidentId)}`);
  expect(response.status()).toBe(200);
  const incident = await response.json() as Incident;
  expect(incident.status).toBe('OPEN');
  expect(incident.assigneeId).toBeNull();
  expect(incident.technicalState).toBe('ONGOING');

  const from = new Date(Date.parse(incident.firstObservedAt) - 60_000);
  const to = new Date(Date.parse(incident.lastObservedAt) + 60_000);
  expect(to.getTime() - from.getTime()).toBeLessThanOrEqual(86_400_000);

  await page.locator(`a[href="/services/${incident.scopeId}"]`).first().click();
  await page.getByLabel('From (UTC)', { exact: true })
    .fill(from.toISOString().slice(0, 16));
  await page.getByLabel('To (UTC, exclusive)')
    .fill(to.toISOString().slice(0, 16));
  await page.getByRole('button', { name: 'Apply time range' }).click();

  const card = page.locator(`[data-episode-id="${incident.episodeId}"]`);
  await expect(card).toBeVisible();
  await expect(card).toContainText('ONGOING');
  await expect(card).toContainText('OPEN');
  await card.getByRole('link', { name: 'Open incident detail' }).click();

  await expect(page.getByRole('heading', { name: 'Incident investigation' }))
    .toBeVisible();
  await expect(page.locator('.detail-panel').first()).toContainText(
    `Severity: ${incident.severity}`,
  );
  await expect(page.getByText('The issue is still ongoing', { exact: false })).toBeVisible();
  await expect(page.locator('[data-fact=confidence]').first()).toBeVisible();
  await expect(page.locator('[data-fact=rank]').first()).toBeVisible();

  await expect(page.getByRole('button', { name: 'Add comment' })).toHaveCount(0);
  await expect(page.getByLabel('Assign to enabled analyst')).toHaveCount(0);

  const claim = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/assignment`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Claim for myself' }).click();
  expect((await claim).status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Start investigation' }))
    .toBeVisible();

  const investigate = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/status`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Start investigation' }).click();
  expect((await investigate).status()).toBe(200);
  await expect(page.getByText('Workflow: INVESTIGATING')).toBeVisible();

  await page.getByLabel('Investigation comment').fill(
    'Reviewed current service evidence; awaiting technical recovery.',
  );
  const comment = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/comments`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Add comment' }).click();
  expect((await comment).status()).toBe(200);
  await expect(page.locator('app-incident-actions').getByRole('status'))
    .toContainText('Comment saved.');

  await expect(page.getByRole('button', { name: 'Resolve incident' }))
    .toBeDisabled();

  // The authorized scenario now supplies real recovery evidence. The API
  // read only synchronizes the test; the analyst uses the visible refresh.
  await expect.poll(async () => {
    const latest = await context.request.get(
      `/api/incidents/${encodeURIComponent(incidentId)}`,
    );
    return latest.status() === 200
      ? (await latest.json()).technicalState
      : 'UNAVAILABLE';
  }, { timeout: 12 * 60_000, intervals: [15_000] }).toBe('RECOVERED');
  await page.getByRole('button', { name: 'Refresh incident' }).click();
  await expect(page.locator('.detail-panel').first())
    .toContainText('Technical state: RECOVERED');
  await expect(page.getByText('The service has recovered.', { exact: false }))
    .toBeVisible();

  await page.getByLabel('Resolution note').fill(
    'Verified recovered service evidence and completed analyst review.',
  );
  const resolve = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/status`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Resolve incident' }).click();
  expect((await resolve).status()).toBe(200);
  await expect(page.locator('app-incident-actions'))
    .toContainText('Workflow: RESOLVED');
  await expect(page.locator('app-incident-actions'))
    .toContainText('Technical state: RECOVERED');

  const persisted = await context.request.get(`/api/incidents/${encodeURIComponent(incidentId)}`);
  expect(persisted.status()).toBe(200);
  expect((await persisted.json()).status).toBe('RESOLVED');

  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect.poll(() => new URL(page.url()).pathname).toBe('/signed-out');
  expect((await context.request.get('/api/auth/me', { maxRedirects: 0 })).status())
    .toBe(401);
});
