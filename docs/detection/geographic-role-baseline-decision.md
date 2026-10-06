# Geographic roles and baseline compatibility — Sergiu G1 decision

The technical decision is to consume Ion's merged, contract-only catalogue and existing resolver,
and explicitly map city scopes to the unchanged fixed synthetic baseline regime. Default activation stays unchanged.
The current integration base and mapping/file digests are in `docs/evidence/2026-10-05-sergiu-day1-contract-manifest.json`.

## Published catalogue accepted for Sergiu's implementation contract

Ion PR #48 is merged into the selected mainline. Companion `1-geography-g1`, authority `2-geography-g1`,
and activation CONTRACT_ONLY/null contain CHI, BAL, EDI, SOR, RIB, UNG, TIR, COM, CAH, ORH.
Every city has `VOLTE-MD-<city>` and `SMS-MD-<city>`. Two unchanged legacy/unallocated scopes remain separate.

| Role | City target / reporter | Legacy target / reporter | Coverage |
| --- | --- | --- | --- |
| VOLTE_IMS | IMS-MD-<city>-01 | IMS-A | Required |
| VOLTE_TRANSPORT | TRANSPORT-MD-<city>-01 | TRANSPORT-A | Required |
| SMS_SMSC | SMSC-MD-<city>-01 | SMSC-A | Required |
| SMS_TRANSPORT | TRANSPORT-MD-<city>-01 | TRANSPORT-A | Optional |

SERVICE reporter is VOLTE-SRC-<city> or SMS-SRC-<city>. Legacy reporters remain VOLTE-ADAPTER and SMS-ADAPTER.
Every city thus needs three VoLTE/two SMS raw measurement sources; optional SMS transport does not increase required coverage.

Reuse `GeographyCatalog.resolve(scopeId, Role)` returning the exact `TopologyCatalog.Node(nodeId, sourceId)`.
Resolve against a captured catalogue/topology pair before selecting a receipt. Missing/ambiguous role, wrong service,
unknown authority/version and unrelated reporter fail closed. Loader tests already exercise invalid catalogues;
GeographicBaselineContractTest additionally exercises the legacy/city mappings in the detector dependency context.

Sergiu's Day 2 conversions are DetectionWorker receipt selection, VoiceSetupRule/SmsDeliveryRule authority checks,
and Python service_features.py selectors. Ion converts ServiceFeatureBuilder/EvidenceJoiner. Scope/start/end, COMPLETE quality,
required metrics and feature contributor event membership remain necessary. Any available node is not a substitute.
Current feature payloads pin topologyVersion; proposed coverage pins catalogueVersion too. Day 2 must provide a
version-aware lookup or an accepted drain/activation boundary without adding unreviewed fields to strict V2.

## Explicit peer candidate

`contracts/baselines/geographic-peer-baseline-v2.json` is an opt-in contract candidate, not the startup default.
It retains baseline-v2 and the exact two original direct baseline entries/values/hour slots. It adds twenty explicit one-hop peers:

- Every VOLTE-MD-<city> → VOLTE-MD-CENTRAL.
- Every SMS-MD-<city> → SMS-MD-ROUTE-A.

Keep DIRECT, PEER and BASELINE_MISSING provenance, requested scope and donor scope explicit. The catalogue remains fixed synthetic
teaching data, not a city-specific estimate or production operator baseline. The manifest records the candidate digest separately
from baselineVersion. Mapping changes require a new reviewed mapping/catalogue snapshot; baselineVersion cannot hide a changed numeric regime.

GeographicBaselineContractTest explicitly constructs BaselineRegistry(candidate, candidateAuthority) and checks all 22 scopes ×168 hours,
including all twenty city peer scopes ×168=3360 resolutions, unchanged donor values, absent donor hour and cross-service rejection.
The normal BaselineRegistry() still resolves only the two legacy scopes. DIRECT wins over configured PEER; fallback is one hop,
same service, with no default/global donor and no synthetic values for an absent hour. UTC hour zero is Monday 00:00; 167 is Sunday 23:00.

## ML compatibility decision

Keep isoforest-v2-synthetic-1, baseline-v2, schema/feature version 2, exact six-feature order, model/calibration bytes,
thresholdRank 0.99, timeout 250ms and eight permits. Existing scorer enforces exact baselineVersion and runtime/artifact compatibility.

Unchanged peer numerical meaning can produce contract-compatible model input, disclosed as synthetic PEER. A changed baselineVersion/regime
remains incompatible until separately reviewed; reject scoring and preserve deterministic detection. Never relabel an altered baseline or loosen
score validation. No training or repackaging is needed for this sprint.

The new model test exercises existing eligible golden vectors under all twenty city scope contexts and rejects changed baseline/order.
This proves scorer contract compatibility only. It does not prove fresh role-aware feature parity, city-specific performance or geographic generalization.
Those limits must remain visible in the Day 2/G5 evidence.

## Detection-package coverage/outbox handoff

Sergiu accepts Ion's proposed package boundary: under the existing WindowDecisionLock/finalization transaction, accepted receipt snapshot,
finalized feature/KPI, coverage delivery row in generic app.voice_delivery and finalized marker commit atomically. Apply to both measured
and inferred-missing paths. The coverage fact binds to the exact feature windowId and pinned catalogue pair; insert before detection/ML.
Any failure rolls back the entire finalization, with no cross-database transaction or second delivery mechanism.

Topic telecom.coverage.v1; key scopeId; id coverageId. The V1 namespaced ID avoids feature/detection ID collisions. Existing lease/ACK/retry
delivery applies later. Exact retries reuse immutable payload and ID; conflict handling cannot use ON CONFLICT to silently replace different content.
Bootstrap's KPI-only drain must explicitly include coverage IDs/topics before geographic history is enabled. Ion authors finalizer-side changes;
Sergiu authors agreed detection-package changes; Denis consumes and projects idempotently into incidents_db. No Day 1 migration/outbox runtime change is made.

Denis's published geography-backend-contract proposal agrees with these city/source/version/idempotency/null boundaries. Its branch is a reviewed source
for semantic alignment, not a merged runtime consumer or a signature on Sergiu's new outputs. Canonical OpenAPI and migrations remain Denis's editing surface.

## Before Day 2 activation

Compatible readers precede city producers. Freeze effective UTC activation and keep old versions for historical evidence. Drain baseline work or
prove version-aware reads before switching authority. Feature-off stops city production while compatible readers drain pending facts;
do not downgrade to baseline-only processor code while city facts are queued. Stanislav records the combined integration/rollback gate and owner reviews.
