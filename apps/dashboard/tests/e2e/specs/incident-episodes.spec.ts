import { test, expect } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';
import type { Incident } from '../../../src/app/core/api/telecom-client';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled incident presentation; real workflows run separately.');

for (const service of ['VOLTE', 'SMS'] as const) for (const width of [1366, 390]) {
  test(`${service} episodes share problem, evidence, states and actions at ${width}px`, async ({ page }, info) => {
    const state = await controlledApi(page);
    state.summary.scope.service = service;
    state.incident.service = service;
    state.incident.latestDetection.service = service;
    state.incident.latestDetection.anomalyType = service === 'VOLTE' ? 'VOLTE_SETUP_DEGRADATION' : 'SMS_DELIVERY_DELAY';
    state.incident.latestDetection.kpis = service === 'VOLTE'
      ? [{ name: 'cssrPct', observed: 96.2, baseline: 99.3, unit: 'PERCENT', numerator: 962, denominator: 1000 }]
      : [{ name: 'p95DeliveryMs', observed: 3400, baseline: 2000, unit: 'MILLISECONDS', numerator: null, denominator: null },
        { name: 'deliveredMessages', observed: 1000, baseline: null, unit: 'COUNT', numerator: null, denominator: null }];
    const items: Incident[] = Array.from({ length: 7 }, (_, index) => ({ ...structuredClone(state.incident),
      id: index === 0 ? state.incident.id : `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
      episodeId: String(index).padStart(64, '0'),
    }));
    for (const index of [1, 2]) {
      items[index].technicalState = 'RECOVERED';
      items[index].latestDetection.technicalState = 'RECOVERED';
      items[index].latestDetection.phase = 'RECOVERY';
      items[index].latestDetection.kpis[0].observed = service === 'VOLTE' ? 99.3 : 2000;
      items[index].status = index === 1 ? 'OPEN' : 'RESOLVED';
    }
    items[3].technicalState = 'UNKNOWN';
    items[3].latestDetection.technicalState = 'UNKNOWN';
    items[3].latestDetection.phase = 'UNKNOWN';
    items[4].latestDetection.kpis[0].observed = null;
    if (service === 'VOLTE') items[5].latestDetection.kpis[0].denominator = 0;
    else items[5].latestDetection.kpis[1].observed = 0;
    items[6].latestDetection.kpis[0].baseline = null;
    await page.route(/\/api\/incidents\?/, route => route.fulfill({ json: { items, total: items.length, page: 0, size: 20 } }));
    await page.setViewportSize({ width, height: 900 });
    await page.clock.setFixedTime(new Date('2026-10-08T12:00:10Z'));
    await page.goto(`/services/${state.summary.scope.scopeId}`);
    const panel = page.locator('app-incident-list > section');
    const cards = panel.locator('.episode-card');
    await expect(cards).toHaveCount(items.length);
    const problem = service === 'VOLTE' ? 'VoLTE setup success drop' : 'SMS delivery delay';
    for (const item of items) {
      // Stable episode IDs also keep evidence actions attached to their own incident.
      const episode = panel.locator(`.episode-card[data-episode-id="${item.episodeId}"]`);
      await expect(episode.getByRole('heading', { level: 3 })).toContainText(problem);
      await expect(episode.locator('.episode-severity')).toContainText(item.severity);
      await expect(episode.locator('.episode-time')).toContainText('UTC');
      await expect(episode.locator('.episode-state')).toHaveText(item.technicalState);
      await expect(episode.locator('.episode-workflow')).toContainText(item.status);
      await expect(episode.getByRole('link', { name: 'Open incident detail', exact: true }))
        .toHaveAttribute('href', `/incidents/${item.id}`);
      expect(await episode.evaluate(node => {
        const title = node.querySelector('h3')!.getBoundingClientRect();
        const description = node.querySelector('.episode-description')!.getBoundingClientRect();
        const states = node.querySelector('.episode-info')!.getBoundingClientRect();
        const actions = node.querySelector('.episode-actions')!.getBoundingClientRect();
        return title.bottom <= description.top && description.bottom <= states.top && states.bottom <= actions.top;
      })).toBe(true);
    }
    await expect(cards.nth(0).locator('.episode-description')).toHaveText(service === 'VOLTE'
      ? 'Latest call setup success: 96.2% (baseline 99.3%).'
      : 'Latest delivery delay (p95): 3,400 ms (baseline 2,000 ms).');
    await expect(cards.nth(1).locator('.episode-workflow')).toHaveText('OPEN · Awaiting analyst resolution');
    await expect(cards.nth(2).locator('.episode-workflow')).toHaveText('RESOLVED');
    await expect(cards.nth(3).locator('.episode-description')).toHaveText('Current evidence is incomplete; recovery is not confirmed.');
    for (const index of [4, 5]) await expect(cards.nth(index).locator('.episode-description')).toContainText('measurements are unavailable');
    await expect(cards.nth(6).locator('.episode-description')).not.toContainText('baseline');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await panel.screenshot({ path: info.outputPath(`episodes-${service}-${width}.png`), animations: 'disabled' });
    await cards.first().getByRole('button', { name: 'View incident evidence', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Incident evidence', exact: true })).toContainText(problem);
    await page.keyboard.press('Escape');
    await expect(cards.first().getByRole('button', { name: 'View incident evidence', exact: true })).toBeFocused();
    await panel.getByRole('combobox', { name: 'State', exact: true }).selectOption('RECOVERED');
    await expect(cards).toHaveCount(2);
    await panel.getByRole('combobox', { name: 'State', exact: true }).selectOption('');
    await cards.first().getByRole('link', { name: 'Open incident detail', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Incident investigation', exact: true })).toBeVisible();
  });
}
