# Roaming source data and incident evidence

The dashboard reads registration, data-session and voice success counters from
the existing metric API. The overview headline cards use those same readings.
Missing archives and failed reads produce unavailable values, never generated
fallbacks. Scenario and fixture previews remain explicitly labeled samples.

## Local synthetic demonstration

The full local Compose startup now enables `ROAMING_SYNTHETIC_ENABLED` and the
incident receiver (`INCIDENT_TRACE_LINK_ENABLED`) for the requested roaming demo.
Set both to `false` to disable it. Host-run deployments remain opt-in unless these
variables are supplied. This does not enable processor/new-domain source feeds.

`roaming-demo-v1` is a separate deterministic generated source, with 30 rolling
days of completed five-minute buckets, ten fictional country partners and both
directions. The existing dataset, partner and metric APIs expose it with
`synthetic: true`. For this source `archiveReady` means generated history is ready;
there is no imported CSV archive. Its manifest checksum identifies the generator
configuration, not operator source bytes. Native archives retain their own identities.

Registration, data and voice successes/attempts use independent seeded populations.
Coarser readings use ratios of summed counts. User estimates use aligned synthetic
snapshots. The 99.5% baseline is explicitly a static demo reference. Finalized
roaming SMS delivery remains unavailable; no SMS success rate is inferred.

The same generator counts build immutable, validated incident evidence. Repeated
three-hour demo cycles include two breached windows to open, continuing degradation,
and three healthy windows to recover, with registration/data outbound examples and
an independent inbound voice example. The normal persisted incident, evidence,
assignment, acknowledgement, comments, audit and resolution APIs are reused.
Replays preserve identities and analyst workflow. Historical demo replay does not
create fresh unread alerts; current synthetic transitions use normal notifications.
The source and evidence remain explicitly synthetic; causes stay undetermined.

The dedicated page follows the VoLTE/SMS heading, toolbar, metric switcher,
success/failure bars, baseline chart, KPI table and incident actions. It also retains
country tables. The shared queue includes Roaming. Bare roaming navigation follows
current completed buckets; explicit historical intervals stay fixed. Latest hour
resumes following the demo source. Missing/failed requests never switch to previews.

## Available sources

- `contracts/datasets/trace-link-md-30d-v1.manifest.json` declares the native
  five-minute archive as synthetic. Its `roaming_kpi_5m.csv` contains
  direction-specific registration, data and voice attempts/successes and
  approximate active-user snapshots. Reference partners are synthetic.
- `contracts/datasets/trace-link-live-synthetic-v1.json` describes independent
  synthetic registration attempts/successes. It provides no subscriber counts.
  The historical metric API accepts five-minute registered history, so this
  one-minute stream is not silently substituted into archive cards.
- No verified operator roaming feed is configured by the repository. A false
  synthetic flag is not evidence of operator provenance. The UI continues to
  label such sources unverified; a synthetic flag on any selected series or
  partner retains the synthetic label.

The read path is `/api/v2/datasets` → versioned `/api/v2/inventory/partners` →
`/api/v2/metrics/series`. The incident service's `MetricQueryService` and
`ReferenceMetricRollupRepository` aggregate original archive primitives and
retain row/manifest checksums. Reference quality is insufficient evidence, even
when arithmetic and bucket coverage are available. Importing or recording a
synthetic archive does not turn it into operator data.

Rates are `100 × sum(successes) / sum(attempts)` for each independent procedure
and selected partner/direction population. Missing/stale samples, zero
denominators and invalid counts do not establish a success rate. Country labels
come from inventory; partner identifiers are not parsed to infer a country.
User estimates use aligned, recent source snapshots. They are not distinct-user
totals. Missing partner snapshots make the total and population shares unavailable.
Finalized roaming SMS delivery requires terminal delivery outcomes and completion
cohorts; procedure-attempt counts alone cannot supply that KPI.

## Incident evidence

The existing processor `RoamingRule` evaluates registration, data and voice
procedures separately. Committed episodes and their ordered lifecycle evidence
are served by `/api/v2/trace-link/incidents` and `/{episodeId}/evidence`.
The roaming page now exposes this existing history through the shared drawer
and incident detail view. Evidence includes available source values, counts,
baselines/thresholds, quality, population, checksums and recovery records.
Missing fields stay unavailable and possible causes remain hypotheses.

The archive read model does not authorize incident creation. Its unknown source
quality cannot establish an incident or recovery by itself. The current
`TraceLinkDetectionEnvelope` contract explicitly requires `synthetic: true`;
the implemented drawer therefore supports committed synthetic incidents, not
verified operator incidents. The archive read path itself does not create incidents. The separate opt-in local
synthetic demonstration above persists its own synthetic lifecycle evidence.

Country/direction incident pages are paginated by the existing API. Selecting
a partner filters the returned page and labels its page coverage; it does not
claim an exhaustive partner count. The backend country filter still assumes the
reference partner-country prefix. Operator partner identifiers need an
authoritative inventory join before that backend country filter can be used.

## Operator integration requirements

A genuine source needs approved ingestion, source identity/checksums, a
registered dataset and versioned partner/country/direction inventory. It must
provide UTC measurement intervals, units, qualifying procedure definitions,
success/attempt counters, measured quality and coverage. Active users additionally
need snapshot timestamps and distinct-subscriber semantics or an explicit
estimate qualification. Operator incident delivery requires a reviewed
non-synthetic detection contract, compatible source adapters, baseline/threshold
policies and valid lifecycle evidence. These inputs are absent from this change;
the UI never manufactures replacements.

## Verification

Focused unit tests cover count-weighted arithmetic, metric/procedure separation,
source selection, synthetic provenance, aligned snapshots, missing inputs,
partial bucket bounds, evidence identity/order and session cancellation.
Controlled browser tests exercise desktop/mobile rendering, error/retry states,
headlines, filters, evidence/recovery display, keyboard focus and incident markers.
These tests do not prove an operator integration or connected backend acceptance.
