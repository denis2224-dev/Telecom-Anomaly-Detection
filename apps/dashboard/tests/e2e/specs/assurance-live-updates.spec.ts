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
  let degraded = false, streams = 0, requests = 0;
  await page.route('**/api/services', route => {
    requests++;
    return route.fulfill({ json: [{ ...services[0], latestWindow: voiceWindows[0] }] });
  });
  await page.route('**/api/incidents?**', route => route.fulfill({ json: {
    items: degraded ? [{ ...voiceIncidents[0], technicalState: 'ONGOING' }] : [],
    total: degraded ? 1 : 0,
  } }));
  await page.route('**/api/incidents/stream', route => {
    streams++;
    // An actual browser EventSource consumes this controlled notification, then reconnects.
    return route.fulfill({ contentType: 'text/event-stream', body:
      ': connected\n\nevent: incident.upsert\ndata: {"id":"controlled-incident","version":2}\n\n' });
  });
  await page.goto('/dashboard');
  await expect(page.locator('.service-assurance-card')).toHaveAttribute('data-health', 'NORMAL');
  await expect.poll(() => streams).toBe(1);
  degraded = true;
  const before = requests;
  await page.clock.runFor(500);
  await expect.poll(() => requests).toBeGreaterThan(before);
  await expect(page.locator('.service-assurance-card')).toHaveAttribute('data-health', 'DEGRADED');
  degraded = false;
  await page.clock.runFor(30500);
  await expect.poll(() => streams).toBe(2);
  await expect(page.locator('.service-assurance-card')).toHaveAttribute('data-health', 'NORMAL');
  await page.clock.runFor(180000);
  await expect(page).toHaveURL(/\/login$/);
  const ended = { requests, streams };
  await page.clock.runFor(60000);
  expect({ requests, streams }).toEqual(ended);
});
