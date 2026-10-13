import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { isAbsolute, resolve, join } from 'node:path';
import { expect, type Browser, type BrowserContext, type Page, type Request } from '@playwright/test';
import type { Reporter, FullConfig, FullResult, Suite, TestCase, TestResult } from '@playwright/test/reporter';
import type { components } from '../../../src/app/core/api/schema';

export interface StackContext {
  projectName: string; composeFiles: string[]; envFile: string; baseURL: string;
  sourceSha: string; outputDir: string; scenarioResultFile: string;
  ownerLabelName: string; ownerLabelValue: string;
  analyst: { username: string; password: string };
  supervisor: { username: string; password: string };
}

// Required connected gates must not silently pass with skipped or filtered cases.
export default class RequiredCases implements Reporter {
  private cases = 0;
  private incomplete = false;
  private startedAtUTC = new Date().toISOString();
  private outcomes: { title: string; status: string; durationMs: number }[] = [];
  onBegin(_config: FullConfig, suite: Suite) { this.cases = suite.allTests().length; }
  onTestEnd(test: TestCase, result: TestResult) {
    if (result.status !== 'passed') this.incomplete = true;
    this.outcomes.push({ title: test.title, status: result.status, durationMs: result.duration });
  }
  async onEnd(result: FullResult) {
    // Discovery is useful for static validation but must not create acceptance evidence.
    if (process.argv.includes('--list')) return;
    const stack = stackContext();
    const passed = result.status === 'passed' && this.cases === 3 && this.outcomes.length === 3 && !this.incomplete;
    writeFileSync(join(stack.outputDir, 'verification.json'), JSON.stringify({
      status: passed ? 'PASSED' : 'FAILED', sourceSha: stack.sourceSha,
      startedAtUTC: this.startedAtUTC, finishedAtUTC: new Date().toISOString(), outcomes: this.outcomes,
      coverage: 'Real OIDC/security, four fresh city scenarios and source trace, proxy reconnect, open-page idle and absolute expiry',
    }, null, 2) + '\n');
    return passed ? undefined : { status: 'failed' as const };
  }
}

export function stackContext(): StackContext {
  const file = process.env.PR77_STACK_CONTEXT;
  if (!file || !isAbsolute(file)) throw new Error('PR77_STACK_CONTEXT must name the private absolute stack context.');
  const value = JSON.parse(readFileSync(file, 'utf8')) as StackContext;
  if (!/^pr77-[a-z0-9-]+$/.test(value.projectName) || !/^[0-9a-f]{40}$/.test(value.sourceSha)
    || !Array.isArray(value.composeFiles) || !value.composeFiles.length || value.composeFiles.some(p => !isAbsolute(p))
    || ![value.envFile, value.outputDir, value.scenarioResultFile].every(p => typeof p === 'string' && isAbsolute(p))
    || value.ownerLabelName !== 'io.telecom.pr77.owner' || !/^[0-9a-f-]{36}$/.test(value.ownerLabelValue)) {
    throw new Error('Invalid isolated PR77 stack context; refuse a default stack.');
  }
  const origin = new URL(value.baseURL);
  if (origin.protocol !== 'http:' || origin.hostname !== 'telecom.test' || !origin.port || origin.pathname !== '/') {
    throw new Error('PR77 baseURL must use the dedicated telecom.test local proxy port.');
  }
  for (const identity of [value.analyst, value.supervisor]) {
    if (!identity?.username || !identity.password) throw new Error('Private temporary identities are required.');
  }
  return value;
}

const root = resolve(__dirname, '../../../../..');
function docker(args: string[], input?: string): string {
  try {
    return execFileSync('docker', args, { cwd: root, input, encoding: 'utf8', timeout: 60_000,
      stdio: ['pipe', 'pipe', 'pipe'] }).trim();
  } catch { throw new Error('Isolated Docker command failed; raw output remains private.'); }
}
function composeArgs(stack: StackContext): string[] {
  return ['compose', '--project-name', stack.projectName, '--env-file', stack.envFile,
    ...stack.composeFiles.flatMap(file => ['--file', file])];
}
export function ownedCompose(stack: StackContext, args: string[], service: 'proxy' | 'postgres', input?: string): string {
  const ids = docker([...composeArgs(stack), 'ps', '--all', '--quiet', service]).split(/\r?\n/).filter(Boolean);
  if (ids.length !== 1 || ids.some(id => !/^[0-9a-f]{12,64}$/.test(id))) throw new Error('Expected one owned container.');
  const inspected = JSON.parse(docker(['inspect', ids[0]]))[0];
  const labels = inspected.Config?.Labels ?? {};
  if (labels['com.docker.compose.project'] !== stack.projectName || labels['com.docker.compose.service'] !== service
    || labels[stack.ownerLabelName] !== stack.ownerLabelValue) throw new Error('Container ownership mismatch; operation refused.');
  return docker([...composeArgs(stack), ...args], input);
}
export function sql(stack: StackContext, database: 'incidents_db' | 'processing_db', query: string): string {
  return ownedCompose(stack, ['exec', '-T', 'postgres', 'sh', '-ec',
    'exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"', '--', database], 'postgres', query);
}

export type Session = components['schemas']['CurrentSession'];
export type Csrf = components['schemas']['CsrfToken'];
export interface Actor { context: BrowserContext; page: Page; csrf: Csrf; me: Session; }
// Chromium resolves telecom.test using its isolated launch rules; Node request contexts do not.
// Use real same-origin browser fetches, keeping all session/cookie material in the browser.
export async function api(actor: Pick<Actor, 'page'>, path: string, options: {
  method?: string; data?: unknown; headers?: Record<string, string>;
} = {}) {
  if (!path.startsWith('/api/') && path !== '/logout') throw new Error('Only local protected API paths are permitted.');
  const result = await actor.page.evaluate(async ({ path, options }) => {
    const response = await fetch(path, { method: options.method ?? 'GET', credentials: 'same-origin', redirect: 'manual',
      signal: AbortSignal.timeout(10_000), headers: { ...(options.data === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...options.headers }, body: options.data === undefined ? undefined : JSON.stringify(options.data) });
    const text = await response.text();
    return { status: response.status, date: response.headers.get('date'), text };
  }, { path, options });
  return { status: () => result.status, headers: () => ({ date: result.date ?? '' }), json: async () => JSON.parse(result.text) };
}
export async function read<T>(actor: Pick<Actor, 'page'>, path: string): Promise<T> {
  const response = await api(actor, path);
  expect(response.status(), `GET ${path}`).toBe(200);
  return response.json() as Promise<T>;
}
export function post(actor: Actor, path: string, data: unknown) {
  return api(actor, path, { method: 'POST', data, headers: { [actor.csrf.headerName]: actor.csrf.token } });
}

// Observe native network lifetime without replacing EventSource or intercepting responses.
export async function observe(page: Page) {
  const activeStreams = new Set<Request>();
  const protectedRequests: { path: string; at: number }[] = [];
  const responses: { path: string; status: number; at: number }[] = [];
  const errors: string[] = [];
  const session = await page.context().newCDPSession(page);
  // Chromium does not emit requestfinished/requestfailed for EventSource requests
  // abandoned by document replacement. Those connections cannot survive their
  // document; discard historical requests only on a full main-frame navigation.
  // CDP keeps same-document Angular routing distinct from this event.
  session.on('Page.frameNavigated', ({ frame }) => {
    if (!frame.parentId) activeStreams.clear();
  });
  await session.send('Page.enable');
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/') && path !== '/api/auth/csrf') protectedRequests.push({ path, at: Date.now() });
    if (path === '/api/incidents/stream') activeStreams.add(request);
  });
  page.on('requestfinished', request => activeStreams.delete(request));
  page.on('requestfailed', request => activeStreams.delete(request));
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/')) responses.push({ path, status: response.status(), at: Date.now() });
  });
  // Record only occurrence, never arbitrary console/error text that could contain protocol material.
  page.on('pageerror', () => errors.push('browser page error'));
  return { activeStreams, protectedRequests, responses, errors };
}

export async function login(browser: Browser, stack: StackContext, role: 'analyst' | 'supervisor') {
  const context = await browser.newContext({ baseURL: stack.baseURL, viewport: { width: 1366, height: 900 } });
  const page = await context.newPage();
  const network = await observe(page);
  try {
    await page.goto('/login');
    expect((await api({ page }, '/api/geography/cities')).status()).toBe(401);
    const oldCsrf = await (await api({ page }, '/api/auth/csrf')).json() as Csrf;
    const before = (await context.cookies()).find(cookie => cookie.name === 'JSESSIONID')?.value;
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(stack[role].username);
      await page.getByLabel('Password', { exact: true }).fill(stack[role].password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/, { timeout: 60_000 });
    } catch { throw new Error(`Real ${role} OIDC login failed; credentials omitted.`); }
    const me = await read<Session>({ page }, '/api/auth/me');
    const csrf = await read<Csrf>({ page }, '/api/auth/csrf');
    expect(me.roles).toContain(role.toUpperCase());
    if (role === 'analyst') expect(me.roles).not.toContain('SUPERVISOR');
    const cookie = (await context.cookies()).find(cookie => cookie.name === 'JSESSIONID');
    expect(Boolean(cookie?.value && cookie.value !== before && cookie.httpOnly && cookie.sameSite === 'Lax')).toBe(true);
    expect(Boolean(csrf.token && csrf.token !== oldCsrf.token)).toBe(true);
    expect(Object.keys(me).sort()).toEqual(['analystId', 'displayName', 'expiresAt', 'roles']);
    const rejected = await api({ page }, '/api/incidents/00000000-0000-0000-0000-000000000000/status', {
      method: 'POST', headers: { [oldCsrf.headerName]: oldCsrf.token }, data: { status: 'INVESTIGATING', version: 0 },
    });
    expect(rejected.status()).toBe(403);
    await expect(page.getByText('SYNTHETIC FIXTURE PREVIEW', { exact: false })).toHaveCount(0);
    // Only return a boolean: storage content and authentication values stay inside the isolated browser.
    expect(await page.evaluate(() => [localStorage, sessionStorage].some(storage => Object.keys(storage).some(key =>
      /access[_-]?token|refresh[_-]?token|id[_-]?token|authorization|csrf/i.test(key)
      || /eyJ[\w-]+\.[\w-]+\.[\w-]+/.test(storage.getItem(key) ?? ''))))).toBe(false);
    await expect.poll(() => network.activeStreams.size).toBeGreaterThan(0);
    return { context, page, csrf, me, network };
  } catch (error) { await context.close(); throw error; }
}

export async function clockPreflight(actor: Actor) {
  const before = Date.now();
  const response = await api(actor, '/api/auth/me');
  expect(response.status()).toBe(200);
  const after = Date.now();
  const server = Date.parse(response.headers()['date']);
  const browser = await actor.page.evaluate(() => Date.now());
  expect(Number.isFinite(server), 'Server Date header required for timing').toBe(true);
  expect(Math.abs(server - (before + after) / 2), 'Host/server clock skew').toBeLessThan(5_000);
  expect(Math.abs(browser - Date.now()), 'Browser/host clock skew').toBeLessThan(2_000);
  const me = await response.json() as Session;
  const remaining = Date.parse(me.expiresAt) - server;
  expect(remaining).toBeGreaterThan(29 * 60_000);
  expect(remaining).toBeLessThanOrEqual(30 * 60_000 + 2_000);
  return { serverDate: new Date(server).toISOString(), absoluteDeadline: me.expiresAt,
    hostServerSkewMs: Math.round((before + after) / 2 - server), browserHostSkewMs: browser - Date.now() };
}

export async function expectExpired(actor: Actor & { network: Awaited<ReturnType<typeof observe>> }) {
  await expect(actor.page.getByRole('heading', { name: 'Your session has expired', exact: true })).toBeVisible();
  await expect(actor.page.getByText('Session connected', { exact: true })).toHaveCount(0);
  await expect.poll(() => actor.network.activeStreams.size, { timeout: 20_000 }).toBe(0);
  // Allow outstanding work to settle, then observe a quiet period before direct test probes.
  await actor.page.waitForTimeout(5_000);
  const quietStart = Date.now();
  await actor.page.waitForTimeout(20_000);
  expect(actor.network.protectedRequests.filter(request => request.at >= quietStart)).toEqual([]);
  for (const path of ['/api/auth/me', '/api/geography/cities', '/api/incidents/stream']) {
    expect((await api(actor, path)).status(), path).toBe(401);
  }
  expect(actor.network.errors).toEqual([]);
  return { expiredUIAt: new Date(quietStart - 5_000).toISOString(), quietStartUTC: new Date(quietStart).toISOString(),
    quietDurationMs: 20_000, activeStreams: actor.network.activeStreams.size, protectedStatuses: [401, 401, 401] };
}
