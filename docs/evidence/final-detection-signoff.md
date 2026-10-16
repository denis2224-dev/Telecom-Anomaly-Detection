# Final detection sign-off — Sergiu Day 6

**Decision: PENDING — no shared release approval or human signature.**

The user confirmed the common release manifest and connected environment are
unavailable. Local preparation is delivered separately from release acceptance.
The Day 5 merge and automated independent review do not impersonate a human
acceptance decision. No model promotion, policy tuning or public API change occurs.

| Required criterion | Status | Evidence / disposition |
|---|---|---|
| Frozen VoLTE/SMS rules and feature order | LOCAL PASS | Detection input pins and completed processor regression |
| KPI primitives and correct units | LOCAL PASS / TRACE PENDING | 54-case four-language parity; displayed-value trace pending |
| No held-out truth leakage | LOCAL PASS | Frozen protocol, study integrity tests and unchanged-input repeated evaluation |
| Positive/negative/low-volume/missing/contradictory/concurrent controls | LOCAL PASS / CONNECTED PENDING | Component/study suites; two connected runs pending |
| ML failure preserves deterministic behavior/recovery | LOCAL PASS / CONNECTED PENDING | Failure, HTTP and worker drills; connected demonstration pending |
| Replay keeps stable identities | LOCAL PASS / CONNECTED PENDING | Processor replay regression; incident/evidence comparison pending |
| Honest cause wording | SCRIPT REVIEWED / UI PENDING | Script uses hypothesis/undetermined; connected UI review pending |
| Sample sizes/seeds/timing/hardware/failures | LOCAL PASS | Archived commands, results, JUnit inventories and environment |
| Reproducible approved artifact hashes | LOCAL PASS / RELEASE PENDING | 307 input pins and Windows model-byte check; common manifest pending |
| Two connected rehearsals | NOT RUN | Common manifest/environment not supplied |
| Human Day 5 acceptance and Sergiu attestation | PENDING | No signature generated |

The machine-readable local verification summary records which technical gates
actually passed or failed; rows above do not convert local tests into release
acceptance. The [local summary](assets/sergiu-day6/verification-summary.json)
records 200 passing repository Python tests, the completed processor suites and
all conditional skips. Three archive skips and the pre-export ML feature skip
were covered by explicit follow-ups. The SMS shadow dataset and separate legacy
V016 directory remain unsupplied. Consult [rehearsal evidence](detection-rehearsals.md) and
[receiving instructions](../runbooks/sergiu-day6.md).

## Limitations to carry into the common manifest

- Synthetic sparse data and paired seeds do not establish operator performance.
- Narrow model scope and poor Day 5 ML shadow results prohibit claims of broad
  fault recall, calibrated probabilities or readiness for autonomous incidents.
- The deterministic detector uses the shipped static demonstration fallback;
  the ML baseline is separate. Neither establishes annual seasonality.
- Component cause controls are regression evidence, not held-out causal accuracy
  or proof of physical causation. Unsupported cause stays undetermined.
- Event-time study delays are not measured deployed response times.
- Byte-identical model rebuilding is a Windows gate. Portable Linux success
  alone does not satisfy it.
- Missing external test inputs and connected evidence remain explicit gaps.
- Technical recovery and analyst closure are distinct; no UI/workflow acceptance
  is inferred from offline evaluation.

## Human attestation intake

After reviewing the final common manifest digest, both connected receipts and all
limitations, the responsible human records their name, reviewed source revision,
manifest SHA-256, date, decision and any conditions. Store that attestation
separately and reference it from the team's release record. Until then, retain
PENDING. This file is an unsigned evidence checklist, not a signature on behalf
of Rusu Sergiu or another teammate.
