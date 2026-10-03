import { test, expect, type Page } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents, voiceRange, voiceWindows } from '../../../src/fixtures/voice';
import type { Incident } from '../../../src/app/core/api/telecom-client';

async function mockStream(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class MockEventSource {
      static instances: MockEventSource[] = [];
      onopen: ((event: Event) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      closed = false;
      private listeners = new Map<string, ((event: Event) => void)[]>();

      constructor(readonly url: string) {
        MockEventSource.instances.push(this);
        queueMicrotask(() => this.open());
      }

      addEventListener(name: string, listener: (event: Event) => void) {
        this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]);
      }

      open() {
        if (!this.closed) this.onopen?.(new Event('open'));
      }

      upsert(id: string, version: number) {
        if (this.closed) return;
        const event = new MessageEvent('incident-upsert', {
          data: JSON.stringify({ id, version }),
        });
        for (const listener of this.listeners.get('incident-upsert') ?? []) {
          listener(event);
        }
      }

      fail() {
        if (!this.closed) this.onerror?.(new Event('error'));
      }

      close() { this.closed = true; }
    }

    (window as unknown as { EventSource: typeof MockEventSource }).EventSource = MockEventSource;
    (window as unknown as { __streams: MockEventSource[] }).__streams = MockEventSource.instances;
  });
}

test.describe('Day 13 incident reconnect', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled stream and REST responses');

  test('deduplicates rows, rejects old versions, and preserves the selected range', async ({ page }) => {
    await mockStream(page);
    let current: Incident = {
      ...voiceIncidents[0],
      version: 4,
      technicalState: 'ONGOING',
    };
    let listReads = 0;

    await page.route('**/api/auth/me', route => route.fulfill({ json: {
      analystId: 'day13-test', displayName: 'Day 13 tester',
      roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600_000).toISOString(),
    } }));
    await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
      token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
    } }));
    await page.route('**/api/services', route => route.fulfill({ json:
      services.map((item, index) => index === 0
        ? { ...item, latestWindow: voiceWindows.at(-1), observedAt: voiceRange.to }
        : item),
    }));
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: {
      items: voiceWindows, total: voiceWindows.length, page: 0,
      size: 100, observedAt: '2026-09-15T10:10:00Z',
    } }));
    await page.route('**/api/incidents?**', route => {
      listReads++;
      return route.fulfill({ json: {
        items: [current], total: 1, page: 0, size: 20,
      } });
    });

    await page.goto('/services/VOLTE-MD-CENTRAL');
    await expect(page.locator('.episode-card')).toHaveCount(1);
    await expect(page.locator('.episode-card')).toContainText('ONGOING');

    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-09-15T09:59');
    await page.getByLabel('To (UTC, exclusive)').fill('2026-09-15T10:10');
    await page.getByRole('button', { name: 'Apply time range' }).click();
    await expect(page.locator('.episode-card')).toHaveCount(1);

    // A delayed recovery at version 3 must not change version 4's ongoing state.
    current = { ...current, version: 3, technicalState: 'RECOVERED' };
    let before = listReads;
    await page.evaluate(({ id }) => {
      (window as any).__streams[0].upsert(id, 3);
    }, { id: current.id });
    await expect.poll(() => listReads).toBeGreaterThan(before);
    await expect(page.locator('.episode-card')).toContainText('ONGOING');

    // A committed version 5 is displayed once, even after a reconnect.
    current = { ...current, version: 5, technicalState: 'RECOVERED' };
    before = listReads;
    await page.evaluate(({ id }) => {
      const stream = (window as any).__streams[0];
      stream.upsert(id, 5);
      stream.upsert(id, 5);
      stream.open();
    }, { id: current.id });
    await expect.poll(() => listReads).toBeGreaterThan(before);
    await expect(page.locator('.episode-card')).toContainText('RECOVERED');
    await expect(page.locator('.episode-card')).toHaveCount(1);
    await expect(page.getByLabel('From (UTC)', { exact: true }))
      .toHaveValue('2026-09-15T09:59');

    await page.evaluate(() => (window as any).__streams[0].fail());
    await expect(page.getByText('Live connection interrupted', { exact: false }))
      .toBeVisible();
    await page.evaluate(() => (window as any).__streams[0].open());
    await expect(page.getByText('Live connection interrupted', { exact: false }))
      .toHaveCount(0);

    current = { ...current, version: 3, technicalState: 'ONGOING' };
    before = listReads;
    await page.evaluate(() => {
      (window as any).__streams[0].open();
    });
    await expect.poll(() => listReads).toBeGreaterThan(before);
    await expect(page.locator('.episode-card')).toContainText('RECOVERED');

    await page.locator('a.back-link').click();
    expect(await page.evaluate(() => (window as any).__streams[0].closed))
      .toBe(true);
  });

  test('closes the stream when the session expires', async ({ page }) => {
    await mockStream(page);
    let expired = false;
    await page.route('**/api/auth/me', route => route.fulfill(expired
      ? { status: 401, json: { code: 'UNAUTHENTICATED' } }
      : { json: {
          analystId: 'day13-test', displayName: 'Day 13 tester',
          roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600_000).toISOString(),
        } }));
    await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
      token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
    } }));
    await page.route('**/api/services', route => route.fulfill({ json:
      services.map((item, index) => index === 0
        ? { ...item, latestWindow: voiceWindows.at(-1), observedAt: voiceRange.to }
        : item),
    }));
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: {
      items: voiceWindows, total: voiceWindows.length, page: 0,
      size: 100, observedAt: '2026-09-15T10:10:00Z',
    } }));
    await page.route('**/api/incidents?**', route => route.fulfill({ json: {
      items: voiceIncidents, total: 1, page: 0, size: 20,
    } }));

    await page.goto('/services/VOLTE-MD-CENTRAL');
    await expect(page.locator('.episode-card')).toHaveCount(1);
    expired = true;
    await page.evaluate(() => (window as any).__streams[0].fail());
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate(() => (window as any).__streams[0].closed))
      .toBe(true);
  });
});
