import { test, expect } from '../helpers/test';
import { controlledApi } from '../helpers/controlled-api';
import { recordedRoaming } from '../helpers/recorded-roaming';

for (const width of [1366, 390]) test(`October 13 roaming cards and tables show recorded data at ${width}px`, async ({ page }, info) => {
  await controlledApi(page);
  const state = await recordedRoaming(page);
  await page.setViewportSize({ width, height: 900 });
  await page.goto('/roaming');
  await expect(page.locator('.roaming-kpis > div')).toHaveCount(5);
  await expect(page.locator('.country-panels tbody tr')).toHaveCount(20);
  await expect(page.locator('.roaming-kpis strong')).toHaveText(['98.0%', '1,000', '98.0%', '98.0%', '—']);
  await expect(page.locator('.roaming-chart .actual-line')).not.toHaveAttribute('d', '');
  await expect(page.locator('.roaming-chart .expected-line')).toHaveAttribute('d', '');
  await expect(page.locator('.preview-note')).toContainText('Synthetic recorded outbound');
  await expect(page.locator('.preview-note')).toContainText('2026-10-06 00:00');
  await page.getByRole('combobox', { name: 'Sort roaming countries' }).selectOption('country');
  await expect(page.locator('.country-panels section').first().locator('tbody tr').first()).toContainText('Bulgaria');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: info.outputPath(`restored-roaming-${width}.png`), fullPage: true });
  const beforeFailure = state.reads;
  state.unavailable = true;
  await page.reload();
  await expect(page.locator('.preview-note')).not.toContainText('Loading');
  await expect(page.locator('.country-panels tbody tr')).toHaveCount(0);
  await expect(page.locator('.roaming-kpis strong')).toHaveText(['—','—','—','—','—']);
  await expect(page.locator('.actual-line')).toHaveAttribute('d', '');
  await page.waitForTimeout(400);
  // A failed batch can still deliver its three already-started requests.
  expect(state.reads - beforeFailure).toBeGreaterThan(0);
  expect(state.reads - beforeFailure).toBeLessThanOrEqual(3);
  state.unavailable = false;
  await page.getByRole('button', { name: 'Retry roaming data' }).click();
  await expect(page.locator('.country-panels tbody tr')).toHaveCount(20);
});
