import { test, expect } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { admin, command, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_SESSION_EXPIRY_LIVE !== '1',
  'Requires a real local OIDC session and the production 15-minute idle deadline.');

test('idle real session expires while the browser is inactive', async ({ browser }, info) => {
  test.setTimeout(17 * 60_000);
  const username = 'expiry-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  const context = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  const page = await context.newPage();
  let userId = '';
  const result = {
    revision: command('git', ['rev-parse', 'HEAD']),
    mode: 'Real OIDC session left idle beyond the 15-minute server deadline',
    lastAuthenticatedAt: '', checkedAt: '', statusAfterIdle: 0, passed: false,
  };
  try {
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Expiry', lastName: 'Check',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Expiry Check']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    expect((await context.request.get('/api/auth/me')).status()).toBe(200);
    await page.close();
    result.lastAuthenticatedAt = new Date().toISOString();
    await new Promise(resolve => setTimeout(resolve, 15 * 60_000 + 5000));
    const expired = await context.request.get('/api/auth/me');
    result.checkedAt = new Date().toISOString();
    result.statusAfterIdle = expired.status();
    expect(expired.status()).toBe(401);
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    result.passed = true;
  } finally {
    writeFileSync(info.outputPath('session-expiry-results.json'), JSON.stringify(result, null, 2));
    try { await context.close(); } catch { /* Context may already be closed. */ }
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Expiry Check';`);
    }
  }
});
