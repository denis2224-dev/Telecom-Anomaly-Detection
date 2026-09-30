import {
  test,
  expect,
  type APIRequestContext,
  type Page,
} from '@playwright/test';
import type { components } from '../../../src/app/core/api/schema';

type Run = components['schemas']['ScenarioRun'];
type Window = components['schemas']['ServiceKpiWindow'];
type Incident = components['schemas']['Incident'];
type Detection = components['schemas']['ServiceDetection'];
type ScenarioType = Run['scenarioType'];

const scopes = [
  {
    service: 'VOLTE' as const,
    scopeId: 'VOLTE-MD-CENTRAL',
    fault: 'VOLTE_IMS_OVERLOAD' as const,
  },
  {
    service: 'SMS' as const,
    scopeId: 'SMS-MD-ROUTE-A',
    fault: 'SMS_QUEUE_DELAY' as const,
  },
];

const cases = scopes.flatMap(scope => [
  ...[29092026, 29092027, 29092028].map(seed => ({
    ...scope,
    type: scope.fault as ScenarioType,
    seed,
    faultRun: true,
  })),
  {
    ...scope,
    type: 'NORMAL_CONTROL' as ScenarioType,
    seed: 29092029,
    faultRun: false,
  },
  {
    ...scope,
    type: 'TELEMETRY_GAP' as ScenarioType,
    seed: 29092030,
    faultRun: false,
  },
]);

async function get<T>(
  request: APIRequestContext,
  path: string,
): Promise<T> {
  const response = await request.get(path);
  expect(response.status(), `GET ${path}`).toBe(200);
  return await response.json() as T;
}

async function allItems<T>(
  request: APIRequestContext,
  path: string,
  filters: Record<string, string>,
): Promise<T[]> {
  const items: T[] = [];

  for (let page = 0; page < 100; page++) {
    const query = new URLSearchParams({
      ...filters,
      page: String(page),
      size: '100',
    });

    const result = await get<{
      items: T[];
      total: number;
    }>(request, `${path}?${query}`);

    items.push(...result.items);
    if (items.length >= result.total) return items;
    expect(result.items.length).toBeGreaterThan(0);
  }

  throw new Error('Evidence pagination exceeded 100 pages.');
}

async function login(page: Page): Promise<void> {
  const username = process.env.G2_USERNAME;
  const password = process.env.G2_PASSWORD;

  if (!username || !password) {
    throw new Error(
      'Set G2_USERNAME and G2_PASSWORD for a local supervisor account.',
    );
  }

  try {
    await page.goto('/login');
    await page.getByRole('button', {
      name: 'Continue to sign in',
      exact: true,
    }).click();

    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', {
      exact: true,
    }).fill(password);

    await page.getByRole('button', {
      name: /sign in/i,
    }).click();

    await expect.poll(
      () => new URL(page.url()).pathname,
    ).toBe('/dashboard');
  } catch {
    throw new Error(
      'Real supervisor login failed; credential details omitted.',
    );
  }
}

function shown(value: number | null | undefined): string {
  return value === null || value === undefined
    ? 'Unavailable'
    : String(value);
}

function kpi(window: Window, name: string) {
  return window.kpis.find(value => value.name === name);
}

async function openRange(
  page: Page,
  scopeId: string,
  from: string,
  to: string,
): Promise<void> {
  await page.goto(`/services/${scopeId}`);

  await page.getByLabel('From (UTC)', {
    exact: true,
  }).fill(from.slice(0, 16));

  await page.getByLabel('To (UTC, exclusive)').fill(
    to.slice(0, 16),
  );

  const loaded = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname.endsWith('/kpis')
      && Date.parse(url.searchParams.get('from') ?? '')
        === Date.parse(from);
  });

  await page.getByRole('button', {
    name: 'Apply time range',
  }).click();

  await loaded;

  await expect(page.getByText(
    'Loading service evidence…',
    { exact: true },
  )).toHaveCount(0);
}

for (const scenario of cases) {
  test(
    `${scenario.service} ${scenario.type} seed ${scenario.seed}`,
    async ({ page, context }, info) => {
      await login(page);

      const me = await get<{
        roles: string[];
      }>(context.request, '/api/auth/me');

      expect(
        me.roles.some(
          role => role === 'SUPERVISOR' || role === 'ADMIN',
        ),
      ).toBe(true);

      await page.goto('/scenarios');
      await page.getByLabel('Scenario', {
        exact: true,
      }).selectOption(scenario.type);

      await page.getByLabel('Service scope').selectOption(
        scenario.scopeId,
      );

      await page.getByLabel('Seed').fill(
        String(scenario.seed),
      );

      const acceptedResponse = page.waitForResponse(response =>
        response.request().method() === 'POST'
        && new URL(response.url()).pathname
          === `/api/simulator/scenarios/${scenario.type}`,
      );

      await page.getByRole('button', {
        name: 'Start scenario',
        exact: true,
      }).click();

      const response = await acceptedResponse;

      expect(
        response.status(),
        'The public scenario start API must be integrated.',
      ).toBe(202);

      const run = await response.json() as Run;
      const command = response.request().postDataJSON();

      expect(Object.keys(command).sort()).toEqual([
        'requestId',
        'scopeId',
        'seed',
      ]);

      expect(run.scopeId).toBe(scenario.scopeId);
      expect(run.scenarioType).toBe(scenario.type);

      expect(
        Date.parse(run.scheduledEndAt)
        - Date.parse(run.scheduledStartAt),
      ).toBe(8 * 60_000);

      await expect(
        page.getByText(run.runId, { exact: true }),
      ).toBeVisible();

      const csrf = await get<{
        token: string;
        headerName: string;
      }>(context.request, '/api/auth/csrf');

      let retry;
      try {
        retry = await context.request.post(
          `/api/simulator/scenarios/${scenario.type}`,
          {
            data: command,
            headers: { [csrf.headerName]: csrf.token },
          },
        );
      } catch {
        throw new Error(
          'Exact command retry failed; session details omitted.',
        );
      }

      expect(retry.status()).toBe(202);
      const repeated = await retry.json() as Run;

      expect(repeated.runId).toBe(run.runId);
      expect(repeated.scheduledStartAt).toBe(
        run.scheduledStartAt,
      );
      expect(repeated.scheduledEndAt).toBe(
        run.scheduledEndAt,
      );

      const history = () => allItems<Window>(
        context.request,
        `/api/services/${scenario.scopeId}/kpis`,
        {
          from: run.scheduledStartAt,
          to: run.scheduledEndAt,
        },
      );

      let gapHealth: string | null = null;

      if (scenario.type === 'TELEMETRY_GAP') {
        // Check the UI while data is actually missing, before it resumes.
        await expect.poll(
          async () =>
            (await history()).some(
              window => window.quality === 'MISSING',
            ),
          {
            timeout: 6 * 60_000,
            intervals: [5000],
          },
        ).toBe(true);

        await page.goto('/dashboard');

        const card = page.locator('article.service-row')
          .filter({ hasText: scenario.scopeId });

        await expect(card).toHaveCount(1);

        const health = card.locator('strong')
          .filter({ hasText: 'Current health:' });

        await expect(health).toHaveText(
          /Current health: (UNKNOWN|STALE)/,
        );

        gapHealth = await health.textContent();
      }

      await expect.poll(
        async () => {
          const current = await get<Run>(
            context.request,
            `/api/simulator/runs/${run.runId}`,
          );

          return ['COMPLETED', 'FAILED', 'STOPPED']
            .includes(current.status)
            ? current.status
            : 'PENDING';
        },
        {
          timeout: 11 * 60_000,
          intervals: [5000],
        },
      ).toBe('COMPLETED');

      await expect.poll(
        async () => (await history()).length,
        {
          timeout: 120_000,
          intervals: [5000],
        },
      ).toBe(8);

      const windows = (await history()).sort(
        (a, b) =>
          Date.parse(a.windowStart) - Date.parse(b.windowStart),
      );

      expect(
        new Set(windows.map(window => window.windowId)).size,
      ).toBe(8);

      if (scenario.type === 'TELEMETRY_GAP') {
        expect(
          windows.map(window => window.quality),
        ).toEqual([
          'COMPLETE',
          'COMPLETE',
          'MISSING',
          'MISSING',
          'MISSING',
          'COMPLETE',
          'COMPLETE',
          'COMPLETE',
        ]);

        for (const window of windows.filter(
          value => value.quality === 'MISSING',
        )) {
          expect(window.mlEligible).toBe(false);
          expect(window.featureNames).toEqual([]);
          expect(window.featureValues).toEqual([]);
        }
      } else {
        expect(
          windows.every(
            window => window.quality === 'COMPLETE',
          ),
        ).toBe(true);
      }

      const incidents = async () =>
        (await allItems<Incident>(
          context.request,
          '/api/incidents',
          {
            scopeId: scenario.scopeId,
            service: scenario.service,
          },
        )).filter(incident =>
          Date.parse(incident.firstObservedAt)
            >= Date.parse(run.scheduledStartAt)
          && Date.parse(incident.firstObservedAt)
            < Date.parse(run.scheduledEndAt),
        );

      if (scenario.faultRun) {
        await expect.poll(
          async () =>
            (await incidents()).map(
              incident => incident.technicalState,
            ),
          {
            timeout: 120_000,
            intervals: [5000],
          },
        ).toEqual(['RECOVERED']);
      }

      const episodeItems = await incidents();
      expect(episodeItems).toHaveLength(
        scenario.faultRun ? 1 : 0,
      );

      await openRange(
        page,
        scenario.scopeId,
        run.scheduledStartAt,
        run.scheduledEndAt,
      );

      if (scenario.service === 'VOLTE') {
        await page.getByText(
          /^Show exact values and attempt counts/,
        ).click();
      }

      for (const window of windows) {
        const row = page.locator(
          `[data-window-id="${window.windowId}"]`,
        );

        await expect(row).toBeVisible();
        const cells = row.locator('td');

        if (scenario.service === 'VOLTE') {
          const cssr = kpi(window, 'cssrPct');
          const actual =
            window.quality === 'MISSING'
            || cssr?.denominator === 0
              ? null
              : cssr?.observed;

          await expect(cells.nth(0)).toHaveText(shown(actual));
          await expect(cells.nth(1)).toHaveText(
            shown(cssr?.baseline),
          );
          await expect(cells.nth(2)).toHaveText(
            shown(cssr?.denominator),
          );
          await expect(cells.nth(3)).toHaveText(
            window.quality,
          );
        } else {
          const missing = window.quality === 'MISSING';
          const samples = missing
            ? null
            : kpi(window, 'deliveredMessages')?.observed;

          const p95 =
            missing || samples === null || samples === 0
              ? null
              : kpi(window, 'p95DeliveryMs')?.observed;

          const values = [
            window.quality,
            shown(p95),
            shown(kpi(window, 'p95DeliveryMs')?.baseline),
            shown(missing
              ? null
              : kpi(window, 'queueDepth')?.observed),
            shown(missing
              ? null
              : kpi(window, 'oldestPendingAgeSec')?.observed),
            shown(samples),
          ];

          for (const [index, value] of values.entries()) {
            await expect(cells.nth(index)).toHaveText(value);
          }
        }
      }

      await expect(
        page.locator('.episode-card'),
      ).toHaveCount(scenario.faultRun ? 1 : 0);

      await page.screenshot({
        path: info.outputPath('service-history.png'),
        fullPage: true,
      });

      let detections: Detection[] = [];

      if (scenario.faultRun) {
        const incident = episodeItems[0];

        expect(incident.status).toBe('OPEN');
        expect(incident.technicalState).toBe('RECOVERED');

        detections = await allItems<Detection>(
          context.request,
          `/api/incidents/${incident.id}/detections`,
          {},
        );

        expect(detections.map(value => value.phase)).toEqual([
          'OPEN',
          'UPDATE',
          'UPDATE',
          'UPDATE',
          'RECOVERY',
        ]);

        expect(
          detections.map(value => value.sequence),
        ).toEqual([1, 2, 3, 4, 5]);

        await page.goto(`/incidents/${incident.id}`);

        for (const detection of detections) {
          const window = windows.find(value =>
            Date.parse(value.windowStart)
              === Date.parse(detection.windowStart),
          );

          expect(window).toBeDefined();
          expect(detection.kpis).toEqual(window!.kpis);
          expect(detection.mlStatus).toBe('OK');
          expect(detection.modelVersion).toBeTruthy();
          expect(detection.anomalyRank).not.toBeNull();

          const article = page.locator(
            `[data-detection-id="${detection.detectionId}"]`,
          );

          await expect(article).toBeVisible();
          await expect(article).toContainText(
            `ML status: ${detection.mlStatus}`,
          );

          for (const metric of detection.kpis) {
            const cells = article.locator(
              `[data-kpi="${metric.name}"] td`,
            );

            const expected = [
              shown(metric.observed),
              shown(metric.baseline),
              metric.unit,
              shown(metric.numerator),
              shown(metric.denominator),
            ];

            for (const [index, value] of expected.entries()) {
              await expect(cells.nth(index)).toHaveText(value);
            }
          }

          if (scenario.service === 'VOLTE') {
            await expect(article).toContainText(
              `Estimated extra failed attempts: ${detection.impact.extraFailedAttempts}`,
            );
          } else {
            await expect(article).toContainText(
              `Affected delivered messages: ${detection.impact.affectedDeliveredMessages}`,
            );
            await expect(article).toContainText(
              `Pending messages: ${detection.impact.pendingMessages}`,
            );
          }
        }

        await expect(
          page.getByText(/Workflow state:/).first(),
        ).toContainText('OPEN');

        await page.screenshot({
          path: info.outputPath('incident-evidence.png'),
          fullPage: true,
        });
      }

      // Public evidence only: no cookies, CSRF values, or credentials.
      await info.attach('g2-public-evidence', {
        body: Buffer.from(JSON.stringify({
          testedCommit: process.env.G2_TESTED_COMMIT ?? 'unrecorded',
          scenario: {
            type: scenario.type,
            scopeId: scenario.scopeId,
            seed: scenario.seed,
          },
          run,
          gapHealth,
          windows,
          incidents: episodeItems,
          detections,
        }, null, 2)),
        contentType: 'application/json',
      });
    },
  );
}