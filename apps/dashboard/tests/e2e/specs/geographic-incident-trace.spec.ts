import { test, expect } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { admin, command, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_GEOGRAPHIC_TRACE !== '1', 'Requires persisted live city incidents.');

test('real incident trace pins each Orhei service to opening evidence', async ({ browser }, info) => {
  const username = 'geographic-trace-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  const context = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  const page = await context.newPage();
  let userId = '';
  const result = {
    capturedAt: new Date().toISOString(),
    revision: command('git', ['rev-parse', 'HEAD']),
    mode: 'Real OIDC login, protected incident/priority API and persisted immutable opening evidence',
    incidents: [] as Array<{
      incidentId: string; service: string; scopeId: string; technicalState: string;
      analystStatus: string; cityId: string; topologyVersion: string;
      openingEvidenceId: string; openingWindowStart: string; containmentPath: string[];
    }>,
    passed: false,
  };
  const read = async (path: string) => {
    const response = await context.request.get(path);
    expect(response.status(), path).toBe(200);
    return response.json();
  };
  try {
    expect((await context.request.get('/api/incidents')).status()).toBe(401);
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Geographic', lastName: 'Trace',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Geographic trace']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);

    const priority = await read('/api/operations/priority?cityId=ORH&size=100');
    expect(priority.policyVersion).toBe('geographic-priority-v1');
    expect(priority.policyStatus).toBe('ACTIVE');
    for (const [service, scopeId] of [['VOLTE', 'VOLTE-MD-ORH'], ['SMS', 'SMS-MD-ORH']]) {
      const listing = await read(`/api/incidents?scopeId=${scopeId}&size=100`);
      expect(listing.items.length, `${scopeId} real incident`).toBeGreaterThan(0);
      const detail = await read(`/api/incidents/${listing.items[0].id}`);
      expect(detail.service).toBe(service);
      expect(detail.scopeId).toBe(scopeId);
      expect(detail.location.cityId).toBe('ORH');
      expect(detail.location.measuredScopeId).toBe(scopeId);
      expect(detail.location.containmentPath).toContain('CITY-MD-ORH');
      expect(detail.location.nullReason).toBeNull();
      expect(detail.latestDetection.scopeId).toBe(scopeId);
      const opening = sql(`SELECT detection_id || '|' || window_start || '|' || (payload->>'topologyVersion')
        || '|' || (payload->>'firstObservedAt') FROM app.detection_evidence
        WHERE episode_id='${detail.episodeId}' AND sequence=1;`).split('|');
      expect(opening).toHaveLength(4);
      expect(detail.location.topologyVersion).toBe(opening[2]);
      expect(detail.firstObservedAt).toBe(opening[3]);
      expect(priority.items.some((item: { incidentId: string }) => item.incidentId === detail.id)).toBe(true);
      result.incidents.push({ incidentId: detail.id, service, scopeId,
        technicalState: detail.technicalState, analystStatus: detail.status,
        cityId: detail.location.cityId, topologyVersion: detail.location.topologyVersion,
        openingEvidenceId: opening[0], openingWindowStart: opening[1],
        containmentPath: detail.location.containmentPath });
    }
    result.passed = true;
  } finally {
    try { await context.close(); } catch { /* Preserve the original test failure. */ }
    writeFileSync(info.outputPath('geographic-incident-trace.json'), JSON.stringify(result, null, 2));
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Geographic trace';`);
    }
  }
});
