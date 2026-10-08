import { test, expect } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { admin, command, sql } from '../helpers/live-stack';
import type { GeographyCatalogue, ServiceSummary } from '../../../src/app/core/api/telecom-client';
import type { components } from '../../../src/app/core/api/schema';

test.skip(process.env.E2E_CONNECTIONS_LIVE !== '1', 'Opt-in authenticated local API audit; no intercepted responses.');

test('every published city, containment node, dependency and service reaches the frontend', async ({ page, context }, info) => {
  let userId = '';
  const report = { capturedAtUTC: new Date().toISOString(), revision: command('git', ['rev-parse', 'HEAD']),
    mode: 'Real OIDC and protected APIs; synthetic local telemetry; no response interception',
    cities: [] as string[], nodes: [] as string[], dependencies: [] as string[], services: [] as string[],
    chartSelections: 0, shadowRows: 0, priorityItems: 0, unavailableShadowScopes: [] as string[], browserErrors: [] as string[],
    requests: [] as { path: string; status: number }[], failedRequests: [] as { path: string; error: string }[], passed: false };
  const read = async <T>(path: string): Promise<T> => {
    const response = await context.request.get(path);
    expect(response.status(), path).toBe(200);
    return response.json();
  };
  page.on('pageerror', error => report.browserErrors.push(error.message));
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/') && !url.pathname.endsWith('/stream'))
      report.requests.push({ path: url.pathname + url.search, status: response.status() });
  });
  page.on('requestfailed', request => {
    const path = new URL(request.url()).pathname;
    const error = request.failure()?.errorText ?? 'Unknown network failure';
    if (path.startsWith('/api/') && !/ERR_ABORTED/.test(error)) report.failedRequests.push({ path, error });
  });
  try {
    expect((await context.request.get('/api/services')).status()).toBe(401);
    const username = 'connections-check-' + randomBytes(6).toString('hex');
    const password = randomBytes(24).toString('base64url') + '!Aa1';
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Connection', lastName: 'Verification', emailVerified: true,
      email: `${username}@example.invalid`, credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Connection verification']);
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Continue to sign in', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
    } catch { throw new Error('Authenticated connection check failed; credentials omitted.'); }
    const catalogue = await read<GeographyCatalogue>('/api/geography/cities');
    const inventory = await read<ServiceSummary[]>('/api/services');
    const scopes = new Set(inventory.map(item => item.scope.scopeId));
    expect(catalogue.cities).toHaveLength(10);
    expect(new Set(catalogue.cities.flatMap(city => city.services.map(item => item.scopeId))).size).toBe(20);
    const treePanel = page.locator('app-city-evidence details').filter({
      has: page.locator('summary', { hasText: 'Containment and dependencies' }),
    }).first();
    for (const city of catalogue.cities) {
      await page.getByLabel('Region', { exact: true }).fill(city.displayName);
      await expect(page.getByRole('heading', { name: `${city.displayName} investigation`, exact: true })).toBeVisible();
      for (const service of city.services) {
        expect(scopes.has(service.scopeId)).toBe(true);
        await expect(page.locator(`.chart-scope[data-scope="${service.scopeId}"]`)).toBeVisible();
        await expect.poll(() => report.requests.some(row =>
          row.path.startsWith(`/api/services/${service.scopeId}/kpis?`) && row.status === 200)).toBe(true);
      }
      if (!await treePanel.evaluate(element => (element as HTMLDetailsElement).open))
        await treePanel.locator('summary').first().click();
      const walk = async (parentId?: string): Promise<void> => {
        for (let number = 0; number < 50; number++) {
          const query = new URLSearchParams({ catalogueVersion: catalogue.catalogueVersion, page: String(number), size: '20' });
          if (parentId) query.set('parentId', parentId);
          const tree = await read<components['schemas']['GeographyTopologyPage']>(`/api/geography/cities/${city.cityId}/topology?${query}`);
          expect(tree.topologyVersion).toBe(catalogue.topologyVersion);
          await expect(treePanel).toContainText(`Parent ${tree.parentId} ·`);
          for (const dependency of tree.dependencies) {
            expect(city.services.some(item => item.scopeId === dependency.scopeId)).toBe(true);
            await expect(treePanel).toContainText(`${dependency.service} ${dependency.role} → ${dependency.nodeId}`);
            report.dependencies.push(dependency.nodeId);
          }
          for (const node of tree.nodes) {
            expect(node.parentId).toBe(tree.parentId);
            const button = treePanel.getByRole('button', { name: `${node.kind} ${node.nodeId}${node.measured ? ' · measured footprint' : ''}`, exact: true });
            await expect(button).toBeVisible();
            report.nodes.push(node.nodeId);
            if (node.kind === 'CELL') await expect(button).toBeDisabled();
            else {
              await button.click();
              await walk(node.nodeId);
              await treePanel.getByRole('button', { name: 'Back to parent', exact: true }).click();
              await expect(treePanel).toContainText(`Parent ${tree.parentId} ·`);
              // Returning to a parent starts on its first page.
              for (let previous = 0; previous < number; previous++)
                await treePanel.getByRole('navigation', { name: 'Topology pages' }).getByRole('button', { name: 'Next', exact: true }).click();
            }
          }
          if (!tree.hasNext) return;
          await treePanel.getByRole('navigation', { name: 'Topology pages' }).getByRole('button', { name: 'Next', exact: true }).click();
        }
        throw new Error('Topology exceeds the bounded audit traversal.');
      };
      await walk();
      await expect(treePanel).toContainText('Local device measurements: Unavailable');
      const history = page.locator('app-city-evidence > details.city-evidence');
      if (!await history.evaluate(element => (element as HTMLDetailsElement).open))
        await history.locator('summary').first().click();
      await expect(page.locator('app-city-evidence [data-city-window]').first()).toBeVisible();
      await expect(page.locator('app-city-evidence [role=alert]')).toHaveCount(0);
      report.cities.push(city.cityId);
      console.log(`Verified ${city.displayName}: authoritative scope pair, containment and dependency nodes.`);
    }
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await page.getByRole('button', { name: 'Open incident investigation', exact: true }).click();
    const priority = await read<components['schemas']['GeographyPriorityPage']>('/api/operations/priority?cityId=ORH&page=0&size=20');
    for (const item of priority.items) {
      await expect(page.locator(`.queue-item a[href="/incidents/${item.incidentId}"]`)).toBeVisible();
      report.priorityItems++;
    }
    await page.getByRole('button', { name: 'Close incidents', exact: true }).click();
    await page.goto('/dashboard?view=scopes');
    await expect(page.locator('.service-row')).toHaveCount(inventory.length);
    for (const service of inventory)
      await expect(page.getByRole('link', { name: service.scope.scopeId, exact: true }))
        .toHaveAttribute('href', `/services/${service.scope.scopeId}`);
    await page.getByRole('link', { name: 'Open connected overview', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Network overview', exact: true })).toBeVisible();
    for (const service of inventory) {
      await page.goto(`/services/${service.scope.scopeId}`);
      await expect(page.locator('.heading-copy')).toContainText(service.scope.scopeId);
      const switches = page.getByRole('group', { name: 'Graph KPI' }).getByRole('button');
      await expect(switches).toHaveCount(service.scope.service === 'SMS' ? 5 : 8);
      for (const button of await switches.all()) {
        await button.click();
        await expect(button).toHaveAttribute('aria-pressed', 'true');
        await expect(page.locator('.service-hero svg[role=img]')).toBeVisible();
        report.chartSelections++;
      }
      await page.getByText('Service dependencies and source quality', { exact: true }).click();
      const nodes = page.locator('app-service-path [data-node-id]');
      await expect(nodes).toHaveCount(service.scope.dependencyIds.length);
      expect(await nodes.evaluateAll(elements => elements.map(element => element.getAttribute('data-node-id'))))
        .toEqual(service.scope.dependencyIds);
      report.dependencies.push(...service.scope.dependencyIds);
      if (service.scope.service === 'SMS') {
        const shadow = page.waitForResponse(response => new URL(response.url()).pathname === `/api/services/${service.scope.scopeId}/ml-shadow`);
        await page.locator('app-sms-shadow summary').click();
        const response = await shadow;
        if (response.status() === 404) {
          await expect(page.locator('app-sms-shadow [role=status]')).toHaveText('SMS classifier evidence is unavailable for this scope.');
          await expect(page.locator('[data-shadow-id]')).toHaveCount(0);
          report.unavailableShadowScopes.push(service.scope.scopeId);
        } else {
          expect(response.status()).toBe(200);
          const evidence = await response.json() as components['schemas']['MlShadowPage'];
          for (const item of evidence.items) {
            expect(item.scopeId).toBe(service.scope.scopeId);
            await expect(page.locator(`[data-shadow-id="${item.evidenceId}"]`)).toBeVisible();
          }
          report.shadowRows += evidence.items.length;
        }
      }
      report.services.push(service.scope.scopeId);
      console.log(`Verified ${service.scope.scopeId}: KPI selections, dependencies${service.scope.service === 'SMS' ? ' and shadow evidence' : ''}.`);
    }
    expect(report.browserErrors).toEqual([]);
    expect(report.requests.filter(row => row.status >= 400 && row.path !== '/api/auth/me'
      && !(row.status === 404 && report.unavailableShadowScopes.some(scope => row.path.startsWith(`/api/services/${scope}/ml-shadow?`))))).toEqual([]);
    expect(report.failedRequests).toEqual([]);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    report.nodes = [...new Set(report.nodes)];
    report.dependencies = [...new Set(report.dependencies)];
    report.passed = true;
  } finally {
    writeFileSync(info.outputPath('connections-live.json'), JSON.stringify(report, null, 2));
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Connection verification';`);
    }
  }
});
