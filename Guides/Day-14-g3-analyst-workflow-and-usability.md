# Day 14 — G3 analyst workflow and usability

**Friday, 2 October 2026** · Frontend acceptance guide

## What you will finish

Walk through **login → service selection → claim → investigate → comment → technical recovery → resolve → logout** using a real analyst account and a dedicated incident that starts `ONGOING` and later recovers. Ask a teammate unfamiliar with telecom to explain the incident from the screen. Record the actual result and defects in `docs/evidence/2026-10-02-g3-ui.md`.

This is an implementation guide, **not a claim that G3 has passed**. Current `main` already has a comment API caller and a basic comment form; this branch aligns them with the workflow below. The backend accepts `POST /api/incidents/{id}/comments`. The API contract also describes a read endpoint for the analyst audit timeline, but this checkout's backend controller does not implement that read endpoint. A successful comment can be confirmed from the UI and write response; a persistent comment-history display remains a defect to record until the backend read is available.

The slide's `tests/e2e/specs/investigation.spec.ts` maps to **`apps/dashboard/tests/e2e/specs/investigation.spec.ts`** in this repository because that is Playwright's configured test directory. No new package is needed.

## 1. Make a clean Day 14 branch

The current checkout is on `resilient-sse-refresh`, the Day 13 branch. Day 14's workflow changes can start from current `main` independently. Run from the repository root:

```sh
cd /Users/davidnenita/Documents/PT/Telecom-Anomaly-Detection
git status --short
git switch main
git pull --ff-only origin main
git switch -c analyst-workflow-usability
```

If this guide is already an untracked file in your checkout, it will follow you to the new branch. Resolve any unrelated modified or deleted files before switching; in particular, do not carry deletions of older guides into Day 14. If `main` is occupied by another worktree, create the branch from the latest `origin/main` in a free checkout. Do not commit just for creating the branch.

**Why?** Day 14 can be reviewed and merged without bringing in unfinished Day 13 commits. **App benefit:** the acceptance result is tied to one clear branch and commit set.

## 2. Add the existing comment API to the dashboard client

In `apps/dashboard/src/app/core/api/telecom-client.ts`, align the existing comment caller with this method directly after `assignIncident`. Keep the current request helper; it already adds CSRF to mutations and rejects responses from an ended session. Update its existing caller and test when renaming the method.

```ts
commentIncident(
  id: string,
  body: components['schemas']['CommentRequest'],
): Promise<Incident> {
  return this.request<Incident>(
    'POST',
    `/api/incidents/${encodeURIComponent(id)}/comments`,
    body,
  );
}
```

**Why align it?** The backend comment endpoint exists and the frontend already calls it. **App benefit:** analysts record their investigation through the same protected API path as claim and resolve.

### Check and commit

```sh
npm --prefix apps/dashboard run build
git diff --check
git add \
  apps/dashboard/src/app/core/api/telecom-client.ts \
  apps/dashboard/src/app/features/incident-investigation/incident-actions.component.ts \
  apps/dashboard/src/app/features/incident-investigation/incident-actions.component.spec.ts
git diff --cached
git commit -m "feat(dashboard): expose incident comment action"
```

Commit after the build passes.

## 3. Add a comment form with real permissions

Edit `apps/dashboard/src/app/features/incident-investigation/incident-actions.component.ts`. Its existing `SessionStore` already knows the signed-in analyst and roles, and the backend still enforces permissions. Add the following code at the indicated locations.

Reuse the existing `requestId` helper from `core/api/request-id.ts`. It uses `crypto.getRandomValues` when `crypto.randomUUID` is unavailable on the local HTTP origin:

```ts
import { requestId } from '../../core/api/request-id';
```

### Add the form

Inside the template's non-fixture `@else` block, after the current `RESOLVED` section and before the current `@if (message())` block, add:

```html
@if (canComment()) {
  <label for="investigation-comment">Investigation comment</label>
  <textarea
    id="investigation-comment"
    rows="4"
    maxlength="2000"
    [value]="commentText()"
    (input)="editComment($event)"
  ></textarea>
  <button
    type="button"
    [disabled]="busy() || !commentText().trim()"
    (click)="addComment()"
  >
    Add comment
  </button>
  @if (commentNotice()) {
    <p role="status">{{ commentNotice() }}</p>
  }
}
```

### Add state and permission rule

Beside the existing `targetId`, `resolutionNote`, `message` and `busy` fields, add:

```ts
readonly commentText = signal('');
readonly commentNotice = signal('');
private pendingComment?: { id: string; text: string; requestId: string };
```

After the existing `canChangeStatus` computed signal, add:

```ts
readonly canComment = computed(() =>
  this.privileged() || this.canChangeStatus(),
);
```

The backend permits the assignee or a supervisor/admin to comment, including after resolution and for an unassigned incident when the actor is privileged. This rule matches that boundary. An unassigned analyst sees no comment action.

### Add form handlers

After `editNote`, add:

```ts
editComment(event: Event): void {
  const next = (event.target as HTMLTextAreaElement).value;
  if (next !== this.commentText()) this.pendingComment = undefined;
  this.commentText.set(next);
  this.commentNotice.set('');
}

async addComment(): Promise<void> {
  const item = this.incident();
  const text = this.commentText().trim();
  if (dataSource.fixture || this.busy() || !this.canComment()
    || !text || text.length > 2000) return;

  if (this.pendingComment?.id !== item.id || this.pendingComment.text !== text) {
    this.pendingComment = { id: item.id, text, requestId: requestId() };
  }
  this.busy.set(true);
  this.message.set('');
  this.commentNotice.set('');

  try {
    const updated = await this.api.commentIncident(item.id, {
      text,
      version: item.version,
      requestId: this.pendingComment.requestId,
    });
    this.updated.emit(updated);
    this.commentText.set('');
    this.pendingComment = undefined;
    this.commentNotice.set('Comment saved.');
  } catch (error) {
    this.message.set(incidentActionMessage(error));
  } finally {
    this.busy.set(false);
  }
}
```

**Why add this?** The UI currently stops between investigate and resolve. The backend requires comment text, an incident version and a UUID request ID. Keeping the same request ID after a failed response makes a retry idempotent; editing the text starts a new logical comment. The text stays in the form after a conflict so the analyst can reload and review.

**How does it improve the app?** The complete workflow can be performed on screen. Analysts only see actions their role and assignment allow; the server remains the final permission check. A successful response gives a clear confirmation without suggesting that the comment is already visible in an audit timeline.

### Check and commit

```sh
npm --prefix apps/dashboard run test
npm --prefix apps/dashboard run build
git diff --check
git add \
  apps/dashboard/src/app/features/incident-investigation/incident-actions.component.ts \
  apps/dashboard/src/app/features/incident-investigation/incident-actions.component.spec.ts
git diff --cached
git commit -m "feat(dashboard): let assigned analysts comment on incidents"
```

Commit after the unit suite and build pass. If a test reveals a real defect, fix it in this task before committing.

## 4. Explain the four decision labels on the screen

An analyst should be able to describe what the labels mean without reading API documentation. Make two small copy changes.

### Incident summary and refresh

In `apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts`, add a visible refresh control just after the `<h1>Incident investigation</h1>` line. It calls the page's existing `load()` method:

```html
@if (!loading() && !error()) {
  <button type="button" (click)="load()">Refresh incident</button>
}
```

Then replace the unconditional sentence beginning `Recovery describes the service` with:

```html
<p>Severity: <strong>{{ item.severity }}</strong>. This is the rule's service-impact priority, not a probability.</p>
@if (item.technicalState === 'RECOVERED') {
  <p>The service has recovered. The analyst investigation stays open until someone resolves it.</p>
} @else if (item.technicalState === 'UNKNOWN') {
  <p>Technical state is unknown because evidence is incomplete. Do not treat this as recovery.</p>
} @else {
  <p>The issue is still ongoing at the latest observation.</p>
}
```

### Cause and model explanation

In `apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts`, immediately after the existing `ML status` paragraph, add:

```html
<p>
  Model anomaly rank:
  {{ detection().anomalyRank ?? 'Unavailable' }}
</p>
<p>
  Rank is a model signal from 0 to 1, not a failure probability or
  the service severity. An unavailable rank is not zero.
</p>
```

Keep the existing explanation that cause confidence describes supporting evidence, not proof. Do not convert `LOW`, `MEDIUM` or `HIGH` confidence into a numeric percentage.

**Why change this?** Severity, model rank, cause confidence and technical state answer different questions. Incident detail also needs a visible way to pick up a later recovery. **App benefit:** a new analyst can explain priority, model signal, uncertainty and recovery, then refresh the evidence without developer tools.

### Check and commit

```sh
npm --prefix apps/dashboard run test
npm --prefix apps/dashboard run build
git diff --check
git add \
  apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts \
  apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts \
  apps/dashboard/src/app/features/incident-investigation/incident-detail.component.spec.ts \
  apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts \
  apps/dashboard/tests/e2e/specs/service-explanations.spec.ts
git diff --cached
git commit -m "docs(dashboard): explain severity rank confidence and recovery"
```

## 5. Create the real G3 browser test

Create `apps/dashboard/tests/e2e/specs/investigation.spec.ts` with the complete code below. It performs every analyst action **through the UI**. It uses protected read requests only to preflight a dedicated incident and verify the final persisted state. The test never creates a privileged account or writes through a hidden API call.

Prepare an **unassigned, `OPEN`, technically `ONGOING`** incident for the test and put its UUID in `G3_INCIDENT_ID`. Coordinate an authorized scheduled scenario or recovery source so the same incident becomes `RECOVERED` **after the comment step**. Use a real enabled `ANALYST` account that can claim it. The test will resolve that incident, so do not point it at production work or at an incident another analyst is investigating. The test account needs only `ANALYST`; a real supervisor/admin can start the scenario separately through the existing Scenario Runner. If no authorized recovery run is available, record the live G3 path as **not run** rather than substituting an already recovered fixture.

```ts
import { test, expect, type Page } from '@playwright/test';
import type { Incident } from '../../../src/app/core/api/telecom-client';

async function signIn(page: Page, username: string, password: string) {
  try {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect.poll(() => new URL(page.url()).pathname).toBe('/dashboard');
  } catch {
    throw new Error('G3 login failed; credential details omitted.');
  }
}

test('G3 real analyst investigation from login to logout', async ({ page, context }) => {
  test.skip(!process.env.E2E_REAL_LOGIN, 'Requires the protected local stack');
  test.setTimeout(15 * 60_000);
  const username = process.env.SESSION_USERNAME;
  const password = process.env.SESSION_PASSWORD;
  const incidentId = process.env.G3_INCIDENT_ID;
  if (!username || !password || !incidentId || !process.env.E2E_BASE_URL) {
    throw new Error('Set SESSION_USERNAME, SESSION_PASSWORD, G3_INCIDENT_ID and E2E_BASE_URL.');
  }

  const anonymous = await context.request.get('/api/auth/me', { maxRedirects: 0 });
  expect(anonymous.status()).toBe(401);
  await signIn(page, username, password);

  const me = await context.request.get('/api/auth/me');
  expect(me.status()).toBe(200);
  const actor = await me.json();
  expect(actor.roles).toContain('ANALYST');
  expect(actor.roles).not.toContain('SUPERVISOR');
  expect(actor.roles).not.toContain('ADMIN');

  const response = await context.request.get(`/api/incidents/${encodeURIComponent(incidentId)}`);
  expect(response.status()).toBe(200);
  const incident = await response.json() as Incident;
  expect(incident.status).toBe('OPEN');
  expect(incident.assigneeId).toBeNull();
  expect(incident.technicalState).toBe('ONGOING');

  const from = new Date(Date.parse(incident.firstObservedAt) - 60_000);
  const to = new Date(Date.parse(incident.lastObservedAt) + 60_000);
  expect(to.getTime() - from.getTime()).toBeLessThanOrEqual(86_400_000);

  await page.locator(`a[href="/services/${incident.scopeId}"]`).first().click();
  await page.getByLabel('From (UTC)', { exact: true })
    .fill(from.toISOString().slice(0, 16));
  await page.getByLabel('To (UTC, exclusive)')
    .fill(to.toISOString().slice(0, 16));
  await page.getByRole('button', { name: 'Apply time range' }).click();

  const card = page.locator(`[data-episode-id="${incident.episodeId}"]`);
  await expect(card).toBeVisible();
  await expect(card).toContainText('ONGOING');
  await expect(card).toContainText('OPEN');
  await card.getByRole('link', { name: 'Open incident detail' }).click();

  await expect(page.getByRole('heading', { name: 'Incident investigation' }))
    .toBeVisible();
  await expect(page.locator('.detail-panel').first()).toContainText(
    `Severity: ${incident.severity}`,
  );
  await expect(page.getByText('The issue is still ongoing', { exact: false })).toBeVisible();
  await expect(page.getByText('Cause confidence:', { exact: false }).first()).toBeVisible();
  await expect(page.getByText('Model anomaly rank:', { exact: false }).first()).toBeVisible();

  await expect(page.getByRole('button', { name: 'Add comment' })).toHaveCount(0);
  await expect(page.getByLabel('Assign to enabled analyst')).toHaveCount(0);

  const claim = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/assignment`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Claim for myself' }).click();
  expect((await claim).status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Start investigation' }))
    .toBeVisible();

  const investigate = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/status`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Start investigation' }).click();
  expect((await investigate).status()).toBe(200);
  await expect(page.getByText('Workflow: INVESTIGATING')).toBeVisible();

  await page.getByLabel('Investigation comment').fill(
    'Reviewed current service evidence; awaiting technical recovery.',
  );
  const comment = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/comments`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Add comment' }).click();
  expect((await comment).status()).toBe(200);
  await expect(page.locator('app-incident-actions').getByRole('status'))
    .toContainText('Comment saved.');

  await expect(page.getByRole('button', { name: 'Resolve incident' }))
    .toBeDisabled();

  // The authorized scenario now supplies real recovery evidence. The API
  // read only synchronizes the test; the analyst uses the visible refresh.
  await expect.poll(async () => {
    const latest = await context.request.get(
      `/api/incidents/${encodeURIComponent(incidentId)}`,
    );
    return latest.status() === 200
      ? (await latest.json()).technicalState
      : 'UNAVAILABLE';
  }, { timeout: 12 * 60_000, intervals: [15_000] }).toBe('RECOVERED');
  await page.getByRole('button', { name: 'Refresh incident' }).click();
  await expect(page.locator('.detail-panel').first())
    .toContainText('Technical state: RECOVERED');
  await expect(page.getByText('The service has recovered.', { exact: false }))
    .toBeVisible();

  await page.getByLabel('Resolution note').fill(
    'Verified recovered service evidence and completed analyst review.',
  );
  const resolve = page.waitForResponse(r =>
    r.url().endsWith(`/api/incidents/${incidentId}/status`)
    && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Resolve incident' }).click();
  expect((await resolve).status()).toBe(200);
  await expect(page.locator('app-incident-actions'))
    .toContainText('Workflow: RESOLVED');
  await expect(page.locator('app-incident-actions'))
    .toContainText('Technical state: RECOVERED');

  const persisted = await context.request.get(`/api/incidents/${encodeURIComponent(incidentId)}`);
  expect(persisted.status()).toBe(200);
  expect((await persisted.json()).status).toBe('RESOLVED');

  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect.poll(() => new URL(page.url()).pathname).toBe('/signed-out');
  expect((await context.request.get('/api/auth/me', { maxRedirects: 0 })).status())
    .toBe(401);
});
```

**Why add it?** Existing browser tests cover pieces of the journey, but not one real analyst completing all actions in order. **App benefit:** G3 verifies the screen, session and actual backend permissions together. The test checks that technical recovery and analyst resolution are separate states.

### Run and commit

Run against the protected local stack after preparing the dedicated incident. Keep credentials in your shell environment; do not put them in the guide, command history, test source, screenshots or evidence document.

```sh
E2E_REAL_LOGIN=1 npm --prefix apps/dashboard run test:e2e -- tests/e2e/specs/investigation.spec.ts --workers=1
npm --prefix apps/dashboard run build
git diff --check
git add apps/dashboard/tests/e2e/specs/investigation.spec.ts
git diff --cached
git commit -m "test(dashboard): verify real analyst investigation journey"
```

The first command expects `E2E_BASE_URL`, `SESSION_USERNAME`, `SESSION_PASSWORD` and `G3_INCIDENT_ID` to have been set in the shell, plus a real recovery already scheduled for that incident. Playwright will not start its own web server when `E2E_REAL_LOGIN=1`. If the live stack, dedicated incident or authorized recovery is unavailable, record **not run**; a mocked test does not satisfy this G3 check.

## 6. Run the usability walkthrough and create evidence

Ask a teammate unfamiliar with telecom to use the protected screen **without developer tools**. Let them select the service and explain, in their own words:

- What is wrong or recovered, and which screen evidence supports that conclusion?
- What does severity tell them? What does it not tell them?
- What does model rank mean when present, and what does `Unavailable` mean?
- What does cause confidence mean? Is the cause proven?
- Why can the service be `RECOVERED` while the analyst workflow is still `OPEN` or `INVESTIGATING`?
- What action should the assigned analyst take next?

Observe without coaching first. Record their exact points of confusion, the screen and step where each occurred, and whether they could finish. Ask the team to classify any remaining defects as blocking or follow-up work. Do not mark G3 accepted from a developer-only demonstration.

Create `docs/evidence/2026-10-02-g3-ui.md` with the following template, then replace every `not run` and placeholder with actual observations:

```md
# G3 analyst workflow and usability — 2 October 2026

**Status:** NOT RUN
**Branch/commit:** `<branch and commit>`
**Environment:** `<protected local stack and browser>`
**Account:** `<role and non-sensitive display name; no credentials>`
**Dedicated incident:** `<incident UUID, scope, episode ID>`

## Automated checks

- Dashboard unit tests: not run.
- Dashboard production build: not run.
- Real `investigation.spec.ts`: not run.
- Existing real logout/session check: not run.
- Required CI: not run.

## Analyst journey

- Login: not run. Expected: authenticated dashboard and correct analyst role.
- Service selection: not run. Expected: chosen scope and incident appear.
- Claim: not run. Expected: only self-claim is offered to an unassigned analyst.
- Investigate: not run. Expected: workflow becomes `INVESTIGATING`.
- Comment: not run. Expected: write succeeds once and the screen confirms it.
- Recovery: not run. Expected: technical state changes from `ONGOING` to `RECOVERED` after committed evidence; workflow remains separate.
- Resolve: not run. Expected: nonblank note and a `RESOLVED` workflow state.
- Logout: not run. Expected: signed-out screen and protected API returns 401.

## Label comprehension, without coaching

- Severity — teammate's explanation: not run.
- Model rank or unavailable rank — teammate's explanation: not run.
- Cause confidence — teammate's explanation: not run.
- Technical state versus workflow state — teammate's explanation: not run.
- Could the teammate explain the incident from the screen? not run.

## Defects and handoff

- Audit timeline read endpoint: contract exists; backend implementation not confirmed in this checkout. Persistent comment history is not shown in the dashboard. Record whether the team treats this as blocking for G3.
- Other defects: `<screen, reproduction steps, severity, owner, issue/PR link>` or none.
- Team acceptance: pending. Record names, date and decision after review.
- Remaining follow-up: `<owner and next action>` or none.

Do not paste passwords, tokens, cookies, CSRF values or full login URLs here.
```

**Why add it?** The evidence file separates what was run from what was merely planned. **App benefit:** the team can make a clear acceptance decision and assign each remaining defect.

### Commit the real results

```sh
git diff --check
git add \
  docs/evidence/2026-10-02-g3-ui.md \
  Guides/Day-14-g3-analyst-workflow-and-usability.md
git diff --cached
git commit -m "docs(evidence): record G3 analyst workflow and usability"
```

Commit the evidence only after recording actual results. If the live test or walkthrough could not run, keep `NOT RUN` and the reason; do not replace it with a passing claim.

## 7. Push, review and merge

The intended commits are:

1. Comment API caller.
2. Permission-aware comment form.
3. Screen explanations for severity, rank, confidence and recovery.
4. Real analyst browser journey.
5. G3 evidence and this guide.

Push the branch:

```sh
git status --short
git push -u origin analyst-workflow-usability
```

Open a PR against `main` titled **Day 14: G3 analyst workflow and usability**. Include the real test result, the usability participant's observations, and all remaining defects. Request review from the frontend owner and the teammate responsible for backend comment/audit behavior. Keep the PR in draft while the real run or acceptance decision is pending.

**Merge when** the real end-to-end journey passes, required CI is green, the team accepts the analyst experience, and blocking defects are fixed. If the missing audit timeline is judged blocking, implement and verify it in the appropriate backend/frontend PR before accepting G3. Follow the team's approved merge method, then update your local `main`:

```sh
git switch main
git pull --ff-only origin main
```

## Done checklist

- [ ] A real analyst signs in and reaches the intended service and incident.
- [ ] The analyst can claim, investigate, comment and resolve through visible controls.
- [ ] The comment write is idempotent on retry and preserves entered text after failure.
- [ ] Technical recovery does not automatically resolve analyst work.
- [ ] Severity, model rank, cause confidence and technical state are explained accurately.
- [ ] Logout ends protected access.
- [ ] A teammate unfamiliar with telecom can explain the incident from the screen.
- [ ] Actual checks, remaining defects and team decision are recorded.
- [ ] The PR is reviewed and merged only after G3 acceptance.
