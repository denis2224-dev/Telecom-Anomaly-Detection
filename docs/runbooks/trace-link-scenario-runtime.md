# Trace Link scenario API and local runtime v1

This repair adds **local synthetic generation**, with both feature flags off by
default. It does not activate new-domain Kafka delivery, release the V016 held
outbox, admit minute observations to the offline detector, or claim incident
creation. The approved offline 300-second path and legacy UUID routes remain
separate. Receiving-owner approvals in the existing handoffs are still pending.

## Public contract

The canonical OpenAPI defines `/api/trace-link/v1/scenarios`:

| Method / suffix | Behavior |
| --- | --- |
| GET `/catalogue` | Enabled state, five executable definitions, thirteen unavailable definitions, producer defaults and 54 synthetic scopes when enabled |
| GET `/runs` | Most recent 128 durable runs for the issuing analyst |
| POST `/runs` | Validate, reserve, commit a public UUID and command, then dispatch privately |
| GET `/runs/{uuid}` | Reconcile authoritative runtime state with the durable command |
| POST `/runs/{uuid}/stop` | Commit stop intent before attempting remote delivery |
| POST `/runs/{uuid}/reconcile` | Inspect retained interruption history; never resume incomplete execution |

Session authentication, SUPERVISOR/ADMIN roles, enabled analyst profiles and
existing CSRF protection apply. A run belongs to its issuing analyst; another
analyst receives 404. Command scopes are validated by the producer against its
pinned reference catalogue. Enabling this local runtime grants no authority to
publish reference identities into the legacy geographic pipeline.

Public run identifiers are UUIDs. V009 maps each UUID to a unique, immutable
64-character producer command fingerprint. Existing `/api/simulator` and
`/internal/scenario-runs` UUID contracts are unchanged. V008 is reserved by the
independent consumer PR #98, so the command ledger uses V009.

Example public request (all values synthetic):

```json
{
  "requestId": "7d62777b-11cc-4260-a487-83e7a7773a17",
  "type": "MOBILE_DATA_DNS_FAILURE",
  "version": "trace-link-scenarios-v1",
  "scope": {
    "domain": "MOBILE_DATA",
    "id": "chi",
    "direction": null,
    "country": null,
    "procedure": "DNS",
    "topologyVersion": "trace-link-links-v1"
  },
  "seed": 42
}
```

The server assigns the next UTC minute and a 480-second interval. A client
cannot supply a schedule. The producer alone supplies defaults when the whole
`parameters` object is omitted. If supplied, all seven parameter fields are
required. The API limits numeric seeds to 0..9007199254740991; the pure producer
CLI retains its signed-long contract. Percentage inputs convert exactly to
integer basis points with at most two decimal places. Missing-minute indices
are unique, zero-based 0..7, including recovery indices 5..7.

Intentional retries retain the exact request identity and body. Object-property
order is canonicalized; changed fields or array order under an existing request
ID conflict with 409. Same-scope half-open overlapping intervals also conflict,
including after stop. Different directions, partners and cities are independent.
Invalid commands are 400, missing private runs 404, and unavailable runtime or
disabled feature 503. A lost start response leaves the durable command intact;
retry recovers the existing UUID rather than creating a new command.

## Isolated configuration and storage

For an explicitly chosen disposable or approved local environment only:

```text
incident-service: TRACE_LINK_SCENARIOS_ENABLED=true
event-generator:  TRACE_LINK_RUNTIME_ENABLED=true
event-generator:  TRACE_LINK_RUNTIME_DIRECTORY=<absolute persistent directory>
```

Use the existing configured `GENERATOR_BASE_URL` on the private service network.
Do not publish internal generator paths through the proxy. No Compose service,
shared environment, database volume or credential was changed by this repair.

The runtime shares the existing scenario scheduler and uses an injected Clock.
One process exclusively locks its configured directory. Immutable checkpoint
generations contain exact generated observation bytes and identities; files are
flushed before atomically creating their final hard link. Incomplete temporary
artifacts are not checkpoints. A corrupt latest checkpoint fails startup.
The filesystem must support hard links and persistent storage; power-loss
behavior and distributed/network filesystems have not been certified.

Storage failure makes the runtime unavailable until restart. Restoring a
nonterminal run marks it INTERRUPTED and persists that state before accepting
commands. A late unknown command is also INTERRUPTED. Neither case replays from
minute zero. Stop and completion retain their existing history. The engine is
bounded to **128 retained runs**, including terminal runs, and checkpoints are
retained for investigation. There is no automatic pruning or deletion API.

V009 gives `incidents_app` SELECT/INSERT and column-level updates to status,
snapshot and stop intent. It cannot rewrite commands/fingerprints or delete or
truncate this history. No processing_db/keycloak_db privileges are granted.

## Interpreting the dashboard

Trace Link controls appear above the explicitly separate legacy controls.
Transport selects a link and direction; roaming selects an approved country /
synthetic partner and in/out direction; LTE selects a reference city and DNS
procedure. Controls can select any of the three implemented domains. The other
thirteen definitions are a read-only unavailable list.

Minutes 1–2, 3–5 and 6–8 describe planned normal, degraded and recovery traffic.
Normal control remains healthy throughout. DNS and EPS bearer activation remain
separate populations. High utilization establishes neither packet loss nor
customer outage. Planned recovery is never observed recovery.

Run history shows server status and completed local generation, with separately
labelled local observation history. `offered=0`, `acknowledged=0` and
`deliveryStatus=DISABLED_PENDING_REVIEW` describe this disabled transport.
Persisted and accepted counts are unavailable, not zero. Even COMPLETED proves
only local generation, not Kafka ACK, persistence, detection or recovery.
STOPPED is not recovery; INTERRUPTED requires inspection and cannot resume.

Only pending retry commands are cached in sessionStorage, scoped by analyst.
Cached browser state never determines lifecycle. Session end clears private
signals and pending retry storage. Session revisions, operation generations and
mutation generations reject stale replies. Terminal runs stop polling, and
independent active runs remain visible when another command is prepared.

## Verification and rollback

The protected `/trace-link` dashboard route independently reads approved native
history and versioned inventory. Dataset identity and available UTC bounds must
come from the dataset owner. CSV uses `/api/v2/exports/metrics.csv` and the same
query/registry as JSON series. Only CITY `voice.success_rate` is registered in
this candidate; no live producer metric alias or new-domain read mapping is
enabled by the scenario flags.

`/api/notifications` and `/{id}/read` read/update existing recipient rows under
the session and CSRF rules. They do not manufacture notification rows. The UI
reconciles durable REST history through one shared incident stream and clears
private state on session end. Automatic notification production, browser push,
Grafana and authenticated connected acceptance require the separate owner gates
in the evidence report.

See [repair evidence](../evidence/2026-10-12-david-trace-link-repair.md) for exact
source identity, commands, results, screenshots and remaining release gates.
The API integration tests use real PostgreSQL and security filters with a
controlled private-generator contract double. Runtime tests exercise the actual
engine and filesystem. Chromium tests use controlled HTTP responses. These are
separate component checks, not an authenticated connected rehearsal.

Rollback sets both new flags to false and restarts the isolated services. Keep
V009 and its durable rows, plus runtime checkpoint files. Do not downgrade or
edit applied migrations, reset volumes, remove history, change credentials or
release held outboxes. The feature-off UI exposes the existing legacy controls.
