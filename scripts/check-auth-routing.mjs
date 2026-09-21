import assert from "node:assert/strict";

// Exercise the running NGINX + Spring + Keycloak stack without logging cookies,
// authorization query values, response bodies or credentials. No login needed.
const origin = process.env.AUTH_BASE_URL || "http://telecom.test:8080";
async function request(path, options = {}) {
  const response = await fetch(new URL(path, origin), {
    redirect: "manual", signal: AbortSignal.timeout(10000), ...options,
  });
  const body = await response.text();
  return { response, body };
}
const isShell = body => /<app-root(?:\s|>)/i.test(body);
for (const path of ["/login", "/login?refresh=1", "/dashboard", "/signed-out"]) {
  const { response, body } = await request(path);
  assert.equal(response.status, 200, `${path}: expected the Angular shell`);
  assert.ok(isShell(body), `${path}: missing Angular shell`);
  assert.match(response.headers.get("cache-control") || "", /no-cache/);
  console.log(`PASS ${path}: Angular shell, no redirect`);
}

const auth = await request("/oauth2/authorization/keycloak");
assert.equal(auth.response.status, 302, "OIDC authorization must redirect");
const target = new URL(auth.response.headers.get("location"), origin);
assert.equal(target.origin, new URL(origin).origin);
assert.equal(target.pathname, "/auth/realms/telecom/protocol/openid-connect/auth");
for (const [key, value] of Object.entries({
  client_id: "telecom-web", response_type: "code", code_challenge_method: "S256",
  redirect_uri: `${new URL(origin).origin}/login/oauth2/code/keycloak`,
})) assert.equal(target.searchParams.get(key), value, `OIDC ${key}`);
assert.ok(target.searchParams.get("state"), "OIDC state is required");
assert.ok(target.searchParams.get("code_challenge"), "PKCE challenge is required");
console.log("PASS authorization: Spring creates canonical OIDC + PKCE redirect");

const discovery = await request("/auth/realms/telecom/.well-known/openid-configuration");
assert.equal(discovery.response.status, 200);
assert.equal(JSON.parse(discovery.body).issuer, `${new URL(origin).origin}/auth/realms/telecom`);
const form = await request(target.pathname + target.search);
assert.equal(form.response.status, 200);
assert.match(form.body, /telecom\.css/, "Keycloak must serve the child theme");
assert.match(form.body, /id="kc-form-login"/, "Keycloak must own the credential form");
console.log("PASS /auth/**: Keycloak discovery and branded form");

for (const path of ["/login/oauth2/code/keycloak", "/login/oauth2/code/keycloak?error=access_denied"]) {
  const { response, body } = await request(path);
  assert.equal(response.status, 400, "Spring must reject a callback without a saved authorization request");
  assert.match(response.headers.get("content-type") || "", /application\/json/);
  assert.equal(JSON.parse(body).code, "LOGIN_FAILED");
  assert.equal(isShell(body), false, "A callback must never fall through to Angular");
}
console.log("PASS callback: rejected by Spring, never Angular HTML");

for (const path of ["/api/auth/me", "/api/services"]) {
  const { response, body } = await request(path);
  assert.equal(response.status, 401, `${path}: anonymous API must be 401`);
  assert.match(response.headers.get("content-type") || "", /application\/json/);
  assert.equal(isShell(body), false);
  JSON.parse(body);
  console.log(`PASS ${path}: anonymous JSON 401`);
}
const logout = await request("/logout", { method: "POST" });
assert.equal(logout.response.status, 401, "Spring must reject anonymous logout without CSRF");
assert.match(logout.response.headers.get("content-type") || "", /application\/json/);
assert.equal(JSON.parse(logout.body).code, "UNAUTHENTICATED");
assert.equal(isShell(logout.body), false);
console.log("PASS logout: reaches Spring protection (401 without a session/token)");
