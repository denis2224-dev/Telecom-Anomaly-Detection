import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

const root = resolve(__dirname, '../../..');
const baseURL = process.env.E2E_BASE_URL ?? 'http://telecom.test:8080';
const api = (path: string) => new URL(path, baseURL).toString();

function docker(args: string[], input?: string): string {
  try {
    return execFileSync('docker', ['compose', 'exec', '-T', 'keycloak', 'bash', '-ec', `
      config=$(mktemp); trap 'rm -f "$config"' EXIT
      /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080/auth \
        --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
      /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
    `, '--', ...args], { cwd: root, input, encoding: 'utf8', timeout: 60000 }).trim();
  } catch {
    throw new Error('Supervisor fixture setup failed; sensitive command output omitted.');
  }
}

test.describe('public service scenario commands', () => {
  test('real supervisor login, idempotent retry, schedule and episode phases', async ({ page, context }) => {
    const username = `scenario-${randomBytes(6).toString('hex')}`;
    const password = `${randomBytes(24).toString('base64url')}!Aa1`;
    const id = docker(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Scenario', lastName: 'Supervisor',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    docker(['add-roles', '-r', 'telecom', '--uid', id, '--rolename', 'SUPERVISOR']);
    execFileSync('./scripts/provision-analyst', ['--username', username, '--display-name', 'Scenario supervisor'], { cwd: root });

    await page.goto(`${baseURL}/login`);
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect.poll(() => new URL(page.url()).pathname).toBe('/dashboard');

    const me = await context.request.get(api('/api/auth/me'));
    expect(me.status()).toBe(200);
    expect((await me.json()).roles).toContain('SUPERVISOR');
    const csrf = await (await context.request.get(api('/api/auth/csrf'))).json();
    const requestId = randomUUID();
    const body = { requestId, seed: 29092026, scopeId: `E2E-${randomBytes(4).toString('hex')}` };
    const start = await context.request.post(api('/api/simulator/scenarios/VOLTE_IMS_OVERLOAD'), {
      data: body, headers: { [csrf.headerName]: csrf.token },
    });
    expect(start.status()).toBe(202);
    const first = await start.json();
    const retry = await context.request.post(api('/api/simulator/scenarios/VOLTE_IMS_OVERLOAD'), {
      data: body, headers: { [csrf.headerName]: csrf.token },
    });
    expect(retry.status()).toBe(202);
    expect(await retry.json()).toEqual(first);

    await expect.poll(async () => (await (await context.request.get(api(`/api/simulator/runs/${first.runId}`))).json()).status,
      { timeout: 180000, intervals: [250, 500, 1000, 2000] }).toBe('COMPLETED');
    const run = await (await context.request.get(api(`/api/simulator/runs/${first.runId}`))).json();
    expect(new Date(run.scheduledEndAt).getTime() - new Date(run.scheduledStartAt).getTime()).toBe(8 * 60_000);

    const stop = await context.request.post(api(`/api/simulator/runs/${first.runId}/stop`), {
      headers: { [csrf.headerName]: csrf.token },
    });
    expect([200, 409]).toContain(stop.status()); // completed runs are already immutable.
    await expect.poll(async () => (await (await context.request.get(api(`/api/incidents?scopeId=${body.scopeId}&size=100`))).json()).items
      .flatMap((item: any) => item.latestDetection?.phase ?? []), { timeout: 180000, intervals: [500, 1000, 2000] })
      .toContain('RECOVERY');
  });
});
