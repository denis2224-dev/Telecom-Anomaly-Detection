# PR #107 integration verification

Observed 15 October 2026, Europe/Chisinau. Production source: `22ed57ab283c8204a69ac040f7695cddd837349f`. Integrated foundation: main `9085cb1cef268b795a5f037be79fa56300d8e787` (#103). This verifies the feature merge; it does not claim final all-owner release acceptance.

## Migration preservation

Main V001–V012 are byte-for-byte unchanged. The original foundation V009 and scenario V011 remain byte-for-byte under `db/compatibility/foundation`. A startup migration strategy selects these resources only for the recorded foundation V009 script. Flyway validates existing checksums normally; no repair, rewritten history, out-of-order bypass, baseline or reset occurs.

Additive V013 converges both known histories to the main V012 episode namespace and extends scenario types. Three compatibility tests cover populated main V012, both original foundation endpoints (V009 and V011), retained complete history rows and episode/evidence/audit data, identical schemas, runtime evidence-delete denial, repeat migration applying zero, and rejection of an altered historical checksum. Existing populated V004 and detector-upgrade regressions also pass. Raw external Flyway callers must select `TraceLinkMigrations.forDatabase` when upgrading the historical foundation track; the default location remains valid for fresh/main databases.

## Integrated behavior

The foundation notification adapter remains the sole notification history route. The streaming exporter replaces the duplicate inline CSV route and retains exact primitive values, null reasons and provenance. Generated OpenAPI and Angular types agree at API 0.5.0. The previously verified enabled-account and adjacent-capacity repairs are carried forward from the reliability stack before rollout. Mock identities are explicitly registered; asynchronous CSV checks wait for completion and verify zero denominators as null/INSUFFICIENT_EVIDENCE.

## Observed verification

- Standalone backend: 205 unit tests, zero failures/errors, one existing skip; 142 integration tests, zero failures/errors/skips.
- Root Maven: streaming-support, generator and processor verification pass.
- Repository Python: 176 tests, pass with one existing PowerShell-only skip.
- Dashboard: 181 tests across 35 files and production build pass. Contracts, generated-client parity, Compose configuration and packaged runtime image build pass.
- ARM64 Linux ML run: 90 tests pass; strict Windows candidate byte rebuilding differs. Frozen artifacts are unchanged. The existing required Windows reproducibility CI job, followed by Linux model evaluation, remains a merge gate; this local result is not reported as full model reproduction.

The final packaged application (`sha256:3beda419892ed335d56a14a682df480d0e100bdfbc848c079629b95ced194e07`, local Docker content store) upgrades the existing disposable foundation QA database from V011 through V013, retains 362880 historical metric windows and starts healthy. Real OIDC analyst/supervisor sessions pass sixteen CSV/JSON parity queries, two controlled Kafka lifecycle/workflow sequences, replay, CSRF, actor isolation, optimistic conflict, restart/read-state preservation and logout. Disabled-account incident, service and notification reads all return 403. The exact observed IDs/counts are in [controlled smoke](assets/pr107-integration/controlled-smoke.json).

Structured results, immutable SQL hashes and private-log checksums: [verification](assets/pr107-integration/verification.json). Credentials, tokens, cookies, raw logs and backups remain outside Git. Original application volumes are untouched.

## Merge and release boundary

The user explicitly authorized this feature merge without waiting for coworker reviews. Automated CI still must pass on the final head. Optional push, the symptom adapter and both new-domain detector activation flags remain false. Main's existing protected local-generation scenario surface retains its own contract. Real browser push, complete new-domain activation, full capacity/Grafana/offline acceptance, complete shared release manifest, two complete release rehearsals and five-owner release sign-off remain later release gates. The controlled checks above do not fill those acceptance fields.
