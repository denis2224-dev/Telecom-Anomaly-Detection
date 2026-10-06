import type { Page } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents, voiceWindows, voiceRange } from '../../../src/fixtures/voice';
import type { Incident, ServiceSummary } from '../../../src/app/core/api/telecom-client';

export async function controlledApi(page: Page) {
  const incident: Incident = {
    ...structuredClone(voiceIncidents[0]), assigneeId: 'day17-18',
    status: 'INVESTIGATING', technicalState: 'ONGOING',
    latestDetection: {
      ...structuredClone(voiceIncidents[0].latestDetection),
      phase: 'UPDATE', technicalState: 'ONGOING', mlStatus: 'OK', anomalyRank: 0.8,
    },
  };
  const summary: ServiceSummary = {
    ...structuredClone(services[0]) as ServiceSummary,
    latestWindow: structuredClone(voiceWindows.at(-1)!),
    observedAt: voiceRange.to, openIncidents: 1,
  };
  const state = {
    incident, summary, windows: structuredClone(voiceWindows),
    authStatus: 200, servicesStatus: 200, historyStatus: 200,
    writeStatus: 503, abortWrite: false, writes: 0,
    requests: [] as string[],
  };
  // A deterministic transport. This tests UI recovery, not real SSE or server ingestion.
  await page.addInitScript(() => {
    class ControlledSource extends EventTarget {
      onopen: (() => void) | null = null;
      onerror: (() => void) | null = null;
      closed = false;
      constructor() {
        super();
        (window as any).__sources ??= [];
        (window as any).__sources.push(this);
      }
      close() { this.closed = true; }
    }
    (window as any).EventSource = ControlledSource;
  });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    state.requests.push(`${request.method()} ${path}`);
    const failure = (status: number) => route.fulfill({
      status, json: { code: status === 409 ? 'VERSION_CONFLICT' : 'UNAVAILABLE' },
    });
    if (path === '/api/auth/me') {
      if (state.authStatus !== 200) return failure(state.authStatus);
      return route.fulfill({ json: {
        analystId: 'day17-18', displayName: 'Controlled analyst', roles: ['ANALYST'],
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      } });
    }
    if (path === '/api/auth/csrf') return route.fulfill({ json: {
      token: 'controlled-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
    } });
    if (path === '/api/services') {
      return state.servicesStatus === 200
        ? route.fulfill({ json: [state.summary] }) : failure(state.servicesStatus);
    }
    if (path.endsWith('/kpis')) {
      if (state.historyStatus !== 200) return failure(state.historyStatus);
      const from = Date.parse(url.searchParams.get('from')!);
      const to = Date.parse(url.searchParams.get('to')!);
      const items = state.windows.filter(row =>
        Date.parse(row.windowStart) >= from && Date.parse(row.windowStart) < to);
      return route.fulfill({ json: {
        items, total: items.length, page: 0, size: 100, observedAt: voiceRange.to,
      } });
    }
    if (path === '/api/incidents') return route.fulfill({ json: {
      items: [state.incident], total: 1, page: 0, size: 100,
    } });
    if (path === '/api/analysts') return route.fulfill({ json: [{
      id: 'day17-18', displayName: 'Controlled analyst', enabled: true,
    }] });
    if (path.endsWith('/detections')) return route.fulfill({ json: {
      items: [state.incident.latestDetection], total: 1, page: 0, size: 20,
    } });
    if (path.endsWith('/timeline')) return route.fulfill({ json: {
      items: [], total: 0, page: 0, size: 100,
    } });
    if (request.method() === 'POST') {
      state.writes++;
      if (state.abortWrite) return route.abort('failed');
      // No success mock: these drills must never claim a failed write committed.
      return failure(state.writeStatus);
    }
    if (path === `/api/incidents/${state.incident.id}`)
      return route.fulfill({ json: state.incident });
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } });
  });
  return state;
}

export async function streamEvent(page: Page, event: 'open' | 'error') {
  await page.evaluate(event => {
    const source = (window as any).__sources?.find((item: any) => !item.closed);
    if (!source) throw new Error('No active controlled stream');
    if (event === 'open') source.onopen?.();
    else source.onerror?.();
  }, event);
}
