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
    'Session ends',
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

  await page.locator('details.source-inventory > summary').click();
  await page.locator('a[href^="/services/"]').first().click();

  await expect(page.getByRole('heading', {
    name: 'Your session has expired',
    exact: true,
  })).toBeVisible();

  await page.waitForTimeout(6000);
  expect(calls).toBe(1);
});

test('real 15-minute idle expiry', async ({
  page,
  context,
}) => {
  test.skip(
    process.env.SESSION_LONG_TESTS !== '1',
    'Enable SESSION_LONG_TESTS for real timeout checks.',
  );
  test.setTimeout(18 * 60_000);

  await login(page);

  const link = page.locator(
    'a[href^="/services/"]',
  ).first();

  await expect(link).toBeVisible();

  // No requests or polling during this wait. Browser timer
  // acceleration would not prove the server's idle timeout.
  await page.waitForTimeout(15 * 60_000 + 5000);

  await link.click();

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
