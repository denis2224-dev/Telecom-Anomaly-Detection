import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, mkdir, copyFile, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

test("generation is deterministic; check rejects stale or missing output", async () => {
  const temp = await mkdtemp(join(tmpdir(), "telecom-branding-test-"));
  try {
    await mkdir(join(temp, "scripts"));
    await mkdir(join(temp, "design"));
    await copyFile(new URL("./sync-branding.mjs", import.meta.url), join(temp, "scripts/sync-branding.mjs"));
    await copyFile(new URL("../design/branding.json", import.meta.url), join(temp, "design/branding.json"));
    await copyFile(new URL("../design/product.json", import.meta.url), join(temp, "design/product.json"));
    await mkdir(join(temp, "apps/dashboard/src"), {recursive:true});
    await writeFile(join(temp, "apps/dashboard/src/index.html"), "<title>Trace Link</title>");
    const script = join(temp, "scripts/sync-branding.mjs");
    const check = () => spawnSync(process.execPath, [script, "--check"], { encoding: "utf8" });
    assert.equal(check().status, 1, "missing outputs must fail");
    execFileSync(process.execPath, [script]);
    assert.equal(check().status, 0);
    assert.match(await readFile(join(temp, "docs/assets/tracelink-logo.svg"), "utf8"), /Trace Link/);
    assert.doesNotMatch(await readFile(join(temp, "docs/assets/tracelink-logo.svg"), "utf8"), /TraceLink/);
    const css = join(temp, "infra/keycloak/themes/telecom/login/resources/css/tokens.css");
    const original = await readFile(css, "utf8");
    execFileSync(process.execPath, [script]);
    assert.equal(await readFile(css, "utf8"), original);
    await writeFile(css, "/* stale */\n");
    assert.equal(check().status, 1);
    assert.equal(await readFile(css, "utf8"), "/* stale */\n", "check must not write");
    execFileSync(process.execPath, [script]);
    assert.equal(check().status, 0);
    const tokensPath = join(temp, "design/branding.json");
    const tokens = JSON.parse(await readFile(tokensPath, "utf8"));
    tokens.accent = "#123456";
    await writeFile(tokensPath, JSON.stringify(tokens));
    const stale = check();
    assert.equal(stale.status, 1);
    assert.match(stale.stderr, /tokens\.css/);
  } finally {
    await rm(temp, { recursive: true, force: true });
  }
});
