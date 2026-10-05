import { test, expect } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceWindows, voiceIncidents } from '../../../src/fixtures/voice';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled REST/SSE responses do not verify a live user session');

test('incident notifications and reconnect reload REST; expiry stops refresh', async ({ page }) => {
  await page.clock.install();
  await page.route('**/api/auth/me', route => route.fulfill({ json: {
    analystId: 'controlled-live', displayName: 'Controlled live refresh', roles: ['ANALYST'],
    expiresAt: new Date(Date.now() + 180000).toISOString(),
  } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
    token: 'controlled-test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }));
  let degraded = false, requests = 0;
  await page.route('**/api/services', route => {
    requests++;
    return route.fulfill({ json: [{ ...services[0], latestWindow: voiceWindows[0] }] });
  });
  await page.route('**/api/incidents?**', route => route.fulfill({ json: {
    items: degraded ? [{ ...voiceIncidents[0], technicalState: 'ONGOING' }] : [],
    total: degraded ? 1 : 0,
  } }));
  await page.addInitScript(() => {
    class ControlledSource extends EventTarget {
      onopen: (() => void) | null = null;
      onerror: (() => void) | null = null;
      closed = false;
      constructor() { super(); (window as any).__source = this; (window as any).__created = ((window as any).__created ?? 0) + 1; }
      close() { this.closed = true; }
    }
    (window as any).EventSource = ControlledSource;
  });
  await page.goto('/dashboard');
  await expect(page.locator('.service-assurance-card')).toHaveAttribute('data-health', 'NORMAL');
  await expect.poll(() => page.evaluate(() => (window as any).__created)).toBe(1);
  degraded = true;
  const before = requests;
  await page.evaluate(() => (window as any).__source.dispatchEvent(new MessageEvent('incident-upsert', { data: JSON.stringify({ id: 'controlled-incident', version: 2 }) })));
  await page.clock.runFor(500);
  await expect.poll(() => requests).toBeGreaterThan(before);
  await expect(page.locator('.service-assurance-card')).toHaveAttribute('data-health', 'DEGRADED');
  degraded = false;
  await page.evaluate(() => (window as any).__source.onopen?.());
  await page.clock.runFor(500);
  expect(await page.evaluate(() => (window as any).__created)).toBe(1);
  await expect(page.locator('.service-assurance-card')).toHaveAttribute('data-health', 'NORMAL');
  await page.clock.runFor(180000);
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => (window as any).__source.closed)).toBe(true);
  const ended = requests;
  await page.clock.runFor(60000);
  expect(requests).toBe(ended);
});
