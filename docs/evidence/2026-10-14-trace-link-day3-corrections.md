# PR #104 review corrections — 14 October 2026

This report tracks corrections to the independent review of PR #104. It is not
an activation decision or a new capacity campaign. Fresh implementation and
verification results are **PENDING** until recorded below with actual commands,
source identities, logs and explicit skips. No new CI result is asserted.

## Reviewed and corrected sources

- Original PR branch: `feat/trace-link-day3-integrated-detection`.
- Reviewed head: `f41dfbb017ad4f11e7a1f18b0996a3868889ff75`.
- Pinned main: `9db3e0e5a61a8062310e560e734a5395758f70b7`.
- Correction branch: `feat/trace-link-day3-review-corrections`.
- Migration reservation commit: `7195d90`; normal main merge: `006172f`.
- Final correction implementation commits and integrated PR branch head: **PENDING**.
- Independent review evidence: user-supplied `sergiu-day3-review-evidence-20261014.zip`,
  SHA-256 `d565a85cf31c2fc40955268548885bd22c3a9cfc61562909d685a1804cce021b`.

The colleague's reviewed-source findings remain distinct from this correction's
verification. Their review rejected integration at the original head and kept
live activation disabled and connected capacity incomplete. Embedded review
harnesses and commands are evidence; they do not expand the authorized scope.

## Finding-by-finding closure

| Review finding | Implemented correction contract | Closure evidence/status |
| --- | --- | --- |
| P1 — incident V006 checksum/version collision with main | Keep main incident V001–V010 byte-identical; place detector tables in V011 after V010; retain main processor V001–V016 and additive V017 | Pending merged-source inventory, fresh/upgrade/validate/idempotency and row/checksum/grant results |
| P2 — onset shifted by 300 seconds and missing initial detection fields | Centralize the validated OPEN anchor; persist firstObservedAt from the first breached window and detectedAt from OPEN evaluatedAt; preserve both through all later transitions | Pending maintained 08:35 / 08:40 / 08:45:01 regression, database/list/detail/restart evidence |
| P2 — unsupported analyst-workflow claim | Document read-only technical incidents and evidence, with analyst workflow unavailable and no legacy UUID mapping | Documentation corrected; full analyst workflow intentionally remains an open separate contract gate |

The alternate detector V006 was used only in disposable tests. This correction
therefore allocates an unshipped migration without rewriting an applied
non-disposable schema. No Flyway repair, checksum substitution, reset or
out-of-order workaround is part of the correction. Main was integrated by a
normal merge; final conflict resolution and checksum verification must be
substantiated by the coordinator's results before marking closure.

## Corrected read contract and scope

Existing authenticated GET routes and pagination bounds remain unchanged:
`/api/v2/trace-link/incidents`, `/{episodeId}` and `/{episodeId}/evidence`.
List items and detail responses use `TraceLinkIncidentView`:

| Field | Meaning |
| --- | --- |
| episodeId | Stable hash episode identity |
| firstObservedAt | First breached window start, derived from the validated opening anchor |
| detectedAt | Initial OPEN context.evaluatedAt |
| latestWindowEnd | Latest contiguous applied window end |
| latestSequence | Latest contiguous applied positive sequence |
| technicalState | Current technical lifecycle state |
| severity | Retained episode severity |
| latestDetection | Unmodified latest strict detection envelope |

The list schema is `TraceLinkIncidentPage`; evidence uses
`TraceLinkDetectionEvidencePage`, with strict detection envelopes as items.
Initial onset/detection times must remain unchanged through equal UPDATE,
escalation, UNKNOWN, RECOVERY, duplicate replay, reconciliation and restart.
The regression onset is `2026-10-10T08:35:00Z`, OPEN interval starts `08:40:00Z`,
and initial detection is `08:45:01Z`. Latest evaluation and commit timestamps
remain available in latestDetection and are not substitutes for initial detection.

These episodes have no analyst assignment, analyst status, investigation,
resolution or workflow audit. Receipt audit records immutable delivery evidence;
it is not analyst workflow audit. No mapping to legacy UUID incidents is added.
Technical recovery changes service-health state only. Implementing an analyst
workflow requires a separate approved identity/workflow contract.

## Fresh verification ledger

Results in this table await final coordinator execution records. At documentation
draft time, Docker startup fails on its inaccessible dockerInference socket, so
database/full-integration gates are blocked rather than passed. The coordinator
reports the shared contracts artifact install completed; its command/log/source
record will be included in the final ledger. Historical test counts and colleague
review results must not be copied here as fresh passes.

| Gate | Required evidence | Current status |
| --- | --- | --- |
| Integration and unchanged migration history | Pinned main and correction commits; canonical prior migration hashes; no unresolved conflicts | PENDING |
| Incident fresh/main-to-V011 upgrade | Populated V001–V010 upgrade, validation, repeat no-op, preserved rows/checksums, grants and legacy constraints | PENDING |
| Processor fresh/V016-to-V017 upgrade | Exact main V001–V016, preserved V017, validation/no-op/rows/grants | PENDING |
| Timestamp and public views | Database/list/detail stability, all lifecycle phases, gaps, conflict/replay/restart/rollback, exact wire equality | PENDING |
| Delivery and security | Maintained real Kafka ACK/mark retry, bounds, authentication, disabled user/feature and 404 checks | PENDING |
| Root and ML Python | Actual commands, counts, exit codes, logs and skips | Root: 174 passed, zero skips (coordinator-reported); ML and final records pending |
| Full reactor and separate receiver | Actual commands/counts/skips; tested shared contracts installed before receiver | PENDING |
| Contracts, pins and generated API | Both OpenAPI copies, generated types, frozen canonical blobs and separate Windows byte results | PENDING |
| Dashboard/browser/configuration | Unit/build/affected browser results, Compose structural validation and whitespace | Dashboard: 181 unit tests and production build passed (coordinator-reported); remaining gates/records pending |

Documentation agent cross-reviewed both coordinator OpenAPI copies after the
DTO update: byte-identical SHA-256
`faadd9a594b684c86e173f66055f9049b82908adc2cb490ba41b96d7dc54ce58`.
The exact eight required view fields, stable timestamp meanings, int64 sequence,
unchanged bounds, separate envelope evidence page and explicit technical-only
scope match the frozen correction interface. No blocking schema finding was
identified by this static review. This does not substitute for runtime API tests.

Archive new logs/manifests under
[assets/trace-link-day3-corrections-20261014](assets/trace-link-day3-corrections-20261014/README.md).
Record commands, cwd, UTC start/end, exit codes and source hashes. Preserve
initial failed attempts alongside corrected results where relevant. Windows
checkout byte failures remain failures; a separate canonical Git-blob check
must not silently replace them. Component checks do not prove live OIDC or a
connected deployment when those were not exercised.

## Historical evidence and remaining gates

The [13 October measurement report](2026-10-12-detection-capacity.md) remains a
historical record: 4,584 finalized/evaluated windows, 2,301 committed detections,
and subsequent receiving reconciliation. Original compressed assets, manifests,
raw measurements and recorded source commits are unchanged. Their measured
source `59827b1d199516e9ddc10a4df6a8479dc370870b`, integrated implementation
`27f596f55d1f406c84f141987866b84aced19449` and reviewed head `f41dfbb` are distinct
from the corrected source. Those capacity results do not automatically apply
to the corrected implementation. No long benchmark is rerun for these fixes.

These gates remain open independently of correction verification:

- Upstream 60-second `trace-link-domain-window-v1` compatibility with the
  300-second `trace-link-persisted-window-v1` detector; no implicit bridge or
  resolution relabeling is introduced.
- Full simultaneous source collection/finalization-to-incident capacity:
  `SOURCE_COLLECTION_AND_FINALIZATION_CAPACITY_UNMEASURED` and
  `SEPARATE_RECEIVER_REPLAY_NOT_SIMULTANEOUS` remain the historical gate reasons.
- Personal matching-window/receiving/wording/hardware decisions and independent
  acceptance of the corrected PR. Tests do not supply these sign-offs.
- Analyst identity/workflow integration and deployment/activation acceptance.

Disabled-foundation correction acceptance: **PENDING verification and independent
review**. Full connected capacity: **INCOMPLETE**. Live activation: **DISABLED**.
No push, remote PR merge, deployment or activation is performed by this report.
