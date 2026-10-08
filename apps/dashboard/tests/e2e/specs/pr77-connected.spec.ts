import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import topology from '../../../../../contracts/topology/geographic-scopes-v2.json';
import type { components } from '../../../src/app/core/api/schema';
import { probableCause } from '../../../src/app/shared/metric-presentation';
import { api, clockPreflight, expectExpired, login, ownedCompose, post, read, sql, stackContext } from '../helpers/pr77-stack';

type Run = components['schemas']['ScenarioRun'];
type Incident = components['schemas']['Incident'];
type Detection = components['schemas']['ServiceDetection'];
type History = components['schemas']['GeographyKpiPage'];
const stack = stackContext();
const hash = (parts: string[]) => createHash('sha256').update(JSON.stringify(parts)).digest('hex');
const save = (info: TestInfo, name: string, value: unknown) => writeFileSync(info.outputPath(name), JSON.stringify(value, null, 2) + '\n');
const capture = async (page: Page, info: TestInfo, name: string, width: number) => {
  await page.setViewportSize({ width, height: 900 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: info.outputPath(`${name}-${width}.png`), fullPage: true, animations: 'disabled',
    mask: [page.locator('.identity'), page.locator('app-incident-actions')] });
};

// Run every required case even if an earlier gate fails; the reporter fails incomplete suites.
test.describe.configure({ mode: 'default' });

test('integrated production roles, real SSE reconnect and fresh geographic incidents', async ({ browser }, info) => {
  const result = { sourceSha: stack.sourceSha, startedAtUTC: new Date().toISOString(), status: 'FAILED',
    checks: [] as string[], runs: [] as Run[], trace: [] as unknown[], captures: [] as string[] };
  const analyst = await login(browser, stack, 'analyst');
  const supervisor = await login(browser, stack, 'supervisor');
  const find = async (run: Run) => (await read<{ items: Incident[] }>(supervisor,
    `/api/incidents?scopeId=${run.scopeId}&size=100`)).items.filter(item =>
    Date.parse(item.firstObservedAt) >= Date.parse(run.scheduledStartAt)
    && Date.parse(item.firstObservedAt) < Date.parse(run.scheduledEndAt));
  const history = (run: Run) => read<History>(analyst, `/api/geography/cities/${run.scopeId.split('-').at(-1)}/kpis?`
    + new URLSearchParams({ service: run.scopeId.startsWith('VOLTE') ? 'VOLTE' : 'SMS',
      from: run.scheduledStartAt, to: new Date().toISOString(), size: '100' }));
  try {
    result.checks.push('ANALYST/SUPERVISOR real OIDC; prelogin REST401; session/CSRF rotation; stale CSRF403; token-free storage');
    await analyst.page.goto('/scenarios');
    await expect(analyst.page.getByRole('button', { name: 'Start scenario', exact: true })).toHaveCount(0);
    expect((await post(analyst, '/api/simulator/scenarios/NORMAL_CONTROL', {
      requestId: randomUUID(), scopeId: 'VOLTE-MD-BAL', seed: 42,
    })).status()).toBe(403);
    result.checks.push('ANALYST scenario UI and API restrictions');

    await analyst.page.goto('/dashboard');
    await analyst.page.getByLabel('Region', { exact: true }).fill('Chișinău');
    await analyst.page.getByLabel('Service', { exact: true }).selectOption('SMS');
    const from = await analyst.page.getByLabel('From (UTC)', { exact: true }).inputValue();
    const to = await analyst.page.getByLabel('To (UTC, exclusive)', { exact: true }).inputValue();
    await analyst.page.getByRole('button', { name: 'Apply', exact: true }).click();
    await analyst.page.getByLabel('Region', { exact: true }).focus();
    const reads = () => analyst.network.responses.filter(r => r.path === '/api/geography/cities' && r.status === 200).length;
    const streams = () => analyst.network.responses.filter(r => r.path === '/api/incidents/stream' && r.status === 200).length;
    const beforeReads = reads(), beforeStreams = streams();
    ownedCompose(stack, ['stop', 'proxy'], 'proxy');
    try {
      await expect(analyst.page.getByText(/Live connection interrupted/)).toBeVisible();
    } finally { ownedCompose(stack, ['start', 'proxy'], 'proxy'); }
    await expect.poll(reads, { timeout: 60_000 }).toBeGreaterThan(beforeReads);
    await expect.poll(streams, { timeout: 60_000 }).toBeGreaterThan(beforeStreams);
    await expect(analyst.page.getByLabel('Region', { exact: true })).toHaveValue('Chișinău');
    await expect(analyst.page.getByLabel('Region', { exact: true })).toBeFocused();
    await expect(analyst.page.getByLabel('Service', { exact: true })).toHaveValue('SMS');
    await expect(analyst.page.getByLabel('From (UTC)', { exact: true })).toHaveValue(from);
    await expect(analyst.page.getByLabel('To (UTC, exclusive)', { exact: true })).toHaveValue(to);
    result.checks.push('Owned proxy stop/start: native SSE reconnect and authoritative REST refresh preserve city/filter/range/focus');

    for (const [scopeId, type] of [['VOLTE-MD-CHI', 'VOLTE_IMS_OVERLOAD'], ['SMS-MD-CHI', 'SMS_QUEUE_DELAY'],
      ['VOLTE-MD-BAL', 'NORMAL_CONTROL'], ['SMS-MD-CAH', 'TELEMETRY_GAP']] as const) {
      const body = { requestId: randomUUID(), scopeId, seed: 7102026 };
      const response = await post(supervisor, `/api/simulator/scenarios/${type}`, body);
      expect(response.status()).toBe(202);
      const run = await response.json() as Run;
      const retry = await post(supervisor, `/api/simulator/scenarios/${type}`, body);
      expect(retry.status()).toBe(202);
      expect(await retry.json()).toEqual(run);
      expect((await api(analyst, `/api/simulator/runs/${run.runId}`)).status()).toBe(403);
      result.runs.push(run);
    }
    writeFileSync(stack.scenarioResultFile, JSON.stringify(result, null, 2) + '\n');
    const [volte, sms, control, gap] = result.runs;
    await expect.poll(async () => (await find(volte)).some(i => i.technicalState === 'ONGOING'),
      { timeout: 6 * 60_000, intervals: [5_000] }).toBe(true);
    await expect.poll(async () => (await find(sms)).some(i => i.technicalState === 'ONGOING'),
      { timeout: 60_000, intervals: [5_000] }).toBe(true);
    await expect.poll(async () => (await history(gap)).points.some(p => p.coverage.state === 'MISSING' && p.metric.observed === null),
      { timeout: 60_000, intervals: [5_000] }).toBe(true);

    // Fresh API values are compared to the actual map, queue and immutable detail rendering.
    await analyst.page.goto('/dashboard');
    await analyst.page.getByLabel('Region', { exact: true }).fill('Chișinău');
    await expect(analyst.page.locator('.chart-scope').nth(0)).toHaveAttribute('data-scope', volte.scopeId);
    await expect(analyst.page.locator('.chart-scope').nth(1)).toHaveAttribute('data-scope', sms.scopeId);
    await expect(analyst.page.locator('.city-marker').filter({ hasText: 'Chișinău' })).toHaveAttribute('data-state', 'DEGRADED');
    await expect(analyst.page.locator('.city-marker').filter({ hasText: 'Bălți' })).toHaveAttribute('data-state', 'NORMAL');
    await analyst.page.getByRole('button', { name: 'Open incident investigation', exact: true }).click();
    const priority = await read<components['schemas']['GeographyPriorityPage']>(analyst, '/api/operations/priority?cityId=CHI&size=100');
    for (const run of [volte, sms]) {
      const [incident] = await find(run);
      const item = priority.items.find(p => p.incidentId === incident.id);
      expect(item?.scopeId).toBe(run.scopeId);
      expect(item?.cityId).toBe('CHI');
      const card = analyst.page.locator('.queue-item').filter({ has: analyst.page.locator(`a[href="/incidents/${incident.id}"]`) });
      await expect(card).toContainText(`Technical: ${item!.technicalState}`);
      await expect(card).toContainText(item!.priorityBand);
      await expect(card).toContainText(item!.impactUnit ?? 'Impact unavailable');
    }
    await capture(analyst.page, info, 'chi-queue', 1366);
    await capture(analyst.page, info, 'chi-queue', 390);
    await analyst.page.getByRole('button', { name: 'Close incidents', exact: true }).click();
    await analyst.page.getByLabel('Region', { exact: true }).fill('Cahul');
    await analyst.page.getByText('Cahul · City coverage and history', { exact: true }).click();
    const missingHistory = await history(gap);
    const missing = missingHistory.points.filter(p => p.coverage.state === 'MISSING');
    expect(missing.length).toBeGreaterThan(0);
    for (const point of missing) {
      expect(point.metric.observed).toBeNull();
      expect(point.metric.nullReason).toBe('MISSING');
      await expect(analyst.page.locator(`[data-city-window="${point.windowId}"]`)).toContainText('MISSING');
      await expect(analyst.page.locator(`[data-city-window="${point.windowId}"]`)).toContainText('Unavailable');
    }
    await expect(analyst.page.locator('.city-marker').filter({ hasText: 'Cahul' })).toHaveAttribute('data-state', 'UNKNOWN');
    await capture(analyst.page, info, 'cahul-gap', 390);
    result.checks.push('CHI fault map/queue against API, BAL normal, CAH missing history/null/unit presentation; desktop/mobile');

    for (const run of [volte, sms]) {
      const [incident] = await find(run);
      const detail = await read<Incident & { location: components['schemas']['IncidentLocation'] }>(analyst, `/api/incidents/${incident.id}`);
      const detections = await read<components['schemas']['DetectionPage']>(analyst, `/api/incidents/${incident.id}/detections?size=100`);
      expect(detail.location.cityId).toBe('CHI');
      expect(detail.location.measuredScopeId).toBe(run.scopeId);
      expect(detail.location.containmentPath).toContain('CITY-MD-CHI');
      expect(detail.location.topologyVersion).toBe(detections.items[0].topologyVersion);
      expect(detail.location.nullReason).toBeNull();
      await analyst.page.goto(`/incidents/${incident.id}`);
      await expect(analyst.page.locator('.incident-summary-bar')).toContainText('ONGOING');
      const d = detections.items[0];
      const entry = analyst.page.locator(`[data-detection-id="${d.detectionId}"]`);
      await expect(entry).toHaveAttribute('data-phase', d.phase);
      await expect(entry.locator('time')).toHaveAttribute('datetime', d.detectedAt);
      for (const kpi of d.kpis) {
        const cells = entry.locator(`[data-kpi="${kpi.name}"] td`);
        await expect(cells.nth(0)).toHaveText(kpi.observed === null ? 'Unavailable' : String(kpi.observed));
        await expect(cells.nth(1)).toHaveText(kpi.baseline === null ? 'Unavailable' : String(kpi.baseline));
        await expect(cells.nth(2)).toHaveText(kpi.unit);
      }
      await entry.getByText('Source evidence', { exact: true }).click();
      for (const evidence of d.evidence) await expect(entry).toContainText(evidence.summary);
      await entry.getByText('Troubleshooting', { exact: true }).click();
      await expect(entry).toContainText(run.scopeId);
      await expect(entry).toContainText(d.topologyVersion);
      for (const evidence of d.evidence) for (const id of evidence.sourceEventIds) await expect(entry).toContainText(id);
      await entry.getByText('Cause hypothesis & recommended checks', { exact: true }).click();
      await expect(entry.locator('app-cause-evidence')).toContainText(probableCause(d));
      await expect(entry.locator('app-cause-evidence')).toContainText(d.causeConfidence);
      await expect(entry.locator('.evidence-impact')).toContainText('Unique customers: Unavailable');
      if (d.service === 'VOLTE') await expect(entry.locator('.evidence-impact')).toContainText(`Estimated extra failed attempts: ${d.impact.extraFailedAttempts}`);
      else {
        await expect(entry.locator('.evidence-impact')).toContainText(`Affected delivered messages: ${d.impact.affectedDeliveredMessages}`);
        await expect(entry.locator('.evidence-impact')).toContainText(`Pending messages: ${d.impact.pendingMessages}`);
      }
      await analyst.page.getByText('Current impact and cause', { exact: true }).click();
      await expect(analyst.page.locator('.evidence-column')).toContainText('Opening city: CHI');
      await capture(analyst.page, info, run.scopeId, 1366);
      await capture(analyst.page, info, run.scopeId, 390);
    }

    await expect.poll(async () => {
      const runs = await Promise.all(result.runs.map(run => read<Run>(supervisor, `/api/simulator/runs/${run.runId}`)));
      expect(runs.some(run => ['FAILED', 'STOPPED'].includes(run.status))).toBe(false);
      return runs.every(run => run.status === 'COMPLETED');
    }, { timeout: 9 * 60_000, intervals: [10_000] }).toBe(true);
    for (const run of [volte, sms]) {
      await expect.poll(async () => (await find(run))[0]?.technicalState, { timeout: 90_000, intervals: [5_000] }).toBe('RECOVERED');
      const [incident] = await find(run);
      expect(incident.status).toBe('OPEN');
      const detections = (await read<components['schemas']['DetectionPage']>(analyst, `/api/incidents/${incident.id}/detections?size=100`)).items;
      expect(detections.map(d => d.phase)).toEqual(['OPEN', 'UPDATE', 'UPDATE', 'UPDATE', 'RECOVERY']);
      await analyst.page.goto(`/incidents/${incident.id}`);
      await expect(analyst.page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
      await expect(analyst.page.locator('[data-phase="RECOVERY"]')).toBeVisible();
      await capture(analyst.page, info, `${run.scopeId}-recovered`, 390);

      // Never interpolate unvalidated API identifiers into SQL.
      expect(/^[0-9a-f]{64}$/.test(incident.episodeId)).toBe(true);
      const persisted = JSON.parse(sql(stack, 'incidents_db', `SELECT json_agg(payload ORDER BY sequence)
        FROM app.detection_evidence WHERE episode_id='${incident.episodeId}';`)) as Detection[];
      expect(persisted).toEqual(detections);
      const authority = topology.scopes.find(scope => scope.scopeId === run.scopeId)!;
      for (const d of persisted) {
        expect(d.correlationKey).toBe(hash([d.service, run.scopeId,
          d.service === 'VOLTE' ? 'VOLTE_SETUP_DEGRADATION' : 'SMS_DELIVERY_DELAY', 'service-rules-v2']));
        expect(Date.parse(d.firstObservedAt)).toBe(Date.parse(run.scheduledStartAt) + 2 * 60_000);
        expect(d.episodeId).toBe(hash([d.correlationKey, d.firstObservedAt]));
        expect(d.detectionId).toBe(hash([d.episodeId, d.windowStart, d.phase, d.rulesetVersion]));
        expect(d.impact.uniqueSubscribers).toBeNull();
        const ids = [...new Set(d.evidence.flatMap(e => e.sourceEventIds))];
        expect(ids.length).toBeGreaterThan(0);
        expect(ids.every(id => /^[0-9a-f-]{36}$/.test(id))).toBe(true);
        const windowStart = new Date(d.windowStart).toISOString();
        const feature = JSON.parse(sql(stack, 'processing_db', `SELECT payload FROM app.feature_outbox
          WHERE scope_id='${run.scopeId}' AND window_start='${windowStart}';`)) as {
            kpis: Detection['kpis']; sourceEventIds: string[]; topologyVersion: string };
        expect(feature.kpis).toEqual(d.kpis);
        expect(feature.topologyVersion).toBe(d.topologyVersion);
        expect(ids.every(id => feature.sourceEventIds.includes(id))).toBe(true);
        const receipts = JSON.parse(sql(stack, 'processing_db', `SELECT json_agg(t) FROM
          (SELECT event_id, payload_hash, payload, kafka_topic, kafka_partition, kafka_offset
          FROM app.observation_receipt WHERE event_id IN (${ids.map(id => `'${id}'`).join(',')}) ORDER BY event_id)t;`)) as {
            event_id: string; payload_hash: string; payload: { scopeId: string; windowStart: string; sourceId: string; nodeId?: string };
            kafka_topic: string; kafka_partition: number; kafka_offset: number }[];
        expect(receipts).toHaveLength(ids.length);
        for (const receipt of receipts) {
          expect(receipt.payload.scopeId).toBe(run.scopeId);
          expect(Date.parse(receipt.payload.windowStart)).toBe(Date.parse(d.windowStart));
          expect([authority.serviceSourceId, ...authority.nodes.map(node => node.sourceId)]).toContain(receipt.payload.sourceId);
          if (receipt.payload.nodeId) expect(authority.nodes.some(node => node.nodeId === receipt.payload.nodeId
            && node.sourceId === receipt.payload.sourceId)).toBe(true);
          expect(receipt.payload_hash).toMatch(/^[0-9a-f]{64}$/);
          expect(receipt.kafka_topic).toBe('telecom.observations.v2');
          expect(receipt.kafka_offset).toBeGreaterThanOrEqual(0);
          for (const forbidden of ['runId', 'seed', 'scenarioType', 'injectedCause', 'oracle']) expect(Object.hasOwn(receipt.payload, forbidden)).toBe(false);
        }
        result.trace.push({ incidentId: incident.id, detectionId: d.detectionId, scopeId: run.scopeId,
          windowStart: d.windowStart, windowEnd: d.windowEnd, topologyVersion: d.topologyVersion,
          receiptIds: receipts.map(r => r.event_id), receiptHashes: receipts.map(r => r.payload_hash) });
      }
    }
    expect(await find(control)).toEqual([]);
    expect(await find(gap)).toEqual([]);
    const gapHistory = await history(gap);
    for (const offset of [2, 3, 4]) {
      const start = Date.parse(gap.scheduledStartAt) + offset * 60_000;
      const point = gapHistory.points.find(p => Date.parse(p.windowStart) === start)!;
      expect(point?.coverage.state).toBe('MISSING');
      expect(point.metric.observed).toBeNull();
    }
    result.checks.push('Five immutable detections per fault match DB/API, canonical identities and authorized durable source receipts; normal/gap no false incident; recovery leaves analyst workflow OPEN');

    // End a real backend session externally, then let an actual protected browser request discover401.
    await analyst.page.goto('/dashboard');
    const previous401s = analyst.network.responses.filter(r => r.status === 401).length;
    await api(analyst, '/logout', { method: 'POST', headers: { [analyst.csrf.headerName]: analyst.csrf.token } });
    await analyst.page.getByRole('button', { name: 'Refresh overview', exact: true }).click();
    await expect.poll(() => analyst.network.responses.filter(r => r.status === 401).length).toBeGreaterThan(previous401s);
    await expectExpired(analyst);
    await supervisor.page.goto('/dashboard');
    await supervisor.page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(supervisor.page).toHaveURL(/\/signed-out$/);
    for (const path of ['/api/auth/me', '/api/geography/cities', '/api/incidents/stream']) {
      expect((await api(supervisor, path)).status()).toBe(401);
    }
    expect(analyst.network.errors).toEqual([]);
    expect(supervisor.network.errors).toEqual([]);
    result.checks.push('Real protected401 closes stream/background work; real UI logout rejects protected REST/new SSE');
    result.status = 'PASSED';
  } finally {
    await analyst.context.close();
    await supervisor.context.close();
    save(info, 'geographic-security-results.json', result);
    writeFileSync(stack.scenarioResultFile, JSON.stringify(result, null, 2) + '\n');
  }
});

test('open inactive dashboard expires at real fifteen-minute idle deadline', async ({ browser }, info) => {
  test.setTimeout(18 * 60_000);
  const actor = await login(browser, stack, 'analyst');
  const result: Record<string, unknown> = { sourceSha: stack.sourceSha, status: 'FAILED', mode: 'open-page inactivity; native REST/SSE remain enabled' };
  try {
    result.clock = await clockPreflight(actor);
    await actor.page.getByLabel('Region', { exact: true }).click();
    const idleStart = Date.now();
    result.lastTrustedInputUTC = new Date(idleStart).toISOString();
    const beforeBackground = actor.network.protectedRequests.length;
    // No test API polling, navigation or input during inactivity. DOM observation does not reset activity.
    await actor.page.waitForTimeout(15 * 60_000 + 5_000);
    expect(actor.network.protectedRequests.length).toBeGreaterThan(beforeBackground);
    result.expiry = await expectExpired(actor);
    result.elapsedMs = Date.now() - idleStart;
    result.status = 'PASSED';
  } finally { save(info, 'idle-expiry-results.json', result); await actor.context.close(); }
});

test('trusted analyst activity cannot extend real thirty-minute absolute deadline', async ({ browser }, info) => {
  test.setTimeout(33 * 60_000);
  const actor = await login(browser, stack, 'analyst');
  const result: Record<string, unknown> = { sourceSha: stack.sourceSha, status: 'FAILED', mode: 'original backend absolute deadline; trusted key input each minute' };
  try {
    const clock = await clockPreflight(actor);
    result.clock = clock;
    const deadline = Date.parse(clock.absoluteDeadline);
    const activity: string[] = [];
    while (Date.now() < deadline - 5_000) {
      await actor.page.getByLabel('Region', { exact: true }).click();
      await actor.page.keyboard.press('ArrowLeft');
      activity.push(new Date().toISOString());
      const me = await read<components['schemas']['CurrentSession']>(actor, '/api/auth/me');
      expect(me.expiresAt).toBe(clock.absoluteDeadline);
      await expect(actor.page.getByText('Session connected', { exact: true })).toBeVisible();
      await actor.page.waitForTimeout(Math.min(60_000, Math.max(1, deadline - Date.now() - 5_000)));
    }
    await actor.page.waitForTimeout(Math.max(0, deadline - Date.now()) + 5_000);
    result.expiry = await expectExpired(actor);
    result.trustedActivityUTC = activity;
    result.status = 'PASSED';
  } finally { save(info, 'absolute-expiry-results.json', result); await actor.context.close(); }
});
