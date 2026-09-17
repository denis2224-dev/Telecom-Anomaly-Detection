import { readFileSync } from "node:fs";
import { parse } from "yaml";
import Ajv from "ajv";
import addFormats from "ajv-formats";

const contract = parse(
  readFileSync(new URL("../api/incident-api.yaml", import.meta.url), "utf8"),
);
const services = JSON.parse(
  readFileSync(
    new URL("../src/fixtures/services.json", import.meta.url),
    "utf8",
  ),
);
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);
const validate = ajv.compile({
  $ref: "#/components/schemas/ServiceSummary",
  components: contract.components,
});
for (const service of services) {
  if (!validate(service)) throw new Error(JSON.stringify(validate.errors));
}
const byScope = new Map(services.map((service) => [service.scope.scopeId, service]));
for (const scopeId of [
  "VOLTE-MD-CENTRAL",
  "SMS-MD-ROUTE-A",
  "VOLTE-MD-NORTH",
  "SMS-MD-ROUTE-B",
]) {
  if (!byScope.has(scopeId)) throw new Error(`Missing Day 04 fixture: ${scopeId}`);
}
if (byScope.get("VOLTE-MD-NORTH").freshness !== "STALE")
  throw new Error("Stale data must remain distinct from fresh data.");
if (byScope.get("SMS-MD-ROUTE-B").latestWindow !== null)
  throw new Error("Missing telemetry must not contain an observation window.");
const invalid = structuredClone(services[0]);
invalid.latestWindow.kpis[0].observed = "90%";
if (validate(invalid))
  throw new Error("Schema must reject string measurements.");
invalid.latestWindow.kpis[0].observed = 90;
invalid.latestWindow.kpis[0].unit = "UNKNOWN_UNIT";
if (validate(invalid)) throw new Error("Schema must reject unknown units.");
console.log(
  `PASS: ${services.length} service fixtures cover normal, degraded, stale, and missing states and match OpenAPI ${contract.info.version}.`,
);
