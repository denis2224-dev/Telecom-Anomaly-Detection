# Guide: outage handling, accessibility and regression

This is the implementation guide for Day 17 (Wednesday, 7 October 2026) and Day 18 (Thursday, 8 October 2026). It is tailored to the repository as inspected on 6 October 2026. It gives complete new files and exact edits to existing files, plus checks, commit points and merge conditions.

Only this Markdown file is added to the repository for the guide. Keep the accessibility checklist and live-drill results here; do not create another accessibility Markdown document. Application and test files below are instructions for your implementation, not files already added to your working tree.

## 1. Branch and starting point

The branch **`feature/resilience-accessibility` has already been created and checked out**, starting from `feature/connected-dashboard-contract`. It contains neither “codex” nor a day number. No implementation commit or merge has been made.

Use one branch for both days, with separate commits. Day 18 tests depend on the helper and connection handling from Day 17. Implement Day 17 first, then Day 18, and open one pull request when the whole change is ready.

All repository-relative paths below are relative to `/Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection`. Start there:

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection
git branch --show-current
git status --short
```

The branch output should be `feature/resilience-accessibility`. Review any unrelated changes before staging. Do not use `git add .`, reset the working tree, or delete old guides merely to make the diff smaller.

**Commit point 0 — save this guide after reading it:**

```bash
git add guide.md
git commit -m "docs: add resilience and accessibility implementation guide"
```

If creating this branch again in another checkout, first switch to the same reviewed integration branch and run `git switch -c feature/resilience-accessibility`. Do not run that creation command again in this checkout: the branch exists.

## 2. What the project already has

- Angular standalone components, signals and Playwright browser tests.
- Recoverable session-discovery errors in `session.store.ts` and `login.component.ts`.
- Authentication expiry, CSRF protection and permission handling in the session interceptor.
- Version-conflict messages and draft retention in `incident-actions.component.ts`.
- Native EventSource reconnect, REST refresh, version deduplication and cleanup on session expiry.
- Existing metric explanations for missing evidence, stale evidence, missing baselines and unusable ML rank.
- A skip link, visible focus styles, native modal dialogs, chart keyboard interaction and exact-value tables.

Reuse these behaviors. The work is to prove failure/recovery behavior and close specific gaps, not replace the stores or introduce a new error framework.

**Existing baseline previously verified:** production build passed; 84 unit tests passed; 10 selected login, reconnect and historical-evidence browser tests passed. That baseline does not mean every scenario/security/live test has been run.

## 3. Important path differences from the screenshot

- Put the new browser specifications in `apps/dashboard/tests/e2e/specs/`, because `apps/dashboard/playwright.config.ts` uses that directory. The repository-root `tests/e2e/` is for other scenario/drill material.
- The application imports `apps/dashboard/src/styles.css`, not `styles.scss`. Add styles to the existing CSS file; do not create an unused SCSS file.
- Put the accessibility completion record in section 12 of this guide instead of creating `docs/ux/accessibility-check.md`, following your instruction to have just one Markdown file.

## 4. Day 17 — expected behavior

Keep these meanings distinct:

- **Access failure:** the application cannot verify/sign in the analyst. Offer an access retry or organization sign-in. Do not invent telecom degradation.
- **API or live-update failure:** the dashboard cannot fetch fresh evidence. Explain the limitation and offer a read retry. Retained values are historical, not proof of current health.
- **ML unavailable or timeout:** show rank as unavailable; continue showing recorded measurements, rule severity and recommended checks. An unusable or stale rank is not zero.
- **Telemetry gap:** show unknown/unavailable measurements. Missing data does not prove normal service or recovery.
- **Mutation failure or conflict:** do not show “saved”, change the displayed workflow optimistically, or discard the draft. Reload/review before retrying. An interrupted response can have an uncertain server outcome: Denis must verify what committed.

One existing issue matters: a successful periodic REST refresh can clear a live-stream warning, even though EventSource has not reconnected. The changes below give real stream-open events a separate callback. That makes the visible warning accurate.

### Shared application-connection banner

**Create `apps/dashboard/src/app/shared/status-banner.component.ts`.** Copy the complete code below.

**Why add this?** All three screens need a clear connection state and a recovery action.

**How it improves the app:** analysts can retry reads while understanding that application connectivity and telecom service health are different. The changing message is announced politely; the button sits outside the live-message region to avoid repeatedly announcing controls.

```ts
import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-status-banner',
  template: `
    <section class="notice connection-banner" aria-label="Application connection">
      <div role="status" aria-live="polite" aria-atomic="true">
        <strong>Application connection</strong>
        <p>{{ message() }}</p>
        <p>This describes the dashboard connection. Use the recorded evidence to assess telecom service health.</p>
      </div>
      <button type="button" [disabled]="busy()" (click)="retry.emit()">
        Refresh evidence
      </button>
      <p class="helper">Refreshing evidence retries the API read. Live updates reconnect automatically.</p>
    </section>
  `,
})
export class StatusBannerComponent {
  readonly message = input.required<string>();
  readonly busy = input(false);
  readonly retry = output<void>();
}
```

### Change `apps/dashboard/src/app/core/state/incident-stream.ts`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Expose the real stream-open signal separately from a successful REST read; existing callers remain compatible.

Find:

```ts
connect(refresh: () => void, interrupted: () => void): () => void {
```

Replace with:

```ts
connect(refresh: () => void, interrupted: () => void, connected: () => void = () => {}): () => void {
```

**Edit 2.** Clear the transport warning only when EventSource actually opens; still reload a REST snapshot to catch up.

Find:

```ts
source.onopen = () => {
      if (!active()) return close();
      refresh();
```

Replace with:

```ts
source.onopen = () => {
      if (!active()) return close();
      connected();
      refresh();
```

### Change `apps/dashboard/src/app/features/service-overview/service-overview.component.ts`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Import the shared recovery banner.

Find:

```ts
import { IconComponent }
```

Replace with:

```ts
import { StatusBannerComponent } from '../../shared/status-banner.component';
import { IconComponent }
```

**Edit 2.** Register the standalone banner in this component.

Find:

```ts
imports: [
```

Replace with:

```ts
imports: [StatusBannerComponent,
```

**Edit 3.** Clear the stream warning on the new connected callback.

Find:

```ts
}, () => this.streamError.set('Live connection interrupted. Existing evidence is still shown; reconnecting…'));
```

Replace with:

```ts
}, () => this.streamError.set('Live connection interrupted. Existing evidence is still shown; reconnecting…'),
      () => this.streamError.set(''));
```

**Edit 4.** Remove this line: the periodic REST refresh must not hide an ongoing stream interruption.

Find:

```ts
      if (!this.store.error()) this.streamError.set('');
```

Delete that line.

### Change `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Import the shared recovery banner.

Find:

```ts
import { IconComponent }
```

Replace with:

```ts
import { StatusBannerComponent } from '../../shared/status-banner.component';
import { IconComponent }
```

**Edit 2.** Register the standalone banner in this component.

Find:

```ts
imports: [
```

Replace with:

```ts
imports: [StatusBannerComponent,
```

**Edit 3.** Keep transport interruptions separate from failed evidence reads; those already use streamError.

Find:

```ts
readonly streamError = signal('');
```

Replace with:

```ts
readonly streamError = signal('');
  readonly connectionInterrupted = signal('');
```

**Edit 4.** Show the transport state above the evidence. Refresh retries a read; it does not claim the stream has reconnected.

Find:

```ts
    @if (loading()) {
```

Replace with:

```ts
    @if (connectionInterrupted()) {
      <app-status-banner [message]="connectionInterrupted()" [busy]="loading()" (retry)="load()" />
    }
    @if (loading()) {
```

**Edit 5.** Use the separate transport signal; leave existing read-error retry controls in place.

Find:

```ts
          () => this.streamError.set(
            'Live connection interrupted. Existing evidence is still shown; reconnecting…',
          ),
```

Replace with:

```ts
          () => this.connectionInterrupted.set(
            'Live connection interrupted. Existing evidence is still shown; reconnecting…',
          ),
          () => this.connectionInterrupted.set(''),
```

### Change `apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Import the shared recovery banner.

Find:

```ts
import { IconComponent }
```

Replace with:

```ts
import { StatusBannerComponent } from '../../shared/status-banner.component';
import { IconComponent }
```

**Edit 2.** Register the standalone banner in this component.

Find:

```ts
imports: [
```

Replace with:

```ts
imports: [StatusBannerComponent,
```

**Edit 3.** Keep transport interruptions separate from failed evidence reads; those already use streamError.

Find:

```ts
readonly streamError = signal('');
```

Replace with:

```ts
readonly streamError = signal('');
  readonly connectionInterrupted = signal('');
```

**Edit 4.** Show the transport state above the evidence. Refresh retries a read; it does not claim the stream has reconnected.

Find:

```ts
    @if (loading()) {
```

Replace with:

```ts
    @if (connectionInterrupted()) {
      <app-status-banner [message]="connectionInterrupted()" [busy]="loading()" (retry)="load()" />
    }
    @if (loading()) {
```

**Edit 5.** Track transport reconnect independently of incident reads.

Find:

```ts
        () => this.streamError.set('Live connection interrupted. Reconnecting…'));
```

Replace with:

```ts
        () => this.connectionInterrupted.set('Live connection interrupted. Reconnecting…'),
        () => this.connectionInterrupted.set(''));
```

### Change `apps/dashboard/src/app/features/service-overview/service-overview.component.html`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Give overview connection errors a visible refresh action, without changing recorded telecom health.

Find:

```html
@if (streamError()) { <p role="status">{{ streamError() }}</p> }
```

Replace with:

```html
@if (streamError()) {
  <app-status-banner [message]="streamError()" [busy]="loading()" (retry)="load()" />
}
```

### Keep the existing mutation and metric behavior

Do not replace `incident-actions.component.ts`, `session.store.ts`, `session.interceptor.ts`, `cause-evidence.component.ts`, or `metric-presentation.ts` just to implement this guide. Their existing catch paths, server-version checks, draft retention and unavailable-rank presentation are used by the tests below.

**Why?** Rewriting working security and mutation handling adds risk. Regression checks prove the behavior that already protects the analyst.

If a test fails after later project changes, fix the shared cause of the failure. Never weaken a test by converting unavailable data to zero, permitting a forbidden action, or displaying success before the server confirms it.

### Controlled API and stream helper

**Create `apps/dashboard/tests/e2e/helpers/controlled-api.ts`.** Copy the complete code below.

**Why add this?** Both days need consistent, controllable session, service, incident, ML and mutation responses.

**How it improves the app:** the browser tests can reproduce outages and conflicts reliably. The helper intercepts all `/api/` traffic, so these tests do not submit changes to a real backend. It deliberately does not provide a successful-write path; real persistence remains a separate check.

The long-text tests deliberately include an oversized source token to stress layout. These controlled payloads test rendering; they do not prove backend schema validation or real ingestion.

```ts
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
```

### Dependency failure browser tests

**Create `apps/dashboard/tests/e2e/specs/dependency-failures.spec.ts`.** Copy the complete code below.

**Why add this?** A happy-path screen does not prove that outage messages, retries and failed writes work.

**How it improves the app:** these tests cover failed session discovery, unavailable service/KPI reads, interruption and recovery on all three screens, unsuccessful comments/resolution, version conflicts, ML timeout/unavailability and telemetry gaps. Existing security suites must still cover 401/403/CSRF behavior.

The session-discovery test is a simulated application access failure. It does not pretend to test the real Keycloak provider, which is covered by the live drill below.

```ts
import { test, expect } from '@playwright/test';
import { controlledApi, streamEvent } from '../helpers/controlled-api';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled failures are separate from live drills');

test('identity discovery unavailable: retry access without claiming telecom degradation', async ({ page }) => {
  const state = await controlledApi(page);
  state.authStatus = 503;
  await page.goto('/login');
  await expect(page.getByRole('alert')).toContainText('session could not be verified');
  await expect(page.locator('.service-assurance-card')).toHaveCount(0);
  expect(state.requests.some(path => path === 'GET /api/services')).toBe(false);
  state.authStatus = 200;
  await page.getByRole('button', { name: 'Retry connection' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.locator('.identity')).toContainText('Controlled analyst');
});

test('overview API interruption has a retry and recovers', async ({ page }) => {
  const state = await controlledApi(page);
  state.servicesStatus = 503;
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Services could not be loaded' })).toBeVisible();
  await expect(page.locator('.identity')).toBeVisible();
  state.servicesStatus = 200;
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'City service overview' })).toBeVisible();
});

test('KPI API interruption retries the same selected range', async ({ page }) => {
  const state = await controlledApi(page);
  state.historyStatus = 503;
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  await expect(page.getByRole('heading', { name: 'Evidence unavailable' })).toBeVisible();
  state.historyStatus = 200;
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByLabel('To (UTC, exclusive)', { exact: true }))
    .toHaveValue('2026-09-15T10:10');
  await expect(page.locator('app-kpi-chart svg[role="img"]')).toBeVisible();
});

for (const screen of ['overview', 'service', 'incident'] as const) {
  test(`${screen}: interrupted stream stays visible through REST refresh and clears on reconnect`, async ({ page }) => {
    const state = await controlledApi(page);
    const url = screen === 'overview' ? '/dashboard'
      : screen === 'service' ? `/services/${state.summary.scope.scopeId}`
      : `/incidents/${state.incident.id}`;
    await page.goto(url);
    await expect.poll(() => page.evaluate(() => (window as any).__sources?.length ?? 0))
      .toBeGreaterThan(0);
    await streamEvent(page, 'error');
    const banner = page.locator('app-status-banner');
    await expect(banner).toContainText('Live connection interrupted');
    await banner.getByRole('button', { name: 'Refresh evidence' }).click();
    // A successful REST read is useful but cannot prove the SSE connection recovered.
    await expect(banner).toContainText('Live connection interrupted');
    await streamEvent(page, 'open');
    await expect(banner).toHaveCount(0);
    await expect(page.locator('.identity')).toBeVisible();
  });
}

for (const failure of ['503', 'network', '409'] as const) {
  test(`failed comment (${failure}) keeps draft and never says saved`, async ({ page }) => {
    const state = await controlledApi(page);
    state.abortWrite = failure === 'network';
    state.writeStatus = failure === '409' ? 409 : 503;
    await page.goto(`/incidents/${state.incident.id}`);
    await page.getByRole('button', { name: 'Details & workflow' }).click();
    const workflow = page.locator('app-incident-actions');
    await page.getByLabel('Investigation comment').fill('Keep this unsaved investigation note.');
    await page.getByRole('button', { name: 'Add comment' }).click();
    await expect(workflow.getByRole('alert')).toContainText(
      failure === '409' ? 'incident changed' : 'could not be reached');
    await expect(page.getByLabel('Investigation comment'))
      .toHaveValue('Keep this unsaved investigation note.');
    await expect(workflow).not.toContainText('Comment saved.');
    await expect(page.locator('.toast:not(.toast-error)')).toHaveCount(0);
    expect(state.writes).toBe(1);
    await page.getByRole('button', { name: 'Reload incident' }).click();
    await expect(workflow.getByRole('alert')).toContainText('Incident refreshed');
    await expect(page.getByLabel('Investigation comment'))
      .toHaveValue('Keep this unsaved investigation note.');
  });
}

test('failed resolution retains note and INVESTIGATING workflow', async ({ page }) => {
  const state = await controlledApi(page);
  state.incident.technicalState = 'RECOVERED';
  state.incident.latestDetection.technicalState = 'RECOVERED';
  state.incident.latestDetection.phase = 'RECOVERY';
  await page.goto(`/incidents/${state.incident.id}`);
  await page.getByRole('button', { name: 'Details & workflow' }).click();
  await page.getByLabel('Resolution note', { exact: true }).fill('Recovery checked; resolution not saved.');
  await page.getByRole('button', { name: 'Resolve incident' }).click();
  const workflow = page.locator('app-incident-actions');
  await expect(workflow.getByRole('alert')).toContainText('could not be reached');
  await expect(workflow.locator('[data-state="INVESTIGATING"]')).toBeVisible();
  await expect(page.getByLabel('Resolution note', { exact: true }))
    .toHaveValue('Recovery checked; resolution not saved.');
  await expect(page.locator('.toast:not(.toast-error)')).toHaveCount(0);
});

for (const status of ['UNAVAILABLE', 'TIMEOUT'] as const) {
  test(`ML ${status}: measured evidence and rule severity remain usable`, async ({ page }) => {
    const state = await controlledApi(page);
    state.incident.latestDetection.mlStatus = status;
    // Even a stale supplied rank must not be presented as a usable result.
    state.incident.latestDetection.anomalyRank = 0.95;
    await page.goto(`/incidents/${state.incident.id}`);
    await page.getByText('Cause hypothesis & recommended checks', { exact: true }).click();
    const cause = page.getByRole('region', { name: 'Cause hypothesis' });
    await expect(cause).toContainText('Model anomaly rank: Unavailable');
    await expect(cause.locator('[data-topic="ml-unavailable"]')).toContainText('rule-based severity');
    await expect(page.locator('[data-kpi="cssrPct"] td').first()).not.toHaveText('Unavailable');
    await expect(page.locator('.incident-summary-bar')).toContainText(state.incident.severity);
    await expect(page.locator('.incident-summary-bar')).toContainText('ONGOING');
  });
}

test('telemetry gap is UNKNOWN and null measurements are unavailable', async ({ page }) => {
  const state = await controlledApi(page);
  state.incident.technicalState = 'UNKNOWN';
  Object.assign(state.incident.latestDetection, { phase: 'UNKNOWN', technicalState: 'UNKNOWN' });
  state.incident.latestDetection.kpis = state.incident.latestDetection.kpis.map(kpi => ({
    ...kpi, observed: null, numerator: null, denominator: null,
  }));
  await page.goto(`/incidents/${state.incident.id}`);
  await expect(page.locator('.incident-summary-bar')).toContainText('UNKNOWN');
  await expect(page.locator('[data-detection-id]')).toContainText('does not prove recovery');
  await expect(page.locator('[data-kpi="cssrPct"] td').first()).toHaveText('Unavailable');
});
```

## 5. Check and commit Day 17

From the dashboard directory:

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection/apps/dashboard
npm test
npm run build
npm run test:e2e -- tests/e2e/specs/dependency-failures.spec.ts tests/e2e/specs/login.spec.ts tests/e2e/specs/reconnect.spec.ts tests/e2e/specs/evidence-timeline.spec.ts --workers=2
```

The new dependency-failure file contains **13 tests**. Do not use `E2E_REAL_LOGIN=1` for this controlled run, because the new tests explicitly skip in live mode. If localhost port 4200 is in use, use `E2E_PORT=4300 npm run test:e2e -- ...` with the same arguments. If Chromium is missing, use the installed Playwright CLI to install its Chromium browser before retrying.

**Commit point 1 — only after the Day 17 checks pass:**

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection
git diff --check
git add apps/dashboard/src/app/shared/status-banner.component.ts   apps/dashboard/src/app/core/state/incident-stream.ts   apps/dashboard/src/app/features/service-overview/service-overview.component.ts   apps/dashboard/src/app/features/service-overview/service-overview.component.html   apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts   apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts   apps/dashboard/tests/e2e/helpers/controlled-api.ts   apps/dashboard/tests/e2e/specs/dependency-failures.spec.ts
git diff --cached --stat
git commit -m "fix: show accurate connection recovery and cover dependency failures"
```

Inspect the staged diff, not just its size, before committing. Do not merge yet: Day 18 and live validation remain.

## 6. Day 17 — live drills and handoff

Run drills only on the team's agreed local/test stack. Stanislav coordinates timing; Denis verifies server outcomes. Use a disposable incident and an authorized test account, with no production traffic.

First read the existing runbooks `docs/runbooks/streaming.md`, the authentication/runbook material under `docs/runbooks/`, and `tests/e2e/failures/telemetry-gap.md`. Start the stack using its existing workflow, not a second overlapping backend. The repository supports both a host-run incident service and the Compose `app` profile.

### A. Identity provider unavailable at sign-in

1. Begin signed out. Stop Keycloak on the test stack.
2. Select “Continue to sign in”. Observe the provider/proxy failure. Do not expect Angular to render a retry screen after navigation has left Angular; that destination is owned by the proxy/provider.
3. Confirm no protected API/data is exposed and no telecom-health state is invented.
4. Restore Keycloak, return to `/login`, and retry organization sign-in. Verify the callback returns to the workspace.
5. If the provider/proxy returns a blank or misleading page with no way to return/retry, record it as a blocking gap. Add a tested proxy/provider error-page fix in the same branch before declaring Day 17 complete; the dashboard-only snippets do not solve that external page.

Compose commands from the repository root:

```bash
docker compose stop keycloak
# Perform the signed-out drill above, then restore it:
docker compose start keycloak
```

### B. Backend restart and API interruption

1. Open a service and incident using a valid session. Record the selected range and incident version.
2. Stop the incident API. Confirm an application connection/read error appears; retained evidence is not relabeled as healthy.
3. Retry an evidence read while the API is down. Confirm no false success.
4. Restart the same API instance/service. Confirm reconnect triggers a REST snapshot, the selected range remains appropriate, and rows are not duplicated.
5. A restarted backend may invalidate sessions. If a protected response is 401, the correct result is sign-in again, not continued protected access. Do not promise session survival across every deployment.

For the Compose-managed API only:

```bash
docker compose --profile app stop incident-service
# Perform the drill, then restore the same service:
docker compose --profile app start incident-service
```

If the backend was started on the host, stop/restart that process using its original launch command instead. The Compose commands do not stop a host process. API interruption can also be reproduced in browser DevTools by blocking a specific read request; remove the block before checking recovery.

### C. Failed or uncertain mutation outcome

1. On a disposable assigned incident, enter a comment or resolution note.
2. Exercise a rejected write, a network interruption and a version conflict.
3. Confirm the rejected/uncertain request is never shown as saved and the unsaved text remains while the component stays mounted.
4. Denis checks the database/audit history and current incident version for the interrupted write. A server can commit before a response is lost; the UI must not guess.
5. Reload/review the current incident, then deliberately retry if appropriate. Existing comment retry uses its pending request ID; the backend's idempotency behavior must be verified separately.
6. For 401 or invalid CSRF, verify access is revoked or the session protection is refreshed through the existing access flow. Do not expect in-memory text to survive sign-out/reload; copy a needed draft before leaving. Never persist sensitive evidence in browser storage just to pass a drill.
7. Also reject assignment and investigation-status changes on the live test incident. Confirm assignee/status does not change locally unless the server confirms success, and verify the resulting server version/audit entries.

### D. ML fallback

```bash
docker compose stop ml-service
# Run the agreed eligible anomaly scenario and inspect the next persisted detection.
# Restore the model service after recording the result:
docker compose start ml-service
```

Confirm the persisted detection reports unavailable/timeout model status, rank is unavailable, and rule severity/KPI evidence remains visible. Restore ML and inspect a later eligible record. Do not expect historical detections to be rewritten or every record to become ML-eligible. A controlled `TIMEOUT` response proves UI handling; a real timeout needs a coordinated delayed/unreachable inference drill and backend confirmation.

### E. Telemetry gaps

Use the existing telemetry-gap drill for **both VoLTE and SMS**. Confirm unknown/unavailable evidence, no invented zero measurement, no automatic technical recovery and no automatic workflow resolution. Restore ingestion, wait for a new complete window, then inspect the new persisted state.

**Evidence to record in section 12:** environment, UTC time, incident/scope IDs, version before/after, observed UI message, recovery action/result, and Denis's server confirmation. Omit passwords, cookies, CSRF values and callback query strings. A mocked test is not evidence of a successful real drill.

## 7. Day 18 — keyboard, chart labels and text layout

The main gaps addressed here are the skip-link destination, duplicate chart IDs across chart/table instances, and keyboard access to horizontally scrolling exact-value tables. Keep the native modal dialog: it already handles Escape, focus containment and return to the opener. Test those behaviors instead of adding a custom focus-trap library.

### Change `apps/dashboard/src/app/app.component.html`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Allow the existing skip link to focus the workspace, without adding it to normal Tab order.

Find:

```html
<main id="workspace">
```

Replace with:

```html
<main id="workspace" tabindex="-1">
```

### Change `apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** The chart and exact-value table render separate instances. Give each instance unique IDs so assistive technology resolves the correct labels.

Find:

```ts
export class KpiChartComponent {
```

Replace with:

```ts
export class KpiChartComponent {
  private static nextId = 0;
  readonly chartId = `cssr-${KpiChartComponent.nextId++}`;
```

**Edit 2.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
aria-labelledby="chart-title"
```

Replace with:

```ts
[attr.aria-labelledby]="chartId + '-title'"
```

**Edit 3.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
<h2 id="chart-title">
```

Replace with:

```ts
<h2 [id]="chartId + '-title'">
```

**Edit 4.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
aria-describedby="chart-keyboard-hint"
```

Replace with:

```ts
[attr.aria-describedby]="chartId + '-keyboard-hint'"
```

**Edit 5.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
aria-labelledby="plot-title plot-desc"
```

Replace with:

```ts
[attr.aria-labelledby]="chartId + '-plot-title'" [attr.aria-describedby]="chartId + '-plot-desc'"
```

**Edit 6.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
<linearGradient id="cssr-area"
```

Replace with:

```ts
<linearGradient [id]="chartId + '-area'"
```

**Edit 7.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
<title id="plot-title">
```

Replace with:

```ts
<title [id]="chartId + '-plot-title'">
```

**Edit 8.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
<desc id="plot-desc">
```

Replace with:

```ts
<desc [id]="chartId + '-plot-desc'">
```

**Edit 9.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
<p id="chart-keyboard-hint" class="helper">
```

Replace with:

```ts
<p [id]="chartId + '-keyboard-hint'" class="helper chart-keyboard-hint">
```

**Edit 10.** Update the matching label/description/scroll-region markup within the existing template.

Find:

```ts
<div class="chart-scroll"><table class="kpi-table">
```

Replace with:

```ts
<div class="chart-scroll" tabindex="0" role="region" aria-label="Exact CSSR values and attempt counts"><table class="kpi-table">
```

**Edit 11.** Keep the shaded chart area bound to its own gradient after making gradient IDs unique.

Find:

```ts
<path class="actual-area" [attr.d]="areaPath()" />
```

Replace with:

```ts
<path class="actual-area" [style.fill]="'url(#' + chartId + '-area)'" [attr.d]="areaPath()" />
```

### Change `apps/dashboard/src/styles.css`

Apply these replacements in order. Keep the rest of the file. Each “Find” block should occur once in the current source; do not replace unrelated callbacks or labels.

**Edit 1.** Keep keyboard instructions visible after changing the fixed hint ID to a per-chart ID.

Find:

```css
p:not(#chart-keyboard-hint)
```

Replace with:

```css
p:not(.chart-keyboard-hint)
```

### Append to `apps/dashboard/src/styles.css`

Keep the existing imports and style rules. Append this block at the end.

**Why add this?** Long evidence, identifiers and notes must wrap without widening the page or hiding useful text. Scrollable evidence regions need a visible focus outline, including in forced-color mode.

**How it improves the app:** narrow-screen and keyboard users can read the same evidence. Tables keep their own horizontal scroll instead of clipping columns. Do not add `overflow-x: hidden` to the whole page as a workaround.

```css
/* Day 18: retain native scrolling and allow long diagnostic text to wrap. */
#workspace { min-width: 0; scroll-margin-top: 16px; }
.connection-banner { margin-block: 12px; }
.connection-banner button { margin-block: 8px; }
.evidence-entry, .evidence-entry-header > div,
.incident-summary-bar > div, .workflow-card, .workflow-card .field {
  min-width: 0;
}
.evidence-entry p, .evidence-entry li,
.incident-summary-bar h2, .workflow-card p,
.workflow-card strong, .workflow-card textarea {
  overflow-wrap: anywhere;
  white-space: normal;
}
.evidence-entry p, .evidence-entry li {
  text-overflow: clip;
  -webkit-line-clamp: unset;
}
.workflow-card textarea { max-width: 100%; }
.evidence-table:focus-visible, .chart-scroll:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
@media (forced-colors: active) {
  :focus-visible { outline: 2px solid Highlight; box-shadow: none; }
  .badge { border: 1px solid CanvasText; }
}
```

### Accessibility and realistic regression cases

**Create `apps/dashboard/tests/e2e/specs/accessibility.spec.ts`.** Copy the complete code below.

**Why add this?** Mouse-only testing misses unreachable controls, focus loss and misleading chart gaps.

**How it improves the app:** these seven tests cover a real keyboard route from overview to service chart to incident workflow, focus containment/return, a conflict with draft retention, long evidence at 1366/1024/390/320 pixels, accessible workflow-control names, chart descriptions and exact values, and access-error retry with the keyboard.

`tabTo()` uses actual Tab presses rather than focusing the target programmatically. It proves reachability. The outline check proves an outline exists, but does not prove its contrast, visibility above every overlay or the usability of the entire tab order; those remain manual checks.

```ts
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
  await expect(page.getByRole('heading', { name: 'City service overview' })).toBeVisible();
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
  await expect(page.locator('app-kpi-chart .chart-tooltip')).toContainText('10:00');
  await page.keyboard.press('End');
  await expect(page.locator('app-kpi-chart .chart-tooltip')).toContainText('10:09');
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
    await update.getByText('Source evidence', { exact: true }).click();
    await expect(update).toContainText(explanation);
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
  const chart = page.locator('app-kpi-chart svg[role="img"]');
  await expect(chart).toHaveAccessibleName('Actual and expected voice call setup success');
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
```

## 8. Check and commit Day 18

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection/apps/dashboard
npm test
npm run build
npm run test:e2e -- tests/e2e/specs/dependency-failures.spec.ts tests/e2e/specs/accessibility.spec.ts --workers=2
npm run test:e2e -- --workers=2
```

The two new files contain **20 controlled browser tests** in total. The final command runs the existing default browser collection too. Look at skipped cases: the default configuration deliberately excludes some live/theme/scenario specifications and several specifications require credentials. A green default run does not automatically prove those gates.

**Commit point 2 — after the Day 18 automated checks pass and the manual checklist has no unresolved blocking issue:**

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection
git diff --check
git add apps/dashboard/src/app/app.component.html   apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts   apps/dashboard/src/styles.css   apps/dashboard/tests/e2e/specs/accessibility.spec.ts
git diff --cached --stat
git commit -m "fix: improve keyboard access and cover accessibility regressions"
```

If a test exposes another concrete accessibility problem, fix the relevant component and include that file explicitly in this commit. Do not mark a failed test “skipped” just to complete the day.

## 9. Manual accessibility review

Use an authenticated test workspace with both services and realistic evidence. Check each item at normal size and at browser zoom. Record results in section 12 rather than creating another Markdown file.

### Keyboard and focus

- Starting at the browser's document, Tab exposes the skip link. Enter moves focus to the workspace.
- Tab and Shift+Tab reach navigation, service filters, time inputs, Apply/Refresh controls, chart/table regions and incident links in a logical order.
- Enter/Space activates buttons and disclosure summaries. Native selects work with arrow keys. No mouse is needed to inspect measurements and recommended checks.
- Chart Home/End and left/right arrow interaction announces/updates the selected window. Missing values remain unavailable.
- Open the incident queue and workflow dialogs. Focus stays inside each modal; Escape closes it; focus returns to its opener. Check both dialogs, not just the workflow tested automatically.
- Focus is visibly distinguishable on links, buttons, fields, summaries and horizontal scroll regions; it is not covered by sticky headers, clipping or overlays.
- A failed write/conflict is announced, text stays editable and Reload incident is reachable. Successful writes show success only after confirmation.

### Labels and meaning

- Inputs/selects/textareas have persistent visible labels; a placeholder alone is insufficient.
- Every icon-only control has an accessible name that describes its action.
- Technical state, workflow and severity are written in text. A user must not need red/green color to distinguish them.
- Screen readers can identify page headings, the navigation and workspace, evidence table captions, row/column headings, chart name/description and exact-value alternatives.
- Use VoiceOver on macOS to check an access error, chart, historical detection and workflow dialog. Confirm the spoken words make sense and hidden closed-dialog content is not presented as an active workflow.
- SVG title/description and table labels refer to their own component instance. No duplicate IDs appear when the exact-value panel opens.

### Contrast and reflow

- Measure ordinary text at **at least 4.5:1** and qualifying large text at **at least 3:1**. Check muted helper text, labels, links, badge text, warnings and error messages against their actual rendered backgrounds. These thresholds come from [W3C: Contrast (Minimum)](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html).
- Check relevant non-text control boundaries and indicators against adjacent colors, generally **at least 3:1**, with the criterion's exceptions applied. See [WCAG 2.2: Non-text Contrast](https://www.w3.org/TR/WCAG22/#non-text-contrast).
- Test a narrow laptop at 1024 pixels, 200% zoom, and a viewport equivalent to 320 CSS pixels. Content should reflow without losing information; genuinely two-dimensional tables/maps may use contained scrolling. See [W3C: Reflow](https://www.w3.org/WAI/WCAG21/Understanding/reflow).
- Long cause hypotheses, notes, recommended checks and source identifiers stay readable. Check an unbroken identifier as well as ordinary sentences.
- Actual zero with a valid denominator displays zero; null and zero-denominator rates display unavailable. SMS p95 without completed samples stays unavailable. A missing baseline prevents a comparison without erasing the measured value.
- Contrast checks must include hover/focus/selected/error states and alpha-blended/gradient backgrounds. The browser-test outline assertion is not a contrast audit.
- Generated palette tokens live in `apps/dashboard/src/branding/tokens.css`. If a color fails, edit the source `design/branding.json`, run the existing branding synchronization command, and stage the intended generated outputs. Do not hand-edit generated tokens. Retest the shared login/provider theme when changing branding.

Denis and Sergiu review the final screen meanings, particularly unavailable rank, confidence versus severity, missing evidence, recovery versus resolution, and retained historical data during a connection outage. Record their actual review; do not mark them as having approved in advance.

## 10. Full regression and security gates

Run the fast dashboard checks from section 8. Then run the project's existing broader gates using the documented environment and accounts:

- `npm run test:auth-routing` against the running protected stack.
- `npm run test:branding` and, when reviewing provider/login styling, `npm run test:theme` and `npm run test:auth` with their documented prerequisites.
- `npm run test:g2` for the existing live service-scenario cases. Set its required account/scenario variables using the existing instructions; never paste secrets into the guide or commits.
- `npm run test:g3` for the existing service-explanation cases, against the configured stack. Read `playwright.g3.config.ts`: that script selects `service-explanations.spec.ts`, not every investigation specification.
- Explicit live incident investigation and session-expiry checks, because the default test run skips them without a real-login environment.
- Backend authentication/security tests, plus the existing contract and detector/model tests relevant to ML fallback and telemetry gaps.

The project contains `SessionSecurityTest`, `OidcLoginFailureHandlerTest` and `AuthSecurityTest`. Run the incident service tests with its Maven wrapper:

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection/services/incident-service
./mvnw test
```

With the protected stack already running and the credentials/environment required by the existing specifications configured securely, an explicit investigation/expiry run can use:

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection/apps/dashboard
E2E_REAL_LOGIN=1 npm run test:e2e -- tests/e2e/specs/investigation.spec.ts tests/e2e/specs/session-expiry.spec.ts --workers=1
```

Set `E2E_BASE_URL` and the required session/G3 variables first according to those files. This command intentionally does not invent usernames, passwords or incident IDs. Verify all expected cases actually execute; skipped required cases leave the gate pending.

For the existing contract/model suites, use the environment documented in the repository README and ML-service README:

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection
python -m unittest discover -s tests -v
python -m unittest discover -s services/ml-service/tests -v
./mvnw -pl services/event-generator,services/processor -am test
```

Do not install a second arbitrary toolchain to bypass documented prerequisites. Missing stack/account/toolchain prerequisites should be recorded as pending validation. They are not a successful test result.

## 11. Commit results, push, review and merge

**Commit point 3 — once real drills, manual review and broader regression are complete:** update section 12 with results and references, then commit the evidence in this same guide:

```bash
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection
git add guide.md
git commit -m "docs: record resilience drills and accessibility verification"
git push -u origin feature/resilience-accessibility
```

Open a pull request with:

- **Head:** `feature/resilience-accessibility`.
- **Base:** `feature/connected-dashboard-contract`, the branch this work starts from, if that remains the team's integration branch.
- **Suggested title:** “Improve connection recovery and accessibility regression coverage”.
- **Description:** explain the REST/stream warning distinction, retry controls, keyboard/table/ID fixes, passed checks, real-drill results and remaining limitations.

If `feature/connected-dashboard-contract` has already merged, use the team's agreed target branch and inspect the diff first. Do not open a PR to `main` that accidentally includes unmerged parent-feature work. Do not assume a remote branch exists simply because the local branch exists.

**Merge only when:**

1. The build and relevant unit/default/browser suites pass on the final revision.
2. Both new browser files execute; required scenario/security/live cases execute with no unexplained skipped gates.
3. Keycloak access failure, backend/API interruption, uncertain mutation, ML fallback/timeout and both-service telemetry-gap drills have evidence and recovery outcomes.
4. No failed or uncertain mutation is presented as saved, and server outcomes have been checked.
5. Keyboard, contrast, screen-reader, narrow-screen and long-text reviews have no unresolved blockers.
6. Denis and Sergiu have reviewed screen meaning, and the responsible reviewer approves the diff.
7. Any build-size warning is explicitly reviewed under the repository's budget policy; do not raise a limit just to hide it.

Prefer the team's normal PR merge method. If keeping the individual commits, keep the Day 17 and Day 18 commits separate; if squash-merging, describe both in the final PR summary. Merge after the checks and reviews, not merely because the calendar has reached the end of a day. Do not merge the parent feature automatically as part of this task.

## 12. Completion record — fill in during implementation

The code examples were checked in a temporary copy; the actual application in this checkout has not been modified by writing this guide. Copying the guide's code and completing all gates below is still your implementation work.

### Day 17

- [ ] Shared banner and stream-open callback implemented on overview, service and incident screens.
- [ ] New dependency-failure tests execute and pass.
- [ ] Existing login/reconnect/evidence tests still pass.
- [ ] Real identity/provider failure and recovery checked, including usable provider/proxy failure page.
- [ ] Backend restart/API interruption and session-expiry outcomes checked.
- [ ] Assignment/status/comment/resolution rejection and uncertain server outcomes verified by Denis.
- [ ] Actual ML unavailability and timeout/fallback records checked.
- [ ] VoLTE and SMS telemetry-gap drills checked and ingestion restored.
- [ ] No connection failure mislabeled as telecom degradation or technical recovery.

### Day 18

- [ ] Skip link focuses workspace; chart/table IDs are unique; exact values are keyboard-scrollable.
- [ ] New accessibility tests execute and pass.
- [ ] Full keyboard route, both modal dialogs, focus return and visible focus checked manually.
- [ ] Screen-reader names, summaries, labels and announcements checked.
- [ ] Contrast measured for text and relevant controls/states; failing colors corrected at their source.
- [ ] Long text, narrow laptop, zoom/reflow, null/zero/no-sample states checked.
- [ ] Previous scenario and security cases execute and pass on the final revision.
- [ ] Denis and Sergiu review recorded.

### Per-drill / per-review result template

Copy this entry within this same guide for each check:

- Check/scenario:
- Environment and build/commit:
- UTC timestamp:
- Scope / incident / version before and after:
- Steps and failure injected:
- Visible state and recovery action:
- Server result / reviewer:
- Recovery result:
- Evidence location (sanitized screenshot, log or test report):
- Pass / fail / pending:
- Follow-up required:

### Validation of the examples in this guide

Checked on 6 October 2026 in an isolated temporary copy of the current project with the snippets above applied:

- Production build: passed. The proposed copy reports a 505.37 kB initial bundle against the 500 kB warning threshold; the 1 MB error threshold is not exceeded. Review the warning before merging; the guide does not raise the budget.
- Unit tests: **91 passed across 20 files** in the current temporary copy.
- Combined browser run: **25 passed** — 13 new dependency-failure cases, 7 new accessibility cases, 2 existing reconnect cases and 3 existing historical-evidence cases.
- The guide code was corrected after the first test run, then the final combined browser run passed with no skipped cases.

The historical baseline in section 2 was measured earlier; the current project includes additional metric tests, which explains the different unit-test count. These results validate the included examples, not the future state of your working branch after copying/editing them.

No actual live provider/backend/ML outage was injected while preparing the guide. No team review, full security gate or full default browser collection is claimed complete. Preserve this distinction when recording your final implementation results.

