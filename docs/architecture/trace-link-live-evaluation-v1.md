# trace-link-live-evaluation-v1 — receiving detector proposal

Status: **PENDING_REVIEW by Sergiu**. This document proposes an additive interface;
it does not register a detector, approve a baseline, or change the merged offline
rules. It was prepared on 12 October 2026, Europe/Chisinau.

The initial integrated source baseline was `47a2c9236e8ac82351b7fa62ec2264cc8df103c2`.
Main subsequently merged #91/#92 at `ae9a53626ac7ee62efec40a19ebb20c6e2cf7049`;
the candidate records that ancestry without any tree change.
The producer input is frozen #97, `5d2b6d102ac2914ede7bfc812ad51ebb3b20dc2c`.
The separate native VoLTE projection is #92,
`b987aa809101eedc1d40d52c95dff94a1e9e27b8`.

## Reproduced boundary

Unchanged `TraceLinkInputs` accepts its existing aligned 300-second context.
It rejects the actual producer observation as a different wire schema, and
rejects the otherwise valid offline context with a 60-second window with
`Expected aligned five-minute window`. The integration evidence archives the
probe and the exact compiled candidate identity. The legacy probe's
`mainCommit=bccfd01...` label identifies the unchanged detector policy source;
the new execution manifest records the actual candidate SHA separately.

Native history remains `trace-link-md-30d-v1`, 300 seconds. Live inputs remain
`trace-link-live-synthetic-v1`, 60 seconds, synthetic=true. Neither changes its
dataset, timestamps, provenance or event identity to satisfy the offline schema.

## Proposed envelope and authority

The proposed input has `schemaVersion=trace-link-live-evaluation-v1`, an array
of **unchanged** `trace-link-domain-window-v1` observations, and separately
supplied receiving context. This proposal must acquire a reviewed JSON Schema
and implementation before any runtime acceptance. It is not an alternative
input already understood by `TraceLinkInputs`.

| Input section | Required facts and admission rule |
| --- | --- |
| observations | Exact original payload bytes/hash and event IDs; one approved scope, native minute and revision; measured primitives only; strict existing producer schema validation |
| sourceAuthority | Generator `trace-link-minute-generator-v1`, source `trace-link-minute-generator-v1-seed-42` for the supplied examples; synthetic flag, dataset, metric dictionary `trace-link-kpi-v1`, catalogue `trace-link-geography-v2`, topology `trace-link-links-v1`; catalogue remains CONTRACT_ONLY until separately approved |
| scopeBinding | Versioned, catalogue-backed binding from producer dimensions to a detector scope; retain original dimensions and binding version; reject absent or ambiguous authority |
| receipt | Receiver-assigned receivedAt and receiver identity; original emittedAt retained; payload hash checked before admission; no scenario label or expected outcome |
| evaluation | evaluatedAt, source observedAt basis, freshness policy and separately versioned minute rule/recovery policy; these are receiver facts and decisions, not inferred from emittedAt |
| baselineDecision | Exact metric, scope, unit, duration, dataset, topology, training interval/cutoff and baseline policy/version; declare learned, explicit demonstration static fallback or unavailable; no copying an offline five-minute baseline into a minute context |
| provenance | Original event IDs, source payload hashes and revision; correlation dependencies identify actual observed inputs and their scope/time; preserve absence rather than manufacture dependency evidence |

The six current live metrics are `transport.traffic`,
`transport.effectiveCapacity`, `transport.link.utilization`,
`roaming.registration.success`, `mobileData.dns.success` and
`mobileData.activation.success`. Their current population, unit and primitive
operator remain those in the dictionary and the live dataset manifest.
`volte.setup.success` and `voice.success_rate` require a separate consumer
decision; this proposal supplies no metric alias.

## Scope translation to review

| Producer scope | Proposed explicit detector binding | Unresolved authority |
| --- | --- | --- |
| LINK_DIRECTION: `scopeId=chi-orh`, `linkId=chi-orh`, `directionId=a-to-b` | Reference link-direction identity `chi-orh:a-to-b`, endpoints CHI and ORH from pinned topology; retain the original link and direction | Sergiu approves binding version; transport capacity is interval-specific; no packet loss, RTT or member failure inferred |
| ROAMING_PROCEDURE: `scopeId=RO-DEMO-01`, partner RO-DEMO-01, country RO, direction out, procedure REGISTRATION | PARTNER_DIRECTION plus an explicit preserved REGISTRATION procedure dimension | A partner is not a country. The receiving catalogue must register the synthetic partner and distinguish out from in; string concatenation alone is insufficient |
| CITY_SERVICE: `scopeId=chi`, `contractCityId=CHI`, LTE, DNS or EPS_BEARER_ACTIVATION | CITY CHI plus preserved service/access/procedure dimensions | The current CITY shape cannot silently discard LTE/procedure. Sergiu chooses an additive shape or explicit metric-specific binding |

The reference topology is `trace-link-md-topology-v1`; the producer topology is
`trace-link-links-v1`. Matching endpoint names do not approve equivalence. Keep
both identifiers and a reviewed binding, or reject the input. Active inventory
selection must never overwrite retained historical identities.

## Actual producer examples

Run `scripts/verify-trace-link-integration.py` using the owner checklist to
extract eight unchanged JSONL records with source path, one-based line,
file/payload hashes and event ID. The originals are the #97
`docs/evidence/assets/trace-link-day6/run-1/*.jsonl` files. They contain no
runId, scenario name, expectedTransition or evaluator truth.

At logical `[2026-10-12T09:02:00Z,2026-10-12T09:03:00Z)`:

- MOBILE_DATA / chi / LTE / DNS contains numerator 711, denominator 1016,
  VALID, coverage 1, event ID
  `53c3f8613694527513c7a715284f83a77d56242c933dd685c73c7dcc33d5185c`.
- ROAMING / RO-DEMO-01 / RO / out / REGISTRATION contains numerator 709,
  denominator 1013, VALID, coverage 1, event ID
  `5829367256e14ee3ad67218b28709050ca4390d77a8a1f9a7f4b11cbc6e75aca`.
- The same extraction includes transport overload, healthy high-utilization
  and opposite-direction controls, the opposite roaming direction, independent
  healthy LTE activation, and an actual MISSING DNS observation at 09:18 from
  the separate gap schedule, with null primitives and zero coverage. The verifier validates each original schema
  and deterministic identity before copying its exact payload bytes.

These are archived logical observations. Replaying their bytes today does not
establish wall-clock freshness. The receiving harness must explicitly use an
approved replay clock or reject them as stale; it must not rewrite their time.

## Quality, time, identity and missing-data decisions

Row/slot presence and observed source quality are distinct. A scalar producer
quality and coverage cannot automatically become the offline structured
quality/freshness object. Preserve VALID, MISSING, INSUFFICIENT_EVIDENCE, retained
STALE/PARTIAL evidence and nullable historical source metadata. Zero attempts
produce a null ratio and an insufficient-population reason; measured zero is
still an observation. A gap must not count as a healthy result or recovery.

UTC windows are half-open. Live source alignment is exactly 60 seconds;
emittedAt equals the generated window end. Receiving time, evaluation time,
observed source time and age must have explicit independent meanings. Admission
must reject future evaluation, wrong scope/version, duplicate metric evidence,
truth leakage and contradictory coverage rather than normalize them away.

Retain the producer eventId unchanged. Its frozen identity tuple is documented
in `scripts/trace_link_scenario_contract.py:ID_FIELDS` and includes source,
dataset, namespace, complete scope dimensions, metric, window/duration, schema,
catalogue, topology and policy. Exact duplicate bytes have one logical receipt;
different content under the same ID/natural key is a conflict. A proposed new
evaluation ID must have its own versioned tuple and reference original event IDs;
it must not replace a producer ID or derive identity from expected labels.

The current recovery tests concern five-minute offline evaluations. The live
opening count, healthy count, timeout/lateness, UNKNOWN interruption and
recovery timing are **unapproved decisions**. Three generated recovery minutes
do not establish three accepted five-minute evaluations or an incident lifecycle.
Utilization alone establishes an overload candidate, not customer outage,
packet loss, RTT degradation, service failure or an attributed cause.

## Decision record required from Sergiu

Approve or reject, with exact versions: source/catalogue/topology binding;
scope and procedure preservation; live evidence and result schema; minimum
population and freshness; compatible baseline training or explicitly bounded
demonstration fallback; minute rule and recovery semantics; UNKNOWN handling;
evaluation identity and dependency provenance; rejection reasons and tests.

No pure adapter was implemented. A lossless transport envelope is possible,
but it cannot supply these missing semantic authorities, source timestamps,
baselines or a minute policy. Filling them to call the current offline detector
would assume the decisions this review is meant to obtain. Raw rejection,
positive existing offline fixtures and the proposed fixture bundle are the
independently executable handoff. Live detector acceptance remains BLOCKED.
