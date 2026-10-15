# Backend rehearsals

Update, 15 October 2026: the 14 October candidate below is **superseded and unaccepted** by the integrated #103/#107 source. Its original hashes and controlled-run records remain historical evidence; no complete rehearsal or owner acceptance transfers to the newer candidate. Current feature verification is in [PR #107 integration](2026-10-15-pr107-integration.md), including the unchanged main V001–V012 and additive V013 path. Capture a new shared release identity after integration and repeat required release acceptance.

Observed 14 October 2026, Europe/Chisinau. Complete release rehearsal acceptance: **pending**. The two runs below are automated controlled Kafka lifecycle checks with real OIDC sessions; no teammate browser demonstration or full new-domain scenario run is claimed.

Candidate application revision: `6b4d4246a5a8eb563394525dbd04394d432ed8ca`. Local deployed image: `trace-link-backend-qa-20261014-incident-service@sha256:4f035a8ecf3d1e2b393941f9d1ad624ea8e36129485bf5a0ce5b3095cf341029`. Exact API, migration, dataset, model, policy, topology and public identity hashes are in [backend-release-candidate.json](../release/backend-release-candidate.json). Both runs used this same candidate. Push and new-domain scenario flags were off; origin was `http://telecom.test:8080`.

## Controlled run 1

Run ID: `4332f332-f78d-494a-958f-6a9288fdd5c3`. Episode: `30b84a9f68fa075b4dda83e8210d35e8330a52b8d04fdf49b7e43aefef4ec6cc`. Incident: `b9e61913-3ff4-45a1-a4b3-7f16fa19d811`. Detection IDs and exact series/CSV results: [controlled-smoke.json](assets/backend-reliability/controlled-smoke.json).

Expected and observed: each role receives four items for OPEN, escalation, recovery, and its assignment. The same-severity and UNKNOWN updates create no lifecycle alert; identical detection replay creates no duplicate. Technical recovery preserves workflow separation. Owned read mutation is idempotent; foreign mutation returns 404, absent CSRF returns 403, stale incident version returns 409. The supervisor assigns and reassigns using observed versions; workflow moves OPEN to INVESTIGATING to RESOLVED.

Status: passed for these observed automated checks. Full browser/new-domain/owner rehearsal: pending. Receiving teammate and reproduction result: pending. No generator scenario acceptance or complete event/window chain is inferred from controlled detections.

## Controlled run 2

Run ID: `4f3d7127-46fe-4771-a54c-ae585cfe84a5`. Episode: `20fa21c2571b3b78b203ddb5e2238bf74ad7ff63fbc311ae91cae7aef8723377`. Incident: `441eb9b7-5eb1-4e84-8735-169565b98504`. Detection IDs and exact series/CSV results: [controlled-smoke.json](assets/backend-reliability/controlled-smoke.json).

Expected and observed: each role receives four items for OPEN, escalation, recovery, and its assignment. The same-severity and UNKNOWN updates create no lifecycle alert; identical detection replay creates no duplicate. Technical recovery preserves workflow separation. Owned read mutation is idempotent; foreign mutation returns 404, absent CSRF returns 403, stale incident version returns 409. The supervisor assigns and reassigns using observed versions; workflow moves OPEN to INVESTIGATING to RESOLVED.

Status: passed for these observed automated checks. Full browser/new-domain/owner rehearsal: pending. Receiving teammate and reproduction result: pending. No generator scenario acceptance or complete event/window chain is inferred from controlled detections.

## Shared observed checks and remaining work

Sixteen exact CSV/JSON comparisons pass; invalid queries return 400. Final restart preserves 28 visible items/read state; fresh authenticated REST reconciliation succeeds and logout denies private reads with 401. Disabled-account incident/service/notification reads return 403. Isolated database restore preserves counts and read/event fingerprints.

Commands and structured evidence are in [backend reliability](2026-10-14-backend-reliability.md). Private scripts, credentials, cookies, logs, and backups remain local.

Still pending: the complete [demonstration script](../demo/denis-script.md) twice on one integrated, immutable candidate; producer-driven VoLTE/SMS/new-domain commands, full window/event/source chain, archived catalogue UI filters, browser consent/accessibility/reconnect, measured capacity/freshness, Grafana parity, offline receiving-teammate reproduction, and five observed owner sign-offs. Any production source/config/image/dataset change invalidates complete rehearsal acceptance. These controlled runs remain separate from the empty acceptance `rehearsals` array in the manifest.
