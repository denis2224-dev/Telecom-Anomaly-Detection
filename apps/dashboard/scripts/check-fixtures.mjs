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
const missing = structuredClone(services[0]);
missing.freshness = "MISSING";
missing.latestWindow = null;
if (!validate(missing))
  throw new Error("Missing telemetry must be schema-valid.");
const invalid = structuredClone(services[0]);
invalid.latestWindow.kpis[0].observed = "90%";
if (validate(invalid))
  throw new Error("Schema must reject string measurements.");
invalid.latestWindow.kpis[0].observed = 90;
invalid.latestWindow.kpis[0].unit = "UNKNOWN_UNIT";
if (validate(invalid)) throw new Error("Schema must reject unknown units.");
console.log(
  `PASS: ${services.length} service fixtures match OpenAPI ${contract.info.version}; null data is accepted and invalid units/types are rejected.`,
);
