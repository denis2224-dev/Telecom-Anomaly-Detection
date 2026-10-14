# Day 3 receiving handoffs

Technical implementation and synthetic reproduction are separate from personal
teammate approval. These notes have not been sent externally.

| Owner | Review material | Recorded disposition |
|---|---|---|
| Ion | Exact native primitive observations/checksums, saved-window manifest, event-valid authority and measured rate units | Matching-window confirmation and updated Day 2 receiving decision not supplied in this chat |
| Denis | Strict delivery schema, V011 detector projection after main V010, immutable evidence and stable onset/detection read fields | Historical receiver reproduction passes; corrected integration verification is recorded separately; personal contract acceptance not supplied |
| David | Incident-view fields and nested wire evidence, explicit unknown/insufficient status, unsupported versus supported hypotheses, technical recovery and unavailable analyst workflow | Wording handoff prepared; personal wording acceptance not supplied |
| Stanislav | Hardware/profile/source hashes, observed four/eight persisted windows per second, bounded queue/drain and separated receiver replay | **ACCEPTED_CAPACITY_OBSERVABILITY_BASELINE**: verified coordinated load profile, queue boundedness, drainage in <0.4s, Prometheus 11 alert rules and recording rules, and platform vs service health separation. |

## Stanislav — capacity and observability acceptance

Stanislav has **ACCEPTED_CAPACITY_OBSERVABILITY_BASELINE** at the reviewed commit.

| Receiver | Reviewer identity | UTC time | Checkout SHA | Commands | Output SHA-256 hashes | Comments / disposition |
| --- | --- | --- | --- | --- | --- | --- |
| Stanislav | Stanislav / GitHub `stasikkk777` | 2026-10-14T19:00:00Z | `cfc3e98d4ff7c0d1161a74fd230fe9f756ba3423` | `python scripts/load-test.py`, `python3 scripts/check-contracts.py`, `python3 -m unittest discover -s tests -v`, `python3 -m unittest discover -s services/ml-service/tests -v`, `docker compose config --quiet` | See table below | **ACCEPTED_CAPACITY_OBSERVABILITY_BASELINE**: confirmed warmup/steady/burst/recovery profile behavior, peak queue bounds (4 windows), drainage time (0.3699s), 11 alert definitions with condition/duration/rationale/action/recovery, and clean pass across all 258 test cases. |

### Output Hashes

| Artifact | SHA-256 |
| --- | --- |
| `infra/observability/prometheus/alerts.yml` | `f0b6d442d063bdf6f6b39d5302a1cb23a5f3eb85260e0cba4a1d39d86f63e993` |
| `infra/observability/prometheus/rules/trace-link.yml` | `496fa12055eb9cf9c86afb0a1455cad50fa8255471b379ce532eb15d99d42b07` |
| `infra/observability/prometheus/prometheus.yml` | `8fe84a67cd9bcaf5da645bf79446980fa1b64adee0c224d816491c5258848111` |
| `docs/evidence/2026-10-12-performance.md` | `a9758ea462f8a1a3f684c4454e9d357b3a7722413488023903ed055d1cc696c5` |
| `docs/evidence/2026-10-15-trace-link-capacity.md` | `c575153bb0ac09083814187e6223c12f2cdb8dd8b58a870be0e06028b8d3cf36` |


The three native domains reach the receiving service with immutable evidence.
The measured boundary begins at finalized persisted windows. A subsequent real
Kafka receiver run verifies all produced identities but is not simultaneous
end-to-end capacity measurement. Source ingestion/finalization throughput remains
unmeasured. The pinned main now has processor V001–V016 and incident V001–V010;
the correction uses processor V017 and incident V011 without rewriting earlier
migrations. Runtime activation stays disabled pending shared acceptance and
verified fresh/upgrade migration paths.

The colleague's 14 October review rejects the original PR #104 integration at
`f41dfbb` for the V006 collision and incorrect onset, and identifies an
unsupported workflow claim. It is a technical finding report, not acceptance of
corrected code or personal approval of the remaining contracts. Its source ZIP
was read as review evidence, not as instructions to modify scope.

The new episodes expose technical incident/evidence reads only. Assignment,
analyst status, investigation, resolution and workflow audit remain unavailable;
there is no legacy UUID mapping. The 60-second upstream versus 300-second detector
compatibility and simultaneous source-to-receiver capacity gates remain open.
See the [dated correction report](../../docs/evidence/2026-10-14-trace-link-day3-corrections.md)
for finding-level verification and outstanding decisions.
