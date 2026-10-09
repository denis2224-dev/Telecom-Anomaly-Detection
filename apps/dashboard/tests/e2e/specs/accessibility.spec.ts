import { test, expect, type Locator, type Page } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled UI cases; real sign-in is reviewed separately');

async function tabTo(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  for (let step = 0; step < 100; step++) {
    if (await target.evaluate(element => element === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error('Control could not be reached with Tab');
}

async function visibleFocus(target: Locator) {
  await expect(target).toBeFocused();
  expect(await target.evaluate(element => {
    const style = getComputedStyle(element);
    return style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) >= 2;
  })).toBe(true);
}

async function noPageOverflow(page: Page) {
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
}

test('keyboard: skip link, service chart, incident workflow, conflict, Escape', async ({ page }) => {
  const state = await controlledApi(page);
  state.writeStatus = 409;
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to workspace' })).toBeFocused();
  await visibleFocus(page.getByRole('link', { name: 'Skip to workspace' }));
  await page.keyboard.press('Enter');
  await expect(page.locator('#workspace')).toBeFocused();

  const serviceLink = page.getByRole('link', { name: 'VoLTE setup', exact: true });
  await tabTo(page, serviceLink);
  await visibleFocus(serviceLink);
  await page.keyboard.press('Enter');
  const chart = page.getByLabel('Scrollable CSSR chart', { exact: true });
  await tabTo(page, chart);
  await visibleFocus(chart);
  await page.keyboard.press('Home');
  await expect(page.locator('.service-hero .chart-tooltip')).toContainText('10:00');
  await page.keyboard.press('End');
  await expect(page.locator('.service-hero .chart-tooltip')).toContainText('10:09');
  const evidence = page.getByRole('link', { name: 'Open incident detail', exact: true });
  await tabTo(page, evidence);
  await page.keyboard.press('Enter');
  const opener = page.getByRole('button', { name: 'Details & workflow', exact: true });
  await tabTo(page, opener);
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Details & workflow' });
  await expect(dialog).toBeVisible();
  const comment = page.getByLabel('Investigation comment');
  await tabTo(page, comment);
  await visibleFocus(comment);
  await page.keyboard.insertText('Keyboard review; keep draft after conflict.');
  await tabTo(page, page.getByRole('button', { name: 'Add comment' }));
  await page.keyboard.press('Enter');
  await expect(page.locator('app-incident-actions').getByRole('alert'))
    .toContainText('incident changed');
  await expect(comment).toHaveValue('Keyboard review; keep draft after conflict.');
  // Native showModal makes the rest of the page inert. At the document boundary,
  // Chromium can move focus to browser chrome and report body as activeElement.
  for (let step = 0; step < 12; step++) {
    await page.keyboard.press('Tab');
    expect(await dialog.evaluate(element =>
      element.contains(document.activeElement) || document.activeElement === document.body)).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(opener).toBeFocused();
});

for (const width of [1366, 1024, 390, 320]) {
  test(`long evidence, labels and non-color state at ${width}px`, async ({ page }, info) => {
    const state = await controlledApi(page);
    const explanation = 'Radio evidence requires checking the sampled window and dependency counters. '.repeat(30);
    const source = 'source-' + 'a'.repeat(300);
    state.incident.latestDetection.probableCause = explanation;
    state.incident.latestDetection.recommendedChecks = [explanation];
    state.incident.latestDetection.evidence = [{
      code: 'LONG_EVIDENCE', summary: explanation, nodeId: 'IMS-A', sourceEventIds: [source],
    }];
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/incidents/${state.incident.id}`);
    const update = page.locator('[data-detection-id]');
    await update.getByText('Cause hypothesis & recommended checks', { exact: true }).click();
    await expect(update.getByText('Source evidence', { exact: true })).toHaveCount(0);
    await expect(update).toContainText(explanation);
    await expect(update.getByText(source, { exact: true })).toBeHidden();
    await update.getByText('Troubleshooting', { exact: true }).click();
    await expect(update.getByText(source, { exact: true })).toBeVisible();
    await expect(page.locator('.incident-summary-bar')).toContainText(state.incident.severity);
    await expect(page.locator('.incident-summary-bar')).toContainText('ONGOING');
    // The long prose must wrap; a screenshot alone does not prove text was retained.
    const prose = update.getByRole('region', { name: 'Cause hypothesis' }).locator('p').first();
    expect(await prose.evaluate(element => {
      const style = getComputedStyle(element);
      return element.scrollWidth <= element.clientWidth + 1
        && element.scrollHeight <= element.clientHeight + 1
        && style.textOverflow !== 'ellipsis' && style.webkitLineClamp === 'none';
    })).toBe(true);
    await noPageOverflow(page);
    await page.getByRole('button', { name: 'Details & workflow' }).click();
    const controls = page.getByRole('dialog').locator('button, input, select, textarea');
    for (const control of await controls.all()) {
      if (await control.isVisible()) await expect(control).toHaveAccessibleName(/.+/);
    }
    await noPageOverflow(page);
    await page.screenshot({ path: info.outputPath(`long-evidence-${width}.png`), fullPage: true });
  });
}

test('chart summary, exact values, nulls, measured zero, zero denominator', async ({ page }) => {
  const state = await controlledApi(page);
  const cssr = state.windows[0].kpis[0];
  Object.assign(cssr, { observed: 0, numerator: 0, denominator: 1000 });
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  const chart = page.locator('.service-hero svg[role="img"]');
  await expect(chart).toHaveAccessibleName(/successful and failed traffic on the left axis, success rate and baseline on the right axis/);
  await expect(chart).toHaveAccessibleDescription(/Blank gaps mean unavailable observations/);
  await page.getByText(/^Exact values \(/).click();
  const table = page.locator('app-kpi-chart').getByRole('table');
  await expect(table.locator('[data-window-id="voice-preview-0"] td').first()).toHaveText('0');
  await expect(table.locator('[data-window-id="voice-preview-4"] td').first()).toHaveText('Unavailable');
  await expect(table.locator('[data-window-id="voice-preview-8"] td').first()).toHaveText('Unavailable');
  const region = page.getByRole('region', { name: 'Exact CSSR values and attempt counts' });
  await tabTo(page, region);
  await visibleFocus(region);
  const ids = await page.locator('[id]').evaluateAll(nodes => nodes.map(node => node.id));
  expect(ids.filter((id, index) => ids.indexOf(id) !== index)).toEqual([]);
});

test('anonymous retry control has a keyboard-accessible label', async ({ page }) => {
  const state = await controlledApi(page);
  state.authStatus = 503;
  await page.goto('/login');
  const retry = page.getByRole('button', { name: 'Retry connection' });
  await tabTo(page, retry);
  await visibleFocus(retry);
  state.authStatus = 200;
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/dashboard$/);
});
