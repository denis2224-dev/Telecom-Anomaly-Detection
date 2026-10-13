# Trace Link Day 2 â€” Rusu Sergiu

Implemented the 11 October assignment as additive offline policies with one coordinator and three simultaneous implementation agents. Seasonal fitting, independent domain rules, lifecycle and guarded cause attribution are integrated. The existing one-minute VoLTE/SMS implementation, Day 1 evidence v1, V2 contracts and model files are preserved. Live registration and delivery remain Day 3.

Branch: `feat/trace-link-day2-seasonal-policies`. Day 1 base: `b4af29f1a912baf7bdecd69f1d130819f249313a`; inspected main: `9067701943acb38cc25965ff89693292fc62766c`; reviewed merge checkpoint: `99adf9ed32fc80d4bf798789d258a31e588625b0`. The final machine-readable manifest identifies the tested implementation commit and exact source hashes. Preexisting untracked work and incomplete plans were preserved.

Implementation commit: `26bbb1e5ff4b5cba77b4d704306289a7ed3561ad`.

## Delivered behavior

- Versioned baseline and rule policy configurations, strict additive contexts/results and separate raw cause-candidate contracts. No public API, migration or V2 wire change.
- Median and raw MAD per complete scope, metric policy/unit, five-minute local slot and weekday class. Python fitting uses pinned tzdata 2026.2 / IANA 2026b; Java records its actual bundled TZDB. Cross-language parity compares independently fitted cells/configuration and reports both timezone versions separately. DST-fold samples reduce to one median per local date.
- The new result envelope emits signed actual-minus-center deviations in original units, including mapped service results. Unavailable values remain null; no MAD standardization or model rank is introduced.
- Explicit DIRECT â†’ configured one-hop PEER â†’ configured STATIC â†’ BASELINE_UNAVAILABLE lookup. Empty default peer catalogue; 99% static defaults only for the five specified success rates. Valid zero centers and zero MAD remain valid.
- Independent transport loss/latency, roaming registration/data/voice and mobile EPS/DNS rules. Counter-product comparisons preserve exact boundaries. Utilization alone is a candidate; mapped service impact requires a separately breached service rule, matching evidence and authoritative dependency checks. Reduced capacity alone never identifies a failed LAG member.
- Two adjacent eligible breaches open; three eligible healthy windows recover after required backlog drainage. Gray, missing data and gaps reset progress as specified. Severity ratchets until recovery. Replay is idempotent, committed conflicts are rejected and older windows cannot rewrite state. Mapped service scope participates in identity.
- Cause results are SUPPORTED_HYPOTHESIS/MEDIUM or UNDETERMINED/null. Missing, stale, wrong-scope/time/path/topology, contradictory, ambiguous or unsupported evidence retains bounded rejection diagnostics. Unsupported output has null dependency. A cause-only change cannot alter the symptom or episode.

## Historical artifact and leakage controls

| Split | UTC interval |
|---|---|
| Training | `[2026-09-10T09:00Z, 2026-09-30T09:00Z)` |
| Validation | `[2026-09-30T09:00Z, 2026-10-05T09:00Z)` |
| Held out | `[2026-10-05T09:00Z, 2026-10-10T09:00Z)` |

The reference-only eligibility policy validates arithmetic and grid structure without inventing source freshness. Declared injection intervals are conservatively excluded across scopes, including affected endpoints; this intentionally over-excludes rather than guessing dependencies. Maintenance exclusions are empty because none were supplied. No queue/delay population is fitted, so unresolved drainage contamination is not admitted. Scenario labels, IDs and future outcomes do not enter detector contexts or estimator samples.

The artifact contains 34,347 cells from 610,560 supplied/expected training samples: 340,143 accepted, 10,494 excluded, 259,923 invalid/ineligible low-volume and zero missing. Each retained cell meets three distinct dates and 90% eligible-slot coverage; minimum observed coverage is 0.90909. Validation/held-out lookup inventory contains 172,880 DIRECT and 132,400 STATIC fallbacks. These counts describe baseline availability, not measured runtime detection accuracy.

Two independent pinned builds reproduce exact compressed and raw bytes:

- Gzip SHA-256: `767be86c7b55e22581d3d395dc12c39ac3b11d58d03d549dcbf612606c268d87`.
- Raw JSON SHA-256: `3bff7c6229a8ec26caa26d68c5c861d6d0b73e3e002b604860d9ae258b68e5d2`.

Source checksums, topology snapshot, exclusions, full estimator configuration and timezone-rule version are pinned. Training windows crossing the declared end and evaluation before training ends are rejected. Java checksum-verifies compressed bytes before parsing and validates the loaded artifact; a corruption regression and actual published-artifact lookup are included.

Diagnostics count excluded grid slots even when the source row is absent; eligible absent slots remain missing. Distinct measurement IDs cannot duplicate the same key/window. These sparse-history regressions leave the complete reference artifact unchanged.

## Reproduction

Install `requirements-dev.txt` and `services/ml-service/requirements-ml.txt` in the selected Python environment. Provide JDK 21, Node, Docker and the extracted reference pack explicitly:

```powershell
.\scripts\verify-trace-link-day2.ps1 `
  -Python '.venv/Scripts/python.exe' `
  -JavaHome 'tmp/jdk21-sergiu-days7-8/jdk-21.0.12.1+1' `
  -ReferenceRoot '../Trace_Link_Integration_Pack'
```

The entrypoint invokes the Day 1 reproduction with all required contract/root-Python/ML-Python/processor checks, legacy and geographic parity and fresh Java/Python/JavaScript/PostgreSQL arithmetic exports. PostgreSQL is disposable, has no published ports and uses `--network none`. It then verifies independent Java/Python fitting, builds the full history twice, compares both against the published artifact and validates fresh Day 2 outputs before publication. The manifest counts only fresh Surefire reports and requires all eight relevant suites.

Final committed-source verification passed on 12 October 2026:

| Check | Result |
|---|---|
| Contract checks | Passed, including 58 rule contexts and 60 cause fixtures |
| Root Python | 130 passed |
| ML Python | 64 passed |
| Java reactor | 1,201 run: 1,197 passed, four skipped, zero failures/errors |
| Java modules | 111 streaming-support, 95 event-generator, 995 processor |
| Trace Link Java suites | 38 passed across eight suites; no skips |
| Fresh arithmetic parity | 54 hand-derived cases in each of Java, Python, JavaScript and PostgreSQL; zero numeric difference |
| Legacy/geographic parity | All persisted payload fields matched for 7 voice, 12 SMS, 30 geographic voice and 32 geographic SMS cases |
| Independent baseline fitting | Two fitted cells matched exactly; both timezone-rule versions 2026b |
| Full historical reproduction | Both builds and published gzip have identical SHA-256 |
| Frozen compatibility | Fourteen Day 2 V2/model pins and the Day 1 29-file reference/pin gate unchanged |

The four skips are existing live packaged-model checks in GeographicDetectionTest,
SmsDeliveryTest and VoiceDeliveryTest because `ML_SERVICE_URL` was absent, plus
SmsShadowReplayTest because `SMS_SHADOW_REPLAY_DIR` was absent. Their live checks
were not verified in this run; all mandatory Day 2 offline checks ran.

The original command started at `2026-10-12T08:05:35.6474381Z`. It completed all
tests/parity checks and the first history build before the user interruption.
The second history build and publication resumed at
`2026-10-12T13:03:03.4404522Z`; the recorded stage-completion timestamp is
`2026-10-12T13:05:25.1613279Z`. The manifest explicitly records this resumption and
the recovered start boundary from the outer transcript creation time, with
transcript hashes. This was an interrupted/resumed reproduction of unchanged
committed source, not an uninterrupted invocation. Development logs remain under
`tmp/trace-link-day2/`.

Recorded runtimes: Python 3.13.9, tzdata 2026.2, Temurin JDK 21.0.12.1+1, Node
24.13.1, Docker server 29.5.2 and disposable `postgres:16.4-alpine`.

The first broad reproduction was rejected: processor verification ran 989 tests
with one error and four environment-gated skips. The existing
`HealthProbeTest.realHttpProbesDistinguishKafkaAvailabilityAndOutage` failed during
its database-outage assertion, before Kafka shutdown: readiness stayed UP after
CONNECT revocation and one pool eviction. The failure did not recur in isolation.
An in-flight pool refill escaping the eviction snapshot is a plausible cause,
not an established diagnosis. The fixture now asserts effective CONNECT denial
and repeats eviction within the original 15-second assertion; status checks and
deadlines are unchanged. The hardened readiness suite passed independently.

On resumption, an isolated run could not start PostgreSQL because Docker was
stopped. Docker then failed startup on stale inference/secrets Unix sockets;
preserving and renaming the socket-only directories restored startup without
resetting images, volumes or databases. That blocked attempt is recorded, not
counted as a successful verification. Earlier shell-argument parsing and package
download/DNS failures were corrected before the committed reproduction.

## Evidence inventory

All assets are under [assets/trace-link-day2](assets/trace-link-day2/):

| Artifact | Purpose |
|---|---|
| `manifest.json` | Tested revision, source/policy/artifact hashes, runtime versions, UTC execution and fresh Java test/skip details |
| `seasonal-baseline.json.gz` | Strict seasonal artifact with full configuration/provenance |
| `history-fit-report.json` | Split, accepted/excluded/invalid/missing counts, input hashes and fallback inventory |
| `fixture-matrix.json` | Independent expected results with policy/baseline versions |
| `domain-rules.json` | Actual results for 58 detection fixtures |
| `cause-controls.json` | Actual attribution/rejection results for 60 cause fixtures |
| `lifecycle.json` | Explicit state-machine trajectory, replay, escalation, unknown/gaps, gray, backlog, recovery and recurrence |
| `integrated.json` | Three complete baseline â†’ rule â†’ episode â†’ supported/unresolved cause trajectories |
| `service-transport.json` | Mapped impairment, adjacent impairment and eligible healthy service recovery examples |
| `reference-eligibility.json` | Negative eligibility fixtures preserving UNKNOWN source freshness and unavailable populations |
| `loaded-fallbacks.json` | Actual published-artifact DIRECT, STATIC and unavailable lookups |
| `frozen-hashes.json` | Fourteen unchanged frozen V2/model file hashes |
| `verification-history.json` | Rejected broad run, blocked environment attempt, focused successes and limits of the readiness-race diagnosis |

Independent review and resolutions are recorded in `tasks/trace-link-day2/review.md` and `review-coordinator.md`. Boundary probes exposed baseline bypass, snapshot timing, cause identity collisions, inconsistent dependency attachments and result-kind inconsistencies; each was corrected with focused regressions. Publication now validates prerequisites before refreshing assets and identifies the exact source revision/hashes.

Final independent publication review approved the committed-source result after
checking 105 source hashes, 12 artifact hashes, both policy hashes, all frozen
pins, both history builds, fresh exports, test totals and transcript hashes.

## Limitations and Day 3 acceptance

The reference pack is synthetic. It lacks target loss counts, RTT mean/probe counts, qualifying EPS eligibility and post-activation DNS populations. Hand-calculated fixtures prove these rules independently. The reference eligibility export is a deliberately negative fixture, not a claim of fresh historical source measurements. Historical fitting preserves UNKNOWN source freshness and does not activate runtime detection. Finalized roaming SMS remains deferred.

Live worker registration, persisted windows/replay, Kafka incident delivery, receiving-service mapping and receiving-owner acceptance are explicit Day 3 gates. Prepared handoffs for Ion, Denis, David and Stanislav are in `tasks/trace-link-day2/handoffs.md`; they have not been sent externally.
