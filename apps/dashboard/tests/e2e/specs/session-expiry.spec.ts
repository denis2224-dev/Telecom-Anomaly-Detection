import {
  test,
  expect,
  type Page,
} from '@playwright/test';

test.skip(!process.env.E2E_REAL_LOGIN, 'Requires the protected local stack and test credentials');

async function login(page: Page): Promise<void> {
  const username = process.env.SESSION_USERNAME
    ?? process.env.G2_USERNAME;

  const password = process.env.SESSION_PASSWORD
    ?? process.env.G2_PASSWORD;

  if (!username || !password) {
    throw new Error(
      'Set SESSION_USERNAME and SESSION_PASSWORD.',
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
      'Real login failed; credential details omitted.',
    );
  }
}

async function storageContainsSecrets(page: Page) {
  return page.evaluate(() => {
    const forbiddenKey =
      /access[_-]?token|refresh[_-]?token|id[_-]?token|authorization|csrf/i;

    const jwt =
      /eyJ[\w-]+\.[\w-]+\.[\w-]+/;

    return [localStorage, sessionStorage].some(storage =>
      Object.keys(storage).some(key =>
        forbiddenKey.test(key)
        || jwt.test(storage.getItem(key) ?? ''),
      ),
    );
  });
}

test('real logout refreshes CSRF and ends protected access', async ({
  page,
  context,
}) => {
  let secretLogged = false;

  page.on('console', message => {
    if (/eyJ[\w-]+\.[\w-]+\.[\w-]+/.test(message.text())) {
      secretLogged = true;
    }
  });

  await login(page);

  await expect(page.locator('.identity')).toContainText(
    '· until',
  );

  expect(await storageContainsSecrets(page)).toBe(false);

  const csrfResponse = await context.request.get(
    '/api/auth/csrf',
  );
  expect(csrfResponse.status()).toBe(200);

  const csrf = await csrfResponse.json();

  // A nonexistent incident prevents business changes even if
  // the application's CSRF protection is unexpectedly broken.
  const rejected = await context.request.post(
    '/api/incidents/00000000-0000-0000-0000-000000000000/status',
    {
      data: { status: 'INVESTIGATING', version: 0 },
      headers: { [csrf.headerName]: 'invalid-test-token' },
    },
  );

  expect(rejected.status()).toBe(403);
  expect((await rejected.json()).code).toBe('CSRF_INVALID');

  const anonymousCsrf = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/auth/csrf'
    && new URL(page.url()).pathname === '/signed-out'
    && response.status() === 200,
  );

  await page.getByRole('button', {
    name: 'Sign out',
    exact: true,
  }).click();

  await expect.poll(
    () => new URL(page.url()).pathname,
  ).toBe('/signed-out');

  await anonymousCsrf;

  expect(
    (await context.request.get(
      '/api/auth/me',
      { maxRedirects: 0 },
    )).status(),
  ).toBe(401);

  expect(await storageContainsSecrets(page)).toBe(false);
  expect(secretLogged).toBe(false);
});

test('frontend stops work after a protected API returns 401', async ({
  page,
}) => {
  await login(page);

  // Login navigation finishes before the overview's initial reads. Inject the
  // rejection only once the workspace is ready, then trigger a protected read.
  await expect(page.locator('details.source-inventory > summary')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Refresh overview' })).toBeEnabled();

  let calls = 0;

  await page.route('**/api/services', async route => {
    calls++;

    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHENTICATED',
        message: 'Session expired',
      }),
    });
  });

  await page.getByRole('button', { name: 'Refresh overview' }).click();

  await expect(page.getByRole('heading', {
    name: 'Your session has expired',
    exact: true,
  })).toBeVisible();

  await page.waitForTimeout(6000);
  expect(calls).toBe(1);
});

test('real protected 401 ends browser work without interception', async ({ page, context }) => {
  await login(page);
  await page.getByRole('link', { name: 'Scenario runner', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Scenario runner', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'VoLTE setup', exact: true })).toBeVisible();
  let calls = 0;
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/')) calls++;
  });
  const rejected = page.waitForResponse(response =>
    new URL(response.url()).pathname.startsWith('/api/') && response.status() === 401);
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const logout = await context.request.post('/logout', {
    headers: { [csrf.headerName]: csrf.token }, maxRedirects: 0,
  }).catch(() => { throw new Error('Session invalidation failed; sensitive details omitted.'); });
  expect(logout.status()).toBe(302);
  // Logout can race a real background read that expires the page first. In
  // either order require a real browser 401, expiry, and no subsequent work.
  await page.evaluate(() => document.querySelector<HTMLAnchorElement>('a[href="/services/VOLTE-MD-CENTRAL"]')?.click());
  await rejected;
  await expect(page.getByRole('heading', { name: 'Your session has expired', exact: true })).toBeVisible();
  await expect(page.getByText('Session connected', { exact: true })).toHaveCount(0);
  const stoppedAt = calls;
  await page.waitForTimeout(6000);
  expect(calls).toBe(stoppedAt);
  expect((await context.request.get('/api/auth/me')).status()).toBe(401);
  expect(await storageContainsSecrets(page)).toBe(false);
});

test('real 15-minute idle expiry', async ({
  page,
  context,
}, info) => {
  test.skip(
    process.env.SESSION_LONG_TESTS !== '1',
    'Enable SESSION_LONG_TESTS for real timeout checks.',
  );
  test.setTimeout(18 * 60_000);

  await login(page);

  await expect(page.getByRole('heading', { name: 'Network overview', exact: true })).toBeVisible();
  const startedAt = Date.now();

  // No analyst input or test API polling. The actual dashboard/SSE refreshes
  // stay running: background transport must not keep an idle analyst signed in.
  // Browser timer acceleration would not prove this real timeout.
  await page.waitForTimeout(15 * 60_000 + 5000);

  await expect(page.getByRole('heading', {
    name: 'Your session has expired',
    exact: true,
  })).toBeVisible();
  await expect(page.getByText('Session connected', { exact: true })).toHaveCount(0);
  expect(Date.now() - startedAt).toBeGreaterThanOrEqual(15 * 60_000);

  expect(
    (await context.request.get(
      '/api/auth/me',
      { maxRedirects: 0 },
    )).status(),
  ).toBe(401);
  expect(await storageContainsSecrets(page)).toBe(false);
  await info.attach('idle-expiry-public-result', {
    body: Buffer.from(JSON.stringify({ startedAtUTC: new Date(startedAt).toISOString(),
      checkedAtUTC: new Date().toISOString(), protectedSessionStatus: 401, expiredHeadingVisible: true })),
    contentType: 'application/json',
  });
});

test('real 30-minute absolute expiry despite activity', async ({
  page,
  context,
}) => {
  test.skip(
    process.env.SESSION_LONG_TESTS !== '1',
    'Enable SESSION_LONG_TESTS for real timeout checks.',
  );
  test.setTimeout(33 * 60_000);

  await login(page);

  const meResponse = await context.request.get(
    '/api/auth/me',
  );
  expect(meResponse.status()).toBe(200);

  const me = await meResponse.json();
  const deadline = Date.parse(me.expiresAt);

  expect(deadline).toBeGreaterThan(
    Date.now() + 29 * 60_000,
  );

  // Activity prevents the 15-minute idle timeout, but must
  // never extend the original absolute deadline.
  while (Date.now() < deadline + 1000) {
    await page.waitForTimeout(
      Math.min(60000, deadline + 1000 - Date.now()),
    );

    if (Date.now() < deadline) {
      // Actual analyst input keeps the browser idle deadline alive as well.
      await page.getByRole('button', { name: 'Refresh overview' }).click();
      const active = await context.request.get('/api/auth/me');
      expect(active.status()).toBe(200);
    }
  }

  await expect(page.getByRole('heading', {
    name: 'Your session has expired',
    exact: true,
  })).toBeVisible();

  expect(
    (await context.request.get(
      '/api/auth/me',
      { maxRedirects: 0 },
    )).status(),
  ).toBe(401);
});
