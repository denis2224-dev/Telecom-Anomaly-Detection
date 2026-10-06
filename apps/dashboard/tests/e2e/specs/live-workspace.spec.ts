import { test, expect } from '@playwright/test';
import { controlledApi, streamEvent } from '../helpers/controlled-api';
import suite from '../../../../../contracts/fixtures/detections/service-explanation-cases.json';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled timing and telemetry; real authentication is checked separately');

test('sidebar defaults to a rail and remembers the choice for this browser session', async ({ page }) => {
  const state = await controlledApi(page);
  await page.goto('/dashboard');
  await expect(page.locator('.app-shell')).toHaveClass(/sidebar-collapsed/);
  await expect(page.getByRole('link', { name: 'VoLTE setup', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Expand sidebar', exact: true }).click();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Collapse sidebar', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'VoLTE setup', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/services/${state.summary.scope.scopeId}$`));
  await expect(page.locator('.app-shell')).not.toHaveClass(/sidebar-collapsed/);
  expect((await page.locator('.back-link').boundingBox())!.height).toBeGreaterThanOrEqual(40);
});

test('overview fallback preserves the workspace and stale status catches missing updates', async ({ page }) => {
  const state = await controlledApi(page);
  const start = new Date(Math.floor(Date.now() / 60_000) * 60_000 + 25_000);
  await page.clock.install({ time: start });
  await page.goto('/dashboard');
  await expect(page.locator('.live-status')).toContainText('Live');
  await page.getByLabel('Service', { exact: true }).selectOption('VOLTE');
  await page.getByLabel('Region', { exact: true }).fill('Orhei');
  const from = page.getByLabel('From (UTC)', { exact: true });
  await from.fill('2026-09-15T10:01'); await from.focus();
  await page.evaluate(() => {
    (window as any).__overview = document.querySelector('app-connected-overview');
    window.scrollTo(0, 100);
  });
  await page.clock.pauseAt(new Date(start.getTime() + 10_000));
  const untilMinute = await page.evaluate(() => 60_000 - Date.now() % 60_000);
  const scroll = await page.evaluate(() => scrollY);
  const reads = () => state.requests.filter(path => path === 'GET /api/services').length;
  const before = reads();
  state.summary.openIncidents = 3;
  await page.clock.runFor(untilMinute - 1); expect(reads()).toBe(before);
  await page.clock.runFor(201);
  await expect.poll(reads).toBeGreaterThan(before);
  await expect.poll(async () => {
    await page.clock.runFor(50);
    return page.locator('.kpi-strip > div').filter({ hasText: 'Open incidents' }).textContent();
  }).toContain('3');
  await expect(page.getByLabel('Service', { exact: true })).toHaveValue('VOLTE');
  await expect(page.getByLabel('Region', { exact: true })).toHaveValue('Orhei');
  await expect(from).toHaveValue('2026-09-15T10:01'); await expect(from).toBeFocused();
  expect(await page.evaluate(() => (window as any).__overview === document.querySelector('app-connected-overview'))).toBe(true);
  expect(await page.evaluate(() => scrollY)).toBe(scroll);
  const openingReads = reads();
  await streamEvent(page, 'open'); await page.clock.runFor(200);
  await expect.poll(reads).toBeGreaterThan(openingReads);
  await page.clock.runFor(1000);
  await expect.poll(async () => { await page.clock.runFor(50); return page.locator('.live-status').textContent(); }).toContain('Live');
  const connectedReads = reads();
  await page.clock.runFor(120_000);
  expect(reads()).toBe(connectedReads);
  await expect(page.locator('.live-status')).toContainText('Stale');
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await streamEvent(page, 'error'); await page.clock.runFor(120_000);
  expect(reads()).toBe(connectedReads);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.clock.runFor(200);
  await expect.poll(reads).toBeGreaterThan(connectedReads);
  await expect.poll(async () => { await page.clock.runFor(50); return page.locator('.live-status').textContent(); }).toContain('Live');
});

for (const service of ['VOLTE', 'SMS'] as const) test(`${service} appends real new windows and keeps inspected window and focus`, async ({ page }) => {
  const state = await controlledApi(page);
  const trajectory = suite.cases.find(item => item.id === `${service.toLowerCase()}-fault`)!;
  state.windows = structuredClone(trajectory.windows.map(item => item.feature)) as typeof state.windows;
  state.summary.scope.service = service; state.summary.scope.scopeId = state.windows[0].scopeId;
  state.summary.latestWindow = state.windows.at(-1)!;
  state.incident.scopeId = state.summary.scope.scopeId; state.incident.service = service;
  state.incident.latestDetection = structuredClone(trajectory.detections.at(-1)!) as typeof state.incident.latestDetection;
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  const hero = page.locator('.service-hero');
  const selected = service === 'VOLTE' ? 'rrcSrPct' : 'queueDepth';
  await hero.getByRole('button', { name: service === 'VOLTE' ? 'RRC SR' : 'Queue Depth', exact: true }).click();
  await expect(hero.locator('[data-chart]')).toHaveAttribute('data-chart', selected);
  const plot = hero.locator('.chart-scroll'); await plot.focus();
  await page.keyboard.press('End');
  const inspectedTime = state.windows.at(-1)!.windowStart.slice(11, 19);
  await expect(hero.locator('.chart-tooltip')).toContainText(inspectedTime);
  await page.evaluate(() => {
    (window as any).__plot = document.querySelector('.service-hero .chart-scroll');
    window.scrollTo(0, 120);
  });
  const box = await hero.boundingBox();
  const last = state.windows.at(-1)!;
  const next = structuredClone(last);
  next.windowId += '-next'; next.windowStart = last.windowEnd;
  next.windowEnd = new Date(Date.parse(next.windowStart) + 60_000).toISOString();
  state.windows.push(next); state.summary.latestWindow = next;
  state.summary.observedAt = next.windowEnd;
  await streamEvent(page, 'open');
  await expect(page.locator('.exact-values > summary')).toContainText(`${state.windows.length} windows`);
  await expect(page.getByLabel('To (UTC, exclusive)', { exact: true })).toHaveValue(next.windowEnd.slice(0, 16));
  await expect(hero.locator('[data-chart]')).toHaveAttribute('data-chart', selected);
  await expect(hero.locator('.chart-tooltip')).toContainText(inspectedTime); await expect(plot).toBeFocused();
  expect(await page.evaluate(() => (window as any).__plot === document.querySelector('.service-hero .chart-scroll'))).toBe(true);
  expect((await hero.boundingBox())!.height).toBe(box!.height);
  expect((await hero.boundingBox())!.y).toBe(box!.y);
  expect(await hero.locator('.actual-line').evaluate(node => getComputedStyle(node).animationName)).toBe('none');
  await page.keyboard.press('End');
  await expect(hero.locator('.chart-tooltip')).toContainText(next.windowStart.slice(11, 19));
});
