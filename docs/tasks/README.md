# Project guides

Use current runbooks and contracts for development. Dated evidence identifies
the source, commands, outcomes and acceptance limits of earlier work.

## Feature guides

- [Service overview](../../apps/dashboard/src/app/features/service-overview) and
  [shared contracts](../../contracts/README.md): configured inventory, stored
  windows and explicit missing data. See the
  [foundation evidence](../evidence/2026-09-18-sergiu-days-1-4.md).
- [Login and session](../../apps/dashboard/src/app/features/login-and-session),
  [theme setup](../runbooks/login-theme.md) and [local setup](../runbooks/local-dev.md):
  open `http://telecom.test:8080/login`, continue to Keycloak, then return to the
  dashboard. Accounts require an application role and enabled analyst mapping;
  temporary passwords are changed on first sign-in. Accounts are administrator-managed.
- [KPI history](../../apps/dashboard/src/app/features/service-kpi-history) and
  [voice browser checks](../../apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts):
  select a UTC range at `/services/VOLTE-MD-CENTRAL`. Actual and baseline lines
  preserve missing-data gaps; each episode has one incident card and separate
  technical recovery and analyst workflow states. Run
  `npm --prefix apps/dashboard run start:fixtures` for the labelled design
  preview at port 4200; the protected persisted flow uses port 8080.
- [Protected voice investigation](protected-voice-investigation/README.md) — complete login-to-evidence flow and exact startup commands.
- [Evidence timeline and cause panel](evidence-timeline-and-cause/README.md) — historical detections, source evidence, impact and cause hypotheses.

## Frontend folders

Under [`apps/dashboard/src/app/features`](../../apps/dashboard/src/app/features):

- `login-and-session/` — app login screen, sign-out, session state and protected-route guard. Previously `session/`.
- `service-overview/` — dashboard service cards and their health states.
- `service-kpi-history/` — KPI chart, time-range filter and service history. Previously `service-detail/`.
- `incident-investigation/` — incident cards and the full evidence timeline. Previously `incidents/`.

The username/password form lives in [`infra/keycloak/themes/telecom/login`](../../infra/keycloak/themes/telecom/login), because Keycloak handles credentials.
Shared API calls and generated API types live in [`core/api`](../../apps/dashboard/src/app/core/api).
Browser tests live in [`tests/e2e/specs`](../../apps/dashboard/tests/e2e/specs).

## Backend and setup folders

- [`services/event-generator`](../../services/event-generator) creates synthetic measurements.
- [`services/processor`](../../services/processor) calculates KPIs and detects voice episodes.
- [`services/incident-service`](../../services/incident-service) stores incidents and provides protected APIs and login sessions.
- [`services/streaming-support`](../../services/streaming-support) validates shared observation contracts.
- [`services/ml-service`](../../services/ml-service) contains independent Python feature calculations.
- [`infra`](../../infra) contains Docker service configuration, database setup, proxy and Keycloak branding.
- [`scripts`](../../scripts) contains startup, verification, account provisioning and scenario commands.
- [`runbooks`](../runbooks) contains detailed setup instructions; [`evidence`](../evidence) records what was verified.

Backend folders already describe their functions. Their names, API URLs and the
assignment's evidence filenames remain stable. Older screenshots that mention
`session`, `service-detail` or `incidents` refer to the renamed frontend folders above.

## Completed work and remaining acceptance

Completed implementation plans and personal checklists have been consolidated
here. Interfaces, independent reviews, reproduction instructions and actual
evidence remain in their existing locations. Historical reports and captured
GitHub trees can mention filenames from their original revision.

The geographic redesign assignment came from
`Orange_Internship_5_Day_Planning_Pack_FINAL.zip`: `source/02_Rusu_Serghei_5_Day_Tasks_FINAL.md`,
`source/00_Shared_Project_Redesign_5_Day_Plan_FINAL.md` and `source/06_Repository_Audit_FINAL.md`.
That pack identifies Rusu Serghei as Rusu Sergiu and supplied editable Markdown;
its advertised PDF entries were absent. The planning audit used `cdac43e3`, while
the actual Day 1 implementation used `46c72f9`, as recorded in the evidence below.

| Work | Retained result and reproducible boundary |
| --- | --- |
| September detection foundations | [Days 1–4 verification](../evidence/2026-09-18-sergiu-days-1-4.md) and [detection contracts](../detection-contracts.md). |
| Geographic numeric, role and evidence contracts | [Day 1 evidence and owner dispositions](../evidence/2026-10-05-sergiu-day1-numeric-evidence.md). The October Rusu Serghei/Sergiu assignment's local technical completion did not supply shared G1 signatures. |
| Geographic parity and explanations | [Day 2](../evidence/2026-10-06-sergiu-day2-role-aware-parity.md), [Day 3](../evidence/2026-10-07-sergiu-day3-geographic-explanations.md) and [cause controls](../evidence/2026-10-08-sergiu-day4-cause-controls.md). Preserve each report's local/shared-gate distinction. |
| Geographic acceptance and PR #77 | [PR #86 reconciliation](../evidence/2026-10-10-sergiu-pr86-reconciliation.md), [PR #77 remediation](../evidence/2026-10-09-pr77-review-remediation.md) and [current-main receipt](../evidence/2026-10-09-pr77-main-613661d-acceptance.md). Historical connected runs qualify only their recorded source. |
| Validation-selected ML experiment | [Method, held-out metrics and limitations](../evidence/2026-10-06-ml-validation-selection.md). The experimental 1% overall false-positive budget does not approve production use or hide weak SMS families/profiles. |
| Trace Link Day 1 | [Four-language evidence](../evidence/2026-10-10-trace-link-day1.md), [calculator interface](../../tasks/trace-link-day1/interface.md), [reproduction](../../tasks/trace-link-day1/reproduction.md) and [attributed receiving decisions](../../tasks/trace-link-day1/handoffs.md). |
| Trace Link Day 2 | [Offline evidence](../evidence/2026-10-11-trace-link-day2.md), [corrected reproduction](../evidence/2026-10-12-trace-link-day2-receiving-closure.md) and [interfaces/reviews](../../tasks/trace-link-day2). A corrected local run does not imply Ion's receiving approval. |
| Trace Link Day 3 | [Correction report](../evidence/2026-10-14-trace-link-day3-corrections.md), [implementation interface](../../tasks/trace-link-day3/interface.md) and [operations](../runbooks/trace-link-detection-day3.md). Read-only technical incidents do not provide analyst workflow integration. |
| Trace Link Day 4 | [ML shadow runbook](../runbooks/trace-link-ml-shadow.md), [review corrections](../evidence/2026-10-14-sergiu-day4-review-corrections.md) and [response bounds](../evidence/2026-10-14-sergiu-day4-response-bounds.md). Shadow inference remains separate from deterministic incident decisions. |
| Trace Link Day 5 | [Evaluation/model operations](../runbooks/detection-and-models.md), [platform scope](../evidence/2026-10-15-sergiu-day5-platform-correction.md) and [automated receipt](../evidence/2026-10-15-sergiu-day5-independent-review.md). Use fresh output directories and the runbook's Windows/Linux commands; applicable failures remain nonzero/INCOMPLETE. |

The [completion record](../evidence/trace-link-completion-status.md) and
[release checklist](../ux/release-checklist.md) govern remaining work. Retain
outstanding human/receiving-owner acceptance, the native 300-second versus live
60-second compatibility decision, simultaneous source-to-incident capacity,
authenticated/security/accessibility checks and receiving-machine rehearsal.
Automated approval, CI and component reproduction cannot supply human signatures.
Optional auxiliary evidence stays PLANNED until authority, publisher, consumer
and storage/API readiness are approved.
