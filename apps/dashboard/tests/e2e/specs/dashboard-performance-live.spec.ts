import { test, expect } from '@playwright/test';
import { randomBytes, createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { command, admin, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_PERFORMANCE_LIVE !== '1', 'Opt-in real authenticated stack; never fixture acceptance.');

test('measures authenticated geography, window updates and native SSE leases', async ({ page, context, browser }, info) => {
  const username = 'performance-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  let userId = '';
  const report = {
    mode: 'Authenticated LIVE production build; native EventSource; no request interception',
    startedAt: new Date().toISOString(), revision: command('git', ['rev-parse', 'HEAD']),
    trackedPatchHash: createHash('sha256').update(command('git', ['diff', 'HEAD'])).digest('hex'),
    browser: browser.version(), passed: false, scopes: 0, cities: 0,
    requests: [] as { path: string; status: number; durationMs: number; receivedAt: string }[],
    pipelineSamples: [] as { receivedAt: string; windowEnd: string; ageMs: number }[],
    memory: [] as Record<string, number>[], inputPaintMs: [] as number[],
    captures: [] as string[], observation: {} as Record<string, unknown>,
  };
  page.on('requestfinished', request => {
    const path = new URL(request.url()).pathname;
    if (!/^\/api\/(geography|services|incidents)/.test(path) || path.endsWith('/stream')) return;
    const timing = request.timing();
    void request.response().then(response => {
      if (report.requests.length < 500) report.requests.push({ path, status: response?.status() ?? 0, durationMs: timing.responseEnd, receivedAt: new Date().toISOString() });
    });
  });
  page.on('response', response => {
    if (new URL(response.url()).pathname !== '/api/services' || response.status() !== 200) return;
    void response.json().then(rows => {
      const scope = rows.find((row: any) => row.scope.scopeId === 'VOLTE-MD-ORH');
      if (scope?.latestWindow && report.pipelineSamples.length < 50) {
        const receivedAt = new Date().toISOString(), windowEnd = scope.latestWindow.windowEnd;
        report.pipelineSamples.push({ receivedAt, windowEnd, ageMs: Date.parse(receivedAt) - Date.parse(windowEnd) });
      }
    }).catch(() => {});
  });
  try {
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Performance', lastName: 'Verification',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Unexpected temporary user identity.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Performance verification']);
    // Observe the real transport without replacing or intercepting any request.
    await page.addInitScript(() => {
      const state = { active: 0, opened: 0, interrupted: 0, hints: 0, ready: 0 };
      const NativeSource = window.EventSource;
      class ObservedSource extends NativeSource {
        private stopped = false;
        constructor(url: string | URL, options?: EventSourceInit) {
          super(url, options);
          state.active++;
          this.addEventListener('open', () => state.opened++);
          this.addEventListener('error', () => state.interrupted++);
          this.addEventListener('incident-upsert', () => state.hints++);
          this.addEventListener('ready', () => state.ready++);
        }
        override close() {
          if (!this.stopped) { this.stopped = true; state.active--; }
          super.close();
        }
      }
      window.EventSource = ObservedSource;
      (window as any).__performanceStream = state;
    });
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
    } catch { throw new Error('Real identity login failed; credentials omitted.'); }
    await expect(page.locator('.city-marker .node-label small')).toHaveCount(9);
    await expect(page.getByText(/SYNTHETIC FIXTURE PREVIEW/)).toHaveCount(0);
    const catalogue = await (await context.request.get('/api/geography/cities')).json();
    report.cities = catalogue.cities.length;
    report.scopes = (await (await context.request.get('/api/services')).json()).length;
    expect(report.cities).toBe(10);
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await expect(page.getByRole('heading', { name: 'Orhei investigation' })).toBeVisible();
    await page.getByText('Orhei · City coverage and history', { exact: true }).click();
    await expect(page.locator('app-city-evidence [data-city-window]').first()).toBeVisible();
    for (const width of [1366, 768, 390]) {
      await page.setViewportSize({ width, height: 768 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
      const file = `overview-${width}.png`;
      await page.screenshot({ path: info.outputPath(file), fullPage: true });
      report.captures.push(file);
    }
    await page.setViewportSize({ width: 1366, height: 768 });
    await page.getByRole('link', { name: 'Open Orhei VoLTE setup', exact: false }).click();
    await expect(page).toHaveURL(/\/services\/VOLTE-MD-ORH$/);
    await expect(page.locator('.actual-dot')).not.toHaveCount(0);
    const cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable');
    const sample = async () => {
      await cdp.send('HeapProfiler.collectGarbage');
      const { metrics } = await cdp.send('Performance.getMetrics');
      return Object.fromEntries(metrics.filter(metric => ['JSHeapUsedSize', 'Nodes', 'TaskDuration'].includes(metric.name))
        .map(metric => [metric.name, metric.value]));
    };
    await page.waitForTimeout(2000);
    const initialEnd = await page.getByLabel('To (UTC, exclusive)').inputValue();
    const initialStream = await page.evaluate(() => ({ ...(window as any).__performanceStream }));
    const observationStartedAt = new Date().toISOString();
    report.memory.push(await sample());
    await page.evaluate(() => {
      const state = { frames: [] as number[], longTasks: [] as number[], started: performance.now(), last: performance.now(), running: true };
      const observer = new PerformanceObserver(list => {
        for (const task of list.getEntries()) if (state.longTasks.length < 100) state.longTasks.push(task.duration);
      });
      observer.observe({ entryTypes: ['longtask'] });
      const frame = () => {
        const now = performance.now();
        if (state.frames.length < 6000) state.frames.push(now - state.last);
        state.last = now;
        if (state.running) requestAnimationFrame(frame);
      };
      requestAnimationFrame(frame);
      (window as any).__performanceFrames = { state, observer };
    });
    for (let index = 0; index < 6; index++) {
      await page.waitForTimeout(12_000);
      const started = performance.now();
      const input = page.getByLabel('From (UTC)', { exact: true });
      await input.focus();
      await input.press('ArrowLeft');
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      report.inputPaintMs.push(performance.now() - started);
      await expect(input).toBeFocused();
      report.memory.push(await sample());
    }
    const observed = await page.evaluate(() => {
      const { state, observer } = (window as any).__performanceFrames;
      state.running = false; observer.disconnect();
      const frames = [...state.frames].sort((a, b) => a - b);
      return { durationMs: performance.now() - state.started,
        frameGapP95Ms: frames[Math.ceil(frames.length * .95) - 1], maxFrameGapMs: frames.at(-1),
        longTasksMs: state.longTasks, stream: { ...(window as any).__performanceStream } };
    });
    report.observation = { ...observed, startedAt: observationStartedAt, initialStream, initialEnd,
      finalEnd: await page.getByLabel('To (UTC, exclusive)').inputValue() };
    expect(observed.stream.active).toBe(1);
    expect(observed.stream.opened).toBeGreaterThan(initialStream.opened);
    expect(await page.getByLabel('To (UTC, exclusive)').inputValue()).not.toBe(initialEnd);
    for (const width of [1366, 768, 390]) {
      await page.setViewportSize({ width, height: 768 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
      const file = `service-${width}.png`;
      await page.screenshot({ path: info.outputPath(file), fullPage: true });
      report.captures.push(file);
    }
    expect(report.requests.some(row => row.path === '/api/geography/cities/ORH/kpis' && row.status === 200)).toBe(true);
    expect(report.requests.some(row => row.path === '/api/services/VOLTE-MD-ORH/kpis' && row.status === 200)).toBe(true);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect(await page.evaluate(() => (window as any).__performanceStream.active)).toBe(0);
    await cdp.detach();
    report.passed = true;
  } finally {
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Performance verification';`);
    }
    writeFileSync(info.outputPath('live-performance.json'), JSON.stringify(report, null, 2));
  }
});
