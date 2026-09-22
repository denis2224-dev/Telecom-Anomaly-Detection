import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

const baseURL = process.env.E2E_BASE_URL ?? "http://telecom.test:8080";
const username = process.env.E2E_USERNAME;
const password = process.env.E2E_PASSWORD;
const scopeId = process.env.E2E_SCOPE_ID ?? "VOLTE-MD-CENTRAL";
const pollIntervalMs = Number(process.env.E2E_POLL_INTERVAL_MS ?? 5_000);
const runTimeoutMs = Number(process.env.E2E_RUN_TIMEOUT_MS ?? 180_000);

test.describe("G1 protected first slice", () => {
  test("persists one voice incident across retry and re-login", async ({ page, context }) => {
    test.setTimeout(runTimeoutMs + 60_000);
    if (!username || !password) {
      throw new Error("Set E2E_USERNAME and E2E_PASSWORD for the protected G1 flow.");
    }

    await page.goto(`${baseURL}/oauth2/authorization/keycloak`);
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel(/password/i).fill(password);
    await page.getByRole("button", { name: /sign in|log in/i }).click();
    await expect(page).toHaveURL(/\/dashboard(?:\/|$)/, { timeout: 30_000 });

    const request = context.request;
    const csrfResponse = await request.get(`${baseURL}/api/auth/csrf`);
    expect(csrfResponse.ok()).toBeTruthy();
    const csrf = await csrfResponse.json();
    const csrfHeader = String(csrf.headerName);
    const csrfValue = String(csrf.token);
    const requestId = randomUUID();
    const payload = { requestId, seed: 22092026, scopeId };

    const start = await request.post(`${baseURL}/api/simulator/scenarios/VOLTE_IMS_OVERLOAD`, {
      data: payload,
      headers: { [csrfHeader]: csrfValue },
    });
    expect(start.status()).toBe(202);
    const firstRun = await start.json();
    expect(firstRun).toMatchObject({
      status: expect.stringMatching(/^(SCHEDULED|RUNNING|COMPLETED)$/),
      scopeId,
      scenarioType: "VOLTE_IMS_OVERLOAD",
    });
    expect(firstRun.runId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );

    await expect.poll(
      async () => (await request.get(`${baseURL}/api/simulator/runs/${firstRun.runId}`)).json(),
      { timeout: runTimeoutMs, intervals: [pollIntervalMs] },
    ).toMatchObject({ status: "COMPLETED" });

    const firstIncidentsResponse = await request.get(
      `${baseURL}/api/incidents?service=VOLTE&scopeId=${encodeURIComponent(scopeId)}&size=100`,
    );
    expect(firstIncidentsResponse.ok()).toBeTruthy();
    const firstIncidents = await firstIncidentsResponse.json();
    const matching = firstIncidents.items.filter(
      (incident: { service: string; scopeId: string }) =>
        incident.service === "VOLTE" && incident.scopeId === scopeId,
    );
    expect(matching).toHaveLength(1);
    const incidentId = matching[0].id;

    await page.reload();
    await expect(page).toHaveURL(/\/dashboard(?:\/|$)/);
    const retryCsrfResponse = await request.get(`${baseURL}/api/auth/csrf`);
    const retryCsrf = await retryCsrfResponse.json();
    const retry = await request.post(`${baseURL}/api/simulator/scenarios/VOLTE_IMS_OVERLOAD`, {
      data: payload,
      headers: { [retryCsrf.headerName]: retryCsrf.token },
    });
    expect(retry.status()).toBe(202);
    await expect(retry.json()).resolves.toMatchObject({ runId: firstRun.runId });

    const afterRetry = await (
      await request.get(
        `${baseURL}/api/incidents?service=VOLTE&scopeId=${encodeURIComponent(scopeId)}&size=100`,
      )
    ).json();
    const matchingAfterRetry = afterRetry.items.filter(
      (incident: { service: string; scopeId: string }) =>
        incident.service === "VOLTE" && incident.scopeId === scopeId,
    );
    expect(matchingAfterRetry).toHaveLength(1);
    expect(matchingAfterRetry[0].id).toBe(incidentId);
  });
});
