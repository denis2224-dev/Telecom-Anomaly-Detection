import { test, expect, type Page } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents, voiceWindows } from '../../../src/fixtures/voice';

const SCOPE = 'VOLTE-MD-CENTRAL';
const scopeCount = Number(process.env.PERF_SCOPE_COUNT ?? 100);
if (!Number.isInteger(scopeCount) || scopeCount < 1 || scopeCount > 5000) {
  throw new Error('PERF_SCOPE_COUNT must be between 1 and 5000.');
}

async function setup(page: Page) {
  let latestEnd = Date.parse('2026-10-05T12:00:00Z');
  let kpiReads = 0;
  let serviceReads = 0;
  let detailReads = 0;
  let responseDelayMs = 0;
  let incidentReads = 0;
  let lastIncidentPage = -1;
  let lastEvidencePage = -1;
  const incident = (index: number) => ({
    ...voiceIncidents[0], id: `resource-incident-${index}`, episodeId: `resource-episode-${index}`,
    scopeId: SCOPE, detectedAt: new Date(latestEnd - index * 60_000).toISOString(),
  });
  const windowAt = (start: number) => ({
    ...voiceWindows[0], scopeId: SCOPE, windowId: `resource-window-${start}`,
    windowStart: new Date(start).toISOString(), windowEnd: new Date(start + 60_000).toISOString(),
  });

  await page.addInitScript(() => {
    const state = { active: 0, closed: 0, stream: null as MockSource | null };
    class MockSource {
      onopen: ((event: Event) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      private closed = false;
      private listeners = new Map<string, ((event: Event) => void)[]>();
      constructor() {
        state.active++;
        state.stream = this;
        queueMicrotask(() => { if (!this.closed) this.onopen?.(new Event('open')); });
      }
      addEventListener(name: string, listener: (event: Event) => void) {
        this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]);
      }
      hint() {
        if (this.closed) return;
        const event = new MessageEvent('incident-upsert', {
          data: JSON.stringify({ id: 'resource-incident-0', version: 4 }),
        });
        for (const listener of this.listeners.get('incident-upsert') ?? []) listener(event);
      }
      close() {
        if (this.closed) return;
        this.closed = true;
        state.active--;
        state.closed++;
        this.listeners.clear();
      }
    }
    (window as any).EventSource = MockSource;
    (window as any).__resource = state;
  });
  await page.route('**/api/auth/me', route => route.fulfill({ json: {
    analystId: 'resource-test', displayName: 'Resource tester', roles: ['ANALYST'],
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
    token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }));
  await page.route('**/api/geography/cities', route => route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } }));
  await page.route('**/api/operations/priority?**', route => route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } }));
  await page.route('**/api/analysts?**', route => route.fulfill({ json: [] }));
  await page.route('**/api/services', async route => {
    serviceReads++;
    if (responseDelayMs) await new Promise(resolve => setTimeout(resolve, responseDelayMs));
    return route.fulfill({ json:
    Array.from({ length: scopeCount }, (_, index) => ({
      ...services[0],
      scope: { ...services[0].scope, scopeId: index === 0 ? SCOPE : `resource-scope-${index}` },
      latestWindow: { ...windowAt(latestEnd - 60_000), scopeId: index === 0 ? SCOPE : `resource-scope-${index}` },
      observedAt: new Date(latestEnd).toISOString(),
    })),
  }); });
  await page.route('**/api/services/*/kpis?**', route => {
    kpiReads++;
    const query = new URL(route.request().url()).searchParams;
    const from = Date.parse(query.get('from')!);
    const to = Date.parse(query.get('to')!);
    const number = Number(query.get('page') ?? 0);
    const size = Number(query.get('size') ?? 100);
    const total = (to - from) / 60_000;
    if (total <= 0 || total > 1440 || size > 100) {
      return route.fulfill({ status: 400, json: { code: 'INVALID_RANGE' } });
    }
    const count = Math.max(0, Math.min(size, total - number * size));
    return route.fulfill({ json: {
      items: Array.from({ length: count }, (_, index) => windowAt(from + (number * size + index) * 60_000)),
      total, page: number, size, observedAt: new Date(latestEnd).toISOString(),
    } });
  });
  await page.route('**/api/incidents?**', route => {
    incidentReads++;
    const query = new URL(route.request().url()).searchParams;
    const number = Number(query.get('page') ?? 0), size = Number(query.get('size') ?? 20);
    lastIncidentPage = number;
    return route.fulfill({ json: {
      items: Array.from({ length: Math.max(0, Math.min(size, 1000 - number * size)) },
        (_, index) => incident(number * size + index)),
      total: 1000, page: number, size,
    } });
  });
  await page.route('**/api/incidents/resource-incident-*', route => {
    detailReads++;
    const index = Number(new URL(route.request().url()).pathname.split('-').at(-1));
    return route.fulfill({ json: incident(index) });
  });
  await page.route('**/api/incidents/*/timeline?**', route => route.fulfill({ json: {
    items: [], total: 0, page: 0, size: 100,
  } }));
  await page.route('**/api/incidents/*/detections?**', route => {
    const query = new URL(route.request().url()).searchParams;
    const number = Number(query.get('page') ?? 0), size = Number(query.get('size') ?? 20);
    lastEvidencePage = number;
    return route.fulfill({ json: {
      items: Array.from({ length: Math.max(0, Math.min(size, 1000 - number * size)) }, (_, index) => ({
        ...voiceIncidents[0].latestDetection,
        detectionId: `resource-detection-${number * size + index}`,
        sequence: number * size + index + 1,
      })), total: 1000, page: number, size,
    } });
  });
  return {
    advance: () => { latestEnd += 60_000; },
    serviceReads: () => serviceReads,
    detailReads: () => detailReads,
    delay: (ms: number) => { responseDelayMs = ms; },
    reads: () => kpiReads,
    incidentReads: () => incidentReads,
    incidentPage: () => lastIncidentPage,
    evidencePage: () => lastEvidencePage,
  };
}

async function paint(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
}

async function navigateToLoginAndExpectCleanup(page: Page) {
  const retained = await page.evaluateHandle(() => ({
    state: (window as any).__resource, stream: (window as any).__resource.stream,
  }));
  expect(await retained.evaluate(({ state }) => state.active)).toBe(1);
  // Stay in the same document: goto resets the mock, then the authenticated
  // session redirects from /login to /dashboard and opens a new stream.
  await page.evaluate(() => {
    history.pushState(null, '', '/login');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.getByRole('heading', { name: 'Sign in to investigate service issues' })).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
  expect(await retained.evaluate(({ state }) => state === (window as any).__resource)).toBe(true);
  await expect.poll(() => retained.evaluate(({ state }) => state.active)).toBe(0);
  expect(await retained.evaluate(({ stream }) => stream.closed)).toBe(true);
  await retained.dispose();
}

test.describe('Browser resource bounds', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled dataset; use real login separately for backend evidence');

  for (const screen of ['overview', 'service', 'investigation']) test(`${screen}: sustained hints remain responsive with bounded REST refreshes`, async ({ page, context }, info) => {
    test.setTimeout(90_000);
    const data = await setup(page);
    const cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable');
    const sample = async () => {
      await cdp.send('HeapProfiler.collectGarbage');
      const { metrics } = await cdp.send('Performance.getMetrics');
      return Object.fromEntries(metrics.filter(metric => ['JSHeapUsedSize', 'Nodes', 'TaskDuration'].includes(metric.name))
        .map(metric => [metric.name, metric.value]));
    };
    await page.goto(screen === 'overview' ? '/dashboard' : screen === 'service' ? `/services/${SCOPE}` : '/incidents/resource-incident-0');
    await expect(page.locator(screen === 'overview' ? '.overview-panels' : screen === 'service' ? '.service-hero' : '.incident-summary-bar')).toBeVisible();
    await page.waitForTimeout(300);
    const records = [];
    for (const hz of [1, 100]) {
      data.delay(400);
      data.advance();
      const reads = screen === 'investigation' ? data.detailReads : data.serviceReads;
      const beforeReads = reads(), beforeIncidents = data.incidentReads();
      const before = await sample();
      const durationMs = 6000;
      const measured = await page.evaluate(async ({ hz, durationMs }) => {
        const state = (window as any).__resource;
        const frames: number[] = [], longTasks: number[] = [];
        const observer = new PerformanceObserver(list => {
          for (const task of list.getEntries()) if (longTasks.length < 100) longTasks.push(task.duration);
        });
        observer.observe({ entryTypes: ['longtask'] });
        performance.clearResourceTimings();
        let last = performance.now(), running = true, events = 0;
        const frame = () => {
          const now = performance.now();
          if (frames.length < 2000) frames.push(now - last);
          last = now;
          if (running) requestAnimationFrame(frame);
        };
        requestAnimationFrame(frame);
        const timer = setInterval(() => { state.stream.hint(); events++; }, 1000 / hz);
        await new Promise(resolve => setTimeout(resolve, durationMs));
        clearInterval(timer);
        running = false;
        observer.disconnect();
        return { events, frames, longTasks, activeStreams: state.active };
      }, { hz, durationMs });
      const readsDuringLoad = reads() - beforeReads;
      // REST updates must happen during the burst, rather than only after it ends.
      expect(readsDuringLoad).toBeGreaterThanOrEqual(hz === 1 ? 2 : 4);
      expect(readsDuringLoad).toBeLessThanOrEqual(8);
      expect(measured.activeStreams).toBe(1);
      await paint(page);
      const inputStarted = performance.now();
      if (screen !== 'investigation') {
        const input = page.getByLabel('From (UTC)', { exact: true });
        await input.focus();
        await input.press('ArrowLeft');
        await paint(page);
        await expect(input).toBeFocused();
      }
      const inputPaintMs = performance.now() - inputStarted;
      await page.waitForTimeout(1600);
      const apiRoundTrips = await page.evaluate(() => performance.getEntriesByType('resource')
        .filter(entry => /^\/api\/(services|incidents|geography)/.test(new URL(entry.name).pathname)
          && !entry.name.includes('/stream'))
        .map(entry => ({ path: new URL(entry.name).pathname, durationMs: entry.duration })));
      const after = await sample();
      const ordered = measured.frames.sort((a, b) => a - b);
      records.push({ requestedHintHz: hz, durationMs, events: measured.events,
        snapshotReadsDuringLoad: readsDuringLoad, incidentPageReads: data.incidentReads() - beforeIncidents,
        frameGapP95Ms: ordered[Math.ceil(ordered.length * .95) - 1], maxFrameGapMs: ordered.at(-1),
        longTasksMs: measured.longTasks, inputPaintMs, apiRoundTrips, before, after });
      if (screen === 'service') {
        await expect(page.locator('.actual-dot')).toHaveCount(60);
        await expect(page.locator('[data-window-id]')).toHaveCount(50);
        await expect(page.locator('.episode-card')).toHaveCount(20);
      }
      if (screen === 'investigation') await expect(page.locator('[data-detection-id]')).toHaveCount(20);
      expect(await page.evaluate(() => (window as any).__resource.active)).toBe(1);
    }
    await navigateToLoginAndExpectCleanup(page);
    await writeFile(info.outputPath('load-performance.json'), JSON.stringify({
      mode: 'Controlled REST responses and SSE; not LIVE acceptance', screen,
      scopeCount, incidentTotal: 1000, responseDelayMs: 400, records,
    }, null, 2));
    await cdp.detach();
  });

  test('bounds history, replaces pages, and closes the stream on navigation', async ({ page }) => {
    const data = await setup(page);
    await page.goto(`/services/${SCOPE}`);
    await expect(page.locator('.episode-card')).toHaveCount(20);
    await expect(page.locator('[data-window-id]')).toHaveCount(50);

    const before = data.reads();
    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-10-04T11:00');
    await page.getByLabel('To (UTC, exclusive)').fill('2026-10-05T12:00');
    await page.getByRole('button', { name: 'Apply time range' }).click();
    await expect(page.getByRole('alert')).toContainText('at most 24 hours');
    expect(data.reads()).toBe(before);

    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-10-04T12:00');
    await page.getByRole('button', { name: 'Apply time range' }).click();
    // Dense history is one path; retain every observation without 1,440 DOM markers.
    await expect.poll(async () => (await page.locator('.service-hero .actual-line').getAttribute('d'))?.match(/[ML]/g)?.length ?? 0).toBe(1440);
    expect(data.reads() - before).toBe(15);
    await expect(page.locator('[data-window-id]')).toHaveCount(50);
    await page.locator('.exact-values > summary').click();
    const firstWindow = await page.locator('[data-window-id]').first().getAttribute('data-window-id');
    await page.getByRole('navigation', { name: 'History table pages' }).getByRole('button', { name: 'Next windows' }).click();
    await expect(page.locator('[data-window-id]').first()).not.toHaveAttribute('data-window-id', firstWindow!);
    await expect(page.locator('[data-window-id]')).toHaveCount(50);

    await expect(page.getByRole('button', { name: 'Next incidents' })).toBeEnabled();
    await page.getByRole('button', { name: 'Next incidents' }).click();
    await expect(page.locator('.episode-card').first()).toHaveAttribute('data-episode-id', 'resource-episode-20');
    expect(data.incidentPage()).toBe(1);
    await expect(page.locator('.episode-card')).toHaveCount(20);
    const readsBeforeHint = data.incidentReads();
    await page.evaluate(() => (window as any).__resource.stream.hint());
    await expect.poll(data.incidentReads).toBeGreaterThan(readsBeforeHint);
    await expect(page.getByText('Updating…', { exact: true })).toHaveCount(0);
    await expect(page.locator('.episode-card')).toHaveCount(20);

    await page.getByRole('link', { name: 'Open incident detail' }).first().click();
    await expect(page.locator('[data-detection-id]')).toHaveCount(20);
    expect(await page.evaluate(() => (window as any).__resource.active)).toBe(1);
    await page.getByRole('button', { name: 'Next evidence' }).click();
    await expect(page.locator('[data-detection-id]').first()).toHaveAttribute('data-detection-id', 'resource-detection-20');
    expect(data.evidencePage()).toBe(1);
    await expect(page.locator('[data-detection-id]')).toHaveCount(20);
    await page.getByRole('link', { name: 'Back to service', exact: false }).click();
    await expect(page.locator('.episode-card')).toHaveCount(20);
    await page.evaluate(() => (window as any).__previousStream = (window as any).__resource.stream);
    await page.locator('a.back-link').click();
    await expect(page).toHaveURL(/\/dashboard$/);
    expect(await page.evaluate(() => (window as any).__previousStream.closed)).toBe(true);
    expect(await page.evaluate(() => (window as any).__resource.active)).toBe(1);
    await navigateToLoginAndExpectCleanup(page);
  });

  test('records retained heap and render time as fresh windows replace old ones', async ({ page, context, browserName }, testInfo) => {
    test.skip(browserName !== 'chromium', 'Chromium diagnostics required');
    test.setTimeout(120_000);
    const data = await setup(page);
    const cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable');
    const sample = async () => {
      await cdp.send('HeapProfiler.collectGarbage');
      const result = await cdp.send('Performance.getMetrics');
      const metric = (name: string) => {
        const value = result.metrics.find(item => item.name === name)?.value;
        if (value === undefined) throw new Error(`Missing metric: ${name}`);
        return value;
      };
      return { heapBytes: metric('JSHeapUsedSize'), domNodes: metric('Nodes') };
    };
    await page.goto(`/services/${SCOPE}`);
    await expect(page.locator('.episode-card')).toHaveCount(20);
    // Record the maximum allowed range separately from latest-hour updates.
    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-10-04T12:00');
    await page.getByLabel('To (UTC, exclusive)').fill('2026-10-05T12:00');
    let started = performance.now();
    await page.getByRole('button', { name: 'Apply time range' }).click();
    // Dense history is one path; retain every observation without 1,440 DOM markers.
    await expect.poll(async () => (await page.locator('.service-hero .actual-line').getAttribute('d'))?.match(/[ML]/g)?.length ?? 0).toBe(1440);
    await paint(page);
    const maximumRange = { uiReadyMs: performance.now() - started, ...await sample() };
    const samples: { iteration: number; uiReadyMs: number; heapBytes: number; domNodes: number }[] = [];
    for (let iteration = 0; iteration < 40; iteration++) {
      data.advance();
      started = performance.now();
      await page.getByRole('button', { name: 'Latest hour' }).click();
      await expect(page.locator('.actual-dot')).toHaveCount(60);
      const expectedEnd = new Date(Date.parse('2026-10-05T12:00:00Z') + (iteration + 1) * 60_000)
        .toISOString().slice(0, 16);
      await expect(page.getByLabel('To (UTC, exclusive)')).toHaveValue(expectedEnd);
      await paint(page);
      const uiReadyMs = performance.now() - started;
      await expect(page.locator('[data-window-id]')).toHaveCount(50);
      await expect(page.locator('.episode-card')).toHaveCount(20);
      expect(await page.evaluate(() => (window as any).__resource.active)).toBe(1);
      if (iteration >= 10) samples.push({ iteration, uiReadyMs, ...await sample() });
    }
    const timings = samples.map(item => item.uiReadyMs).sort((a, b) => a - b);
    const percentile = (p: number) => timings[Math.ceil(timings.length * p) - 1];
    const navigation = [];
    for (let iteration = 0; iteration < 10; iteration++) {
      await page.evaluate(() => (window as any).__previousStream = (window as any).__resource.stream);
      await page.locator('a.back-link').click();
      await expect(page).toHaveURL(/\/dashboard$/);
      expect(await page.evaluate(() => (window as any).__previousStream.closed)).toBe(true);
      expect(await page.evaluate(() => (window as any).__resource.active)).toBe(1);
      await paint(page);
      navigation.push({ iteration, ...await sample() });
      // SPA navigation, rather than a full reload that could hide retained screens.
      await page.locator('details.source-inventory > summary').click();
      await page.getByRole('link', { name: SCOPE, exact: true }).click();
      await expect(page.locator('.episode-card')).toHaveCount(20);
    }
    // Allow JIT/lazy-view warm-up, but fail if repeated updates or navigation
    // retain whole screens or grow the observation collection without a bound.
    for (const group of [samples, navigation.slice(2)]) {
      const heaps = group.map(item => item.heapBytes), nodes = group.map(item => item.domNodes);
      expect(Math.max(...heaps) - Math.min(...heaps)).toBeLessThan(5 * 1024 * 1024);
      expect(Math.max(...nodes) - Math.min(...nodes)).toBeLessThanOrEqual(100);
    }
    await navigateToLoginAndExpectCleanup(page);
    const report = {
      mode: 'synthetic routed REST; mock SSE', browser: browserName,
      scopeCount, incidentTotal: 1000, evidenceTotal: 1000,
      maximumRange, medianUiReadyMs: percentile(0.5), p95UiReadyMs: percentile(0.95),
      samples, navigation,
    };
    const output = testInfo.outputPath('resource-baseline.json');
    await writeFile(output, JSON.stringify(report, null, 2));
    await testInfo.attach('resource-baseline', { path: output, contentType: 'application/json' });
    await cdp.detach();
  });
});
