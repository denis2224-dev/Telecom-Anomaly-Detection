import { test, expect, type Page } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents } from '../../../src/fixtures/voice';
import type { ServiceSummary } from '../../../src/app/core/api/telecom-client';

type Case = {
  id: string; service: 'VOLTE' | 'SMS'; topic: string;
  missing?: boolean; noBaseline?: boolean; samples?: number;
  stale?: boolean; modelTimeout?: boolean;
};
const cases: Case[] = [
  { id: 'missing-observation', service: 'VOLTE', topic: 'missing-evidence', missing: true },
  { id: 'absent-baseline', service: 'VOLTE', topic: 'baseline-missing', noBaseline: true },
  { id: 'low-volume', service: 'SMS', topic: 'low-volume', samples: 5 },
  { id: 'model-unavailable', service: 'VOLTE', topic: 'ml-unavailable', modelTimeout: true },
  { id: 'stale-evidence', service: 'SMS', topic: 'stale-evidence', stale: true },
];

async function setup(page: Page, scenario: Case) {
  const summary = structuredClone((services as ServiceSummary[])
    .find(item => item.scope.service === scenario.service && item.latestWindow !== null)!);
  summary.freshness = scenario.stale ? 'STALE' : scenario.missing ? 'MISSING' : 'FRESH';
  const window = summary.latestWindow!;
  const primaryName = scenario.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
  if (scenario.noBaseline) window.kpis.find(kpi => kpi.name === primaryName)!.baseline = null;
  if (scenario.samples !== undefined) {
    window.kpis.find(kpi => kpi.name === 'deliveredMessages')!.observed = scenario.samples;
    if (scenario.samples === 0) window.kpis.find(kpi => kpi.name === 'p95DeliveryMs')!.observed = null;
  }
  const item = structuredClone(voiceIncidents[0]);
  item.service = scenario.service;
  item.scopeId = summary.scope.scopeId;
  item.latestSequence = 1;
  item.latestDetection = {
    ...item.latestDetection, service: scenario.service, scopeId: summary.scope.scopeId,
    sequence: 1, anomalyType: scenario.service === 'VOLTE' ? 'VOLTE_SETUP_DEGRADATION' : 'SMS_DELIVERY_DELAY',
    kpis: structuredClone(window.kpis), windowStart: window.windowStart, windowEnd: window.windowEnd,
    mlStatus: scenario.modelTimeout ? 'TIMEOUT' : 'OK',
    anomalyRank: scenario.modelTimeout ? null : 0, modelVersion: scenario.modelTimeout ? null : 'test-model',
  };
  if (scenario.missing) summary.latestWindow = null;
  await page.addInitScript(() => {
    class QuietSource {
      onopen = null;
      onerror = null;
      addEventListener() {}
      close() {}
    }
    (window as any).EventSource = QuietSource;
  });
  await page.route('**/api/auth/me', route => route.fulfill({ json: {
    analystId: 'day16-review', displayName: 'Terminology tester', roles: ['ANALYST'],
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
  } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
    token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }));
  await page.route('**/api/services', route => route.fulfill({ json: [summary] }));
  await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: {
    items: scenario.missing ? [] : [window], total: scenario.missing ? 0 : 1,
    page: 0, size: 100, observedAt: window.windowEnd,
  } }));
  await page.route('**/api/incidents?**', route => route.fulfill({ json: {
    items: [item], total: 1, page: 0, size: 20,
  } }));
  await page.route('**/api/incidents/*', route => route.fulfill({ json: item }));
  await page.route('**/api/incidents/*/detections?**', route => route.fulfill({ json: {
    items: [item.latestDetection], total: 1, page: 0, size: 20,
  } }));
  await page.route('**/api/incidents/*/timeline?**', route => route.fulfill({ json: {
    items: [], total: 0, page: 0, size: 100,
  } }));
  await page.route('**/api/analysts?**', route => route.fulfill({ json: [] }));
  return { summary, item };
}

test.describe('Dashboard terminology', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled UI cases; human acceptance uses normal login separately');

  for (const width of [1366, 390]) for (const scenario of cases) {
    test(`${scenario.id} at ${width}px has an explanation and next action`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      const { summary, item } = await setup(page, scenario);
      if (scenario.noBaseline) {
        await page.goto('/dashboard');
        await expect(page.locator('.service-row')).toContainText('UNKNOWN');
        await expect(page.locator('.service-row')).toContainText('expected value');
        await page.getByRole('link', { name: summary.scope.scopeId, exact: true }).click();
      } else {
        await page.goto(scenario.modelTimeout ? `/incidents/${item.id}` : `/services/${summary.scope.scopeId}`);
      }
      if (scenario.modelTimeout) {
        await page.locator('.cause-details > summary').click();
        await expect(page.locator('[aria-label="Cause hypothesis"]')).toContainText('Model response timed out');
        await expect(page.locator('[aria-label="Cause hypothesis"]')).toContainText('Model anomaly rank: Unavailable');
      }
      const state = page.locator(`aside[data-topic="${scenario.topic}"]`).first();
      await expect(state).toBeVisible();
      await expect(state.locator('strong')).not.toBeEmpty();
      await expect(state.locator('p').last()).not.toBeEmpty();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    });
  }

  test('preserves zero rank and provides help through the keyboard', async ({ page }) => {
    const { item } = await setup(page, { id: 'zero-rank', service: 'VOLTE', topic: 'rank' });
    await page.goto(`/incidents/${item.id}`);
    await page.locator('.cause-details > summary').click();
    const cause = page.locator('[aria-label="Cause hypothesis"]');
    await expect(cause).toContainText('Model anomaly rank: 0');
    await expect(cause).not.toContainText('Model anomaly rank: 0%');
    const details = cause.locator('details[data-topic="rank"]');
    await details.locator('summary').focus();
    await page.keyboard.press('Enter');
    await expect(details).toHaveAttribute('open', '');
    await expect(details).toContainText('not a failure probability');
    await page.keyboard.press('Enter');
    await expect(details).not.toHaveAttribute('open');
  });

  test('keeps queue evidence visible when no messages completed', async ({ page }) => {
    const { summary } = await setup(page, { id: 'zero-samples', service: 'SMS', topic: 'no-samples', samples: 0 });
    await page.goto(`/services/${summary.scope.scopeId}`);
    await expect(page.locator('app-sms-quality')).toContainText('No completed messages in this window');
    await expect(page.locator('app-sms-quality')).toContainText('Delivery p95 Unavailable');
    await expect(page.locator('app-sms-quality')).toContainText('250 messages');
  });
});
