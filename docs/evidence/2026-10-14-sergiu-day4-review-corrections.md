# Day 4 independent review corrections

Review: [REQUEST CHANGES on PR #110](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/110#pullrequestreview-5522591498),
reviewed head `720802b8b6c9497a42e18adcb1ea9333c415b5db`, base
`f9934b5dce7c47f75771661b58592b68e4d545fa`. Corrections remain on
`feat/trace-link-day4-ml-shadow-hardening`. Shared activation stays off; no merge,
deployment, held-out scoring, migration repair or deterministic rule change is included.

## Reproduction and correction

1. **P1: headers-first stalled body exceeds the HTTP budget.** The new real-HTTP
   regression failed before the fix: a flushed `{` followed by a blocked body
   returned `MALFORMED_RESPONSE` after the server's two-second wait, instead of
   `TIMEOUT`. The client now waits on `sendAsync(..., ofString())` with the
   remaining monotonic budget, cancels unfinished exchanges and always releases
   its permit. The regression requires TIMEOUT/no response, latency below 600 ms
   (scheduling allowance around the 250 ms budget), all permits restored, and a
   successful next call while the original server handler is still blocked.
   A real-PostgreSQL worker regression commits the timeout before the server is
   released and verifies unchanged deterministic rows/state and restart idempotency.
2. **P2: cross-procedure roaming populations.** Registration, voice and SMS
   finalized-success primitives in a DATA scope were eligible in the original
   Python builder and accepted with HTTP 200. Both independent builders now
   enforce the explicit metric/procedure mapping before constructing the vector.
   Positive tests retain each of the four procedures in its own scope for both
   directions; negative API tests require HTTP 422/INVALID_REQUEST with no score.
3. **P2: snapshots outside the window or detached from freshness.** Future,
   pre-window, exclusive-end and in-window-but-observation-mismatched jitter
   snapshots were accepted and scored before the fix. Both builders now require
   `from <= snapshotAt < to` and equality with the observation instant. Positive
   tests check an in-window snapshot with its independently calculated source age.
4. **P2: incompatible or undeclared PEER baselines.** A peer with inbound/VOICE
   dimensions for outbound/DATA, an undeclared dimension-compatible peer, and a
   same-scope peer were accepted and scored before the fix. Both builders now
   validate compatible dimensions, distinct IDs and exact policy key/peerKey
   declarations, including dataset, domain, metric, unit, metric policy and
   topology. Positive tests use an explicitly supplied policy mapping and reject
   altered metric/unit/policy keys. The default shipped policy has no peer mappings.

`contracts/fixtures/trace-link/ml-adversarial-cases-v1.json` retains ten malformed
raw contexts, rejection reasons, and the vectors accepted by the vulnerable
implementation. Those `untrustedFeatures` are attack inputs captured before the
fix, not numerical correctness oracles. The original hand-calculated expectations
and scenario truth were not edited. Python red runs show ten builder failures and
ten API HTTP-200 acceptances; the Java red run fails the first adversarial context
and the independent stalled-body test. All cases run in the corrected regressions.

## Candidate correction

Strict snapshot validation exposed an error in the synthetic generator too:
it set `snapshotAt` to the exclusive window end. The corrected generator observes
at minute four and sets `observedAt` and `sourceAgeSeconds` consistently (61 seconds
at evaluation). The candidate was rebuilt with the original seeds and partitions;
held-out inputs were hashed but never scored. This updates the unapproved
pre-merge candidate, not an activated deployment or committed result.

Comparing the original Git artifacts with the rebuilt candidate shows all estimator
tree nodes/values, baseline bytes, calibration bytes and scaling arrays unchanged.
Only imputation/centering for `transport.jitter.sourceAgeSeconds` and
`roaming.partnerRtt.sourceAgeSeconds` changed from 1 to 61. MOBILE_DATA model bytes
are unchanged. Raw primitive provenance and input/split hashes reflect the correction.

New manifest SHA-256: `b7d15dc27bf383edd4f522ec2f034504a838c946968ab9e382b08227b6a08268`.

| Domain | Model SHA-256 |
| --- | --- |
| TRANSPORT | `cfe1b20d33c89eece9d49850485ef83ba358ffdb8c4c683877d9bdaae25ed077` |
| ROAMING | `522bbfdf3e3abb26be2b9183cfa4abd21af22215ec498cd0614daae40875bab4` |
| MOBILE_DATA | `f916f71f8c8c0d9871b8f72b852daad35320976b6fc9861152461ab2e15834b4` |

The Dockerfile now includes the exact baseline policy/schema and KPI definitions
required by the stricter independent Python validator. The real packaged HTTP
check verifies normal scores in all three domains and all ten adversarial rejections.

## Fresh verification

Final logs and machine-readable verification are retained under
`assets/trace-link-day4-review-20261014/`. Original Day 4 evidence remains historical.
Independent re-review remains pending; passing local tests do not constitute approval.

Verified implementation commits: `65ff49f9` (complete body deadline) and
`4ed9b5209c9d8fd1c4776ebb7a3e41482d7f821c` (feature validation/candidate correction).
The source manifest pins all 17 changed implementation/fixture/artifact files;
archived JUnit files are filtered by classes actually reported in the fresh full log.

| Check | Fresh result |
| --- | --- |
| Full Maven processor/dependencies `verify` | BUILD SUCCESS, 16:18 total |
| Processor | 1,110 tests, 0 failures/errors, 8 existing opt-in skips |
| Streaming support / event generator | 139 / 158 tests, 0 failures/errors/skips |
| Focused feature/client/worker tests | 14 tests, 0 failures/errors/skips |
| Complete ML Python suite | 91 tests, 0 failures/errors/skips |
| Root Python suite | 174 tests, 0 failures/errors/skips |
| Fresh Java/Python feature exports | 34 vectors, exact integers / at most 1e-9 floats |
| Arithmetic exports | 54 cases, Java/Python/PostgreSQL/JavaScript, maximum error 0 |
| VoLTE / SMS geographic parity | 30 / 32 cases, maximum error 0 |
| Contract validator / frozen inputs | Pass / 29 repository and external pins unchanged |
| Clean Windows CLI candidate rebuild | Byte-identical; held-out counter-seed isolation passed |
| Rebuilt container real HTTP | All three valid scopes score; ten adversarial cases reject without scores |
| Missing optional candidate container | Legacy readiness UP; HTTP 503 MODEL_UNAVAILABLE, no score |
| Real HTTP + PostgreSQL body-stall regression | TIMEOUT commits while handler remains stalled; permit released; restart retains result |

Environment: Windows, Java 25.0.4.1 (project release target 21), Python 3.13.9,
numpy 2.4.6, scikit-learn 1.9.1, joblib 1.5.3, FastAPI 0.141.1, jsonschema 4.26.0,
Docker 29.8.2, PostgreSQL 16.4-alpine. Image identity:
`sha256:079534e1bea4ed024be8dc60d3226af7b7d3b3f4f392a13ec7fc8546e7c730a4`.

No new test skip was added. The same eight live ML/replay/full-archive/frozen-V016
opt-ins remain unexecuted; exact test names and reasons are in `verification.json`
and archived JUnit reports. This correction run does not claim a separate Java 21
runtime campaign, Linux byte equality, connected capacity, deployment or Day 5
held-out model-quality evaluation. Reviewer-requested independent re-approval is pending.
