import { execFileSync, spawn } from "node:child_process";
import { mkdtemp, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

// Disposable Keycloak only. No .env, bootstrap admin, real accounts, or volumes.
const root = fileURLToPath(new URL("../", import.meta.url));
const temp = await mkdtemp(join(tmpdir(), "telecom-theme-test-"));
let container;
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
try {
  const realm = JSON.parse(await readFile(join(root, "infra/keycloak/telecom-realm.json"), "utf8"));
  // Recovery is opt-in on the real realm. Exercise its inherited template here
  // without enabling registration/recovery or sending email in the real stack.
  realm.resetPasswordAllowed = true;
  await writeFile(join(temp, "telecom-realm.json"), JSON.stringify(realm));
  container = docker("run", "-d", "--rm", "-p", "127.0.0.1::8080",
    "-v", `${root}infra/keycloak/themes/telecom:/opt/keycloak/themes/telecom:ro`,
    "-v", `${temp}/telecom-realm.json:/opt/keycloak/data/import/telecom-realm.json:ro`,
    "quay.io/keycloak/keycloak:26.7.4", "start-dev", "--import-realm",
    "--spi-theme--static-max-age=-1", "--spi-theme--cache-themes=false", "--spi-theme--cache-templates=false");
  const origin = `http://${docker("port", container, "8080/tcp")}`;
  let ready = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    try {
      ready = (await fetch(`${origin}/realms/telecom/.well-known/openid-configuration`, {
        signal: AbortSignal.timeout(1000),
      })).ok;
    } catch { /* Keycloak is still booting. */ }
    if (ready) break;
    await delay(1000);
  }
  if (!ready) throw new Error("Isolated Keycloak did not become ready within 180 seconds.");
  console.log("Testing disposable Keycloak 26.7.4 (no real accounts).");
  const child = spawn(process.execPath, ["node_modules/@playwright/test/cli.js", "test", "--config", "playwright.theme.config.ts"], {
    cwd: join(root, "apps/dashboard"),
    stdio: "inherit",
    env: { ...process.env, E2E_THEME_URL: origin, E2E_THEME_RECOVERY: "1" },
  });
  process.exitCode = await new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", code => resolve(code ?? 1));
  });
} finally {
  // Delete only this run's disposable container and generated realm copy.
  if (container) docker("rm", "-f", container);
  await rm(temp, { recursive: true, force: true });
}
