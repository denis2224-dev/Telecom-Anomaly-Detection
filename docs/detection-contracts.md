# Detection contracts: Sergiu days 1–4

The current observation contract is authoritative: `sip503Count`, `deliveryDelayMs`
and `oldestPendingAgeSeconds` retain their names, units, source IDs and validators.
The archive's older raw-field names are not accepted as aliases. Feature names
remain those in `contracts/features/feature-order-v2.json`; in particular the SMS
feature `oldestPendingAgeSec` is calculated from `oldestPendingAgeSeconds`.

## Frozen definitions (day 1)

`contracts/policies/service-rules-v2.json` is the single threshold source.
These are synthetic teaching thresholds, not operator standards.

| Decision | Exact condition |
| --- | --- |
| Voice eligible | COMPLETE voice interval, available CSSR baseline, >=100 eligible attempts |
| Voice breach | baseline CSSR - observed CSSR >1.0 percentage point |
| Voice recovery candidate | same eligibility, drop <=0.5 percentage point |
| Voice severity on breach | MEDIUM; HIGH at >=50 extra failures; CRITICAL at >=200 |
| SMS delay breach | >=30 completed samples AND p95 >20,000 ms AND p95 >3 times baseline |
| SMS backlog breach | fresh queue >=100 AND oldest pending age >60 seconds; OR with delay branch |
| SMS recovery candidate | fresh oldest age <=30 seconds AND (>=30 samples with p95 <=10,000 ms and <=2 times baseline OR empty queue and zero delivered samples) |
| SMS severity | MEDIUM; HIGH for delayed >=100 delivered messages or old backlog; CRITICAL for fresh queue >=1,000 AND oldest age >=300 seconds |
| Episode opening / recovery | two consecutive eligible breaches / three consecutive healthy complete windows |

SMS runtime rules and episode state are later assignments. Missing evidence resets
pending streaks and cannot prove recovery; active episodes become UNKNOWN. The gray
zone between trigger and recovery resets recovery streaks and keeps the episode.
The policy's future heartbeat interval does not change current heartbeat payloads.

Each success rate uses its own denominator. Voice eligible attempts exclude user
outcomes. Deltas are observed minus baseline in percentage points; extra failures
are `max(0, baseline - observed) * eligibleAttempts / 100`. These are attempts,
not people. SMS p95 uses sorted index `ceil(0.95*n)-1`, never averaged percentiles.
An old queue is independent of completed-message sample availability.

VOLTE order: CSSR delta, SIP 503/eligible attempts, RRC delta, bearer delta,
TRANSPORT packet loss, IMS CPU. SMS order: p95/baseline p95, p95 milliseconds,
SMSC queue depth, oldest age seconds, delivery-attempt SR delta, completed messages.
Missing required inputs produce empty vectors, never fabricated zero values.
Zero denominators give null KPIs. A nonpositive SMS delay baseline makes its ratio
unavailable. IDs, labels and seeds are excluded from model features.

Severity represents estimated impact. Anomaly rank is calibrated model strength,
not probability or severity. Cause confidence describes supporting evidence, not
proof. Aggregate data cannot supply unique subscribers: that value stays null.

## Interfaces and later handoff

Canonical feature and detection JSON Schemas preserve the existing public API
fields and add the conditional feature-order/eligibility checks described there.
Historical evidence carries policy, baseline and topology versions. A missing
baseline lookup still carries the loaded baseline catalogue version; lookup status
is internal context, not a new public API field.

Correlation key is SHA-256 of compact UTF-8 JSON `[service,scopeId,anomalyType,rulesetVersion]`.
Episode ID hashes `[correlationKey,firstBreachedWindowStart]`; detection ID hashes
`[episodeId,windowStart,phase,rulesetVersion]`, with ASCII escapes and no spaces.
Day 5 owns persistence, monotonically increasing sequence, retry safety and OPEN
emission. Illustrative payloads are fixtures, not evidence of a live incident.

## Initial inventory and acceptance

Starting commit: `93fc17f`. Existing: source schemas/topology, Python and Java
observation validation, service scaffolds/health probes, incident API and fixtures.
Missing at start: ML implementation, feature/policy/baseline schemas and data,
baseline registry and voice rule. Preserve existing fixtures and teammate code.

Ion: feature-order and raw/expected parity acceptance **pending**. Denis: rule and
episode/evidence contract acceptance **pending**. David: explanation review
**pending**. No teammate approval or G0 team sign-off is implied by passing tests.

## Baseline lookup (day 3)

The catalogue explicitly lists each covered UTC hour for each scope. Monday 00:00
is 0; Sunday 23:00 is 167. Both demo scopes cover all 168 hours with fixed reference
values; there is no claim of four-week estimation. Voice references are 99.3% CSSR,
99.5% RRC and 99% bearer SR. SMS references are 2,000 ms p95 and 99% attempt SR.

`BaselineRegistry.lookup(scopeId, windowStart)` returns DIRECT, PEER or
BASELINE_MISSING, plus requested scope, source scope, service, UTC hour, catalogue
version and immutable values. Direct coverage wins. Only explicitly listed peers
of the same registered service may supply that same hour, and lookup follows one
hop. Missing coverage is not zero. Unknown scopes, overlapping coverage, invalid
values and cross-service peers are rejected. The current catalogue has no peers.

Processor startup loads the policy and baseline catalogue from packaged resources
and rejects malformed or unsupported configuration. Changes require an intentional
version/configuration update and restart, not mutable runtime defaults. No new
public API fields or endpoints are introduced. The Python builder takes the
resolved lookup as input; its interface is documented in the ML module README.

## Voice evaluation (day 4)

`VoiceSetupRule.evaluate(featureWindow)` resolves the matching scope/hour baseline
and returns an immutable evaluation with status, breach, optional severity, CSSR
drop, impact, versions, evidence and ML availability. It checks the canonical
feature schema, exact minute alignment, scope/version compatibility, unique KPI
names, finite values and CSSR numerator/denominator consistency. Counts are bounded
nonnegative integers. Node evidence is optional for this deterministic rule.

A complete evaluable window returns EVALUATED; low volume or incomplete/absent
voice metrics return INSUFFICIENT_DATA; absent coverage returns BASELINE_MISSING.
Unavailable evaluations have no calculated impact or severity and are not healthy
verdicts. Nonbreaching evaluated windows have no severity. Breach comparisons use
exact products before division, and impact is computed before display rounding.
The rule does not assign phase, IDs or sequence and does not publish any messages.

For 940 successes / 1,000 eligible attempts, observed CSSR is 94%, expected is 99.3%,
drop is 5.3 points and estimated extra failures are 53: HIGH. Available RRC/bearer,
SIP 503, transport and IMS measurements are retained as supporting evidence. The
cause remains undetermined with LOW confidence; richer diagnosis is day 6. ML is
UNAVAILABLE for a usable vector, INSUFFICIENT_DATA otherwise; rank/model version
and unique subscribers remain null.

`voice-open-illustrative-v2.json` is an API/schema-valid illustration for Denis,
using the worked feature values and a hypothetical first breach at 07:59 UTC.
Its OPEN at the 08:00 window and sequence 1 illustrate day-5 episode semantics;
no episode was actually opened or persisted by this implementation. The existing
incident fixture is preserved as historical reference rather than silently rewritten.
