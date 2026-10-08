# Geographic completion and connected acceptance — 8 October 2026

## Scope and ownership

This record covers the integrated first-four-day geographic implementation on `feature/geographic-completion`, published for review as PR #74. It incorporates the original G3/G4 local merges, shared `main` at `cfd119c`, and Ion's PR #73 history work at `05f4f08`. The completion commits correct Rusu's two P2 findings and connect the dashboard to the new geographic APIs. The task owner accepted `geographic-priority-v1` without attributing policy approval to Rusu.

Rusu's finding on public scenario validation was reproduced at the pre-fix revision: a city row marked `ACTIVE` could be scheduled before `effectiveFrom`, while the private generator rejected it. The public command now validates at its calculated scheduled minute before durable reservation. Exact `requestId` retry/body conflict, legacy scopes, service targeting, same-scope overlap, and start/stop/reconcile behavior retain their existing tests. The OpenAPI nullable enum fix covers metric, location, city reason and impact unit responses.

The UNKNOWN impact-origin correction resolves a matching earlier immutable detection and summary window before returning source fields; otherwise those fields are null. This avoids claiming that historical impact came from the current UNKNOWN window. Ion's processor migration and bootstrap establish one owner for geographic history and service-specific seed variation. The dashboard uses the server priority order and filters, active city scopes, versioned topology, and lazy incident/scenario routes.

## Automated verification

| Gate | Result |
| --- | --- |
| Root Java `./mvnw verify` | 899 reported, 895 executed, 4 optional skips; zero failures/errors |
| Incident service clean verify | 205 reported, 204 executed, 1 optional skip; zero failures/errors |
| Docker-backed `ScenarioControllerIT` | 13 passed, including pre-activation rejection, activation boundary, retry, overlap and stop semantics |
| Dashboard unit suite and production build | 116 passed; 451.11 kB initial bundle, within budget |
| Python unit tests | 57 passed |
| Contract and deployment checks | Canonical OpenAPI copy equal, contract checker passed, Compose geography alignment passed, `scripts/verify` passed on rebuilt local stack |
| Browser fixture regression | Four affected specs: 28 passed, 3 optional fixture skips; controlled priority and topology routes match the new API |

## Authenticated local acceptance

The opt-in `scripts/day3-geographic-live.cjs` check used a disposable SUPERVISOR identity on the rebuilt local Compose stack. Its password and session stayed in temporary files/browser memory. The identity was removed after the run. The saved [response record](assets/geographic-completion/live.json), [catalogue](assets/geographic-completion/catalogue.json), [VoLTE incident display](assets/geographic-completion/VOLTE-MD-CHI-incident.png) and [SMS incident display](assets/geographic-completion/SMS-MD-CHI-incident.png) contain no credential, cookie, authorization, session, or CSRF fields.

| Check | Observed result |
| --- | --- |
| Protected catalogue | 10 cities and 20 initial city/service history reads |
| Command and exact retry | VoLTE overload at `VOLTE-MD-CHI`, SMS queue delay at `SMS-MD-CHI`, normal control at `VOLTE-MD-BAL`, and telemetry gap at `SMS-MD-CAH`; all retries returned the original run and scheduled start |
| Runtime | All four scenarios reached `COMPLETED`; 19 status snapshots, no stopped or failed run |
| Fault evidence | Exactly one incident per fault scope, each with `OPEN`, three `UPDATE`s, and `RECOVERY` in sequence; episode, detection IDs, scope, time window, and impact assertions passed |
| Negative controls | No matching incident in either control scope |
| Bounded geography history | 20 city/service histories returned at least eight points each, with no further page |
| Browser | Both fault incident evidence screens rendered; no browser page errors and no script blockers |

The script's `acceptance` field retains its cautious `SCENARIO_TIMELINES_PASSED_RECEIPT_AND_DISPLAY_REVIEW_PENDING` label. The technical checks above passed locally. A separate teammate signoff was not required by the task owner; this record does not claim one. The dashboard landing screenshot was excluded because it was captured during loading. The script now waits for the overview and city markers before saving that screenshot in future runs.

## Remaining planned scope

Auxiliary power/cause correlation remains `PLANNED`: the authoritative G2 publisher, schema, and pure correlation contract were not supplied. No power conclusion was fabricated. This does not affect the mandatory geographic, evidence, command, or dashboard behavior recorded here.
