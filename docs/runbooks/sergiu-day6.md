# Sergiu Day 6 verification and receiving handoff

The detection freeze is a contribution to Stanislav's release manifest, not a
replacement. The verifier checks integrity and receipt completeness; the receiving
reviewer must inspect the actual API/database/browser evidence. It cannot prove
that a receipt author ran a browser or grant human acceptance.

The common manifest must include `detectionInputs`, the exact path-to-SHA-256 map
from the detection freeze's `files`. This additive release-metadata field binds
both rehearsals to the detector artifacts without a circular manifest hash.
Before either run, also freeze `detectionRehearsal.startingStateSha256` and
`detectionRehearsal.expectedOutcomes` in that common manifest. Receipts cannot
substitute their own expected outcomes or restore identity after observing results.

## Local verification

Use a clean LF checkout and the pinned Python 3.13.9 dependencies, Java 21 and
Docker prerequisites in `detection-and-models.md`. All output paths must be new.
Use `feat/trace-link-day6-detection-signoff` for these new commands. The Day 5
runbook's PR #111 checkout reproduces historical Day 5 work and lacks this tool.
From the repository root, with the environment's Python executable:

```powershell
python -m unittest discover -s tests -p test_sergiu_day6.py -v
python scripts/verify-trace-link-day5.py --output tmp/day6-full-new --reference-root '<approved-reference-pack>'
pwsh -File scripts/verify-trace-link-day1.ps1 -Python '<python-executable>' -JavaHome '<Java-21-home>' -ReferenceRoot '<approved-reference-pack>' -Focused
python services/ml-service/training/generate_history.py
python services/ml-service/training/evaluate.py --output tmp/day6-legacy-new.json
python scripts/verify-sergiu-day6.py --freeze --output tmp/day6-detection-inputs-new.json
```

The Day 1 driver creates its fixed `tmp/trace-link-day1-reproduction` directory;
use a fresh checkout for an independent repeat and archive that output afterward.
The generated legacy training history is evaluation input, not model retraining.
Run these checks sequentially. Scoring enforces the existing 250 ms response
bound, including during evaluation; preserve any over-limit failure and diagnose
it without changing the bound or selecting different inputs. Compare the new
freeze's `files` map with the committed detection freeze; timestamps and source
revision metadata describe the new execution and need not be byte-identical.
Never overwrite the committed freeze to conceal changed artifacts.
Record all commands, versions, elapsed times and exits. Keep the original failed
attempts. Inspect JUnit/Python skips; exit zero does not close missing prerequisites.
Enable native archive tests with their documented reference-root properties and
frozen V016 compatibility only with its exact approved SQL/hash. Never invent an
external SMS shadow dataset or ML endpoint to eliminate a skip.

For byte-equivalent reproduction of this published evaluation, extract `study/`
from `docs/evidence/assets/sergiu-day6/initial-verification.zip` into a fresh
directory. Use the fresh-Java-export and study-only evaluation commands in
`detection-and-models.md` against that extracted study and its protocol. Generate
your own processor export; the ZIP's export is a comparison reference. Generating
a new study on a later Git revision records new provenance and can change report
hashes even when predictions agree.

## Connected prerequisites and owners

| Input | Owner | Required evidence |
|---|---|---|
| Common release manifest and isolated restored environment | Stanislav | Source/images/config/migrations/dataset/topology/seeds plus detection-input pins |
| Matching source scenarios | Ion | Approved namespace, duration, seed, primitives, expected windows and controls |
| Persisted linkage | Denis | Source/detection/episode/incident evidence IDs and authenticated API responses |
| Display inventory | David | Every value used in the script, queries, units, screenshots, browser/viewport |
| Human acceptance | Sergiu and responsible Day 5 reviewer | Actual identity, reviewed revision, date and decision |

Keep machine and human receipts separate. Do not rewrite archived Day 5 manifests
to turn PENDING into approval. Do not send handoff messages without authorization.

## Receipt format

Store run receipts and raw evidence under a new repository evidence directory.
All file keys below are repository-relative, forward-slash paths with SHA-256
values. Never include credentials, sessions or personal subscriber data.

Each run JSON contains:

- `runId`: distinct execution identifier; `mode`: `CONNECTED`.
- `releaseSha256`: hash of the identical common manifest supplied to the verifier.
- `startingStateSha256`: identical approved restore/configuration/seed identity.
- `startedAt`, `finishedAt`: actual timezone-qualified execution times; run two
  starts after run one finishes. `latencyMs`: finite measured wall-clock duration.
- `commands`: nonempty list of actual `argv` arrays and `exitCode` values.
- `files`: nonempty path-to-hash map for raw command logs, queries, responses,
  screenshots, restore receipt and expected/actual scenario results.
- `gates`: `positive`, `controls`, `concurrent`, `lifecycle`, `ml_failure`,
  `restart_replay`, `display_trace`, `analyst_separation`, `full_verification`.
  Each value has `status` and `evidenceFile` naming a pinned raw receipt.
  Mark PASS only after inspecting corresponding pinned evidence. Include every
  control and lifecycle step in the actual scenario results, not just a label.
- `traces`: rows with `displayId` (at least TRANSPORT, ROAMING, MOBILE_DATA, VOLTE,
  SMS), `datasetId`, `windowId`, `primitive`, `unit`, `baselineVersion`,
  `policyVersion`, `detectionId`, `incidentEvidenceId`, and `evidenceFile` naming
  a pinned file. Use both IDs null plus `absenceReason` for a healthy/insufficient
  case. Represent an unavailable baseline explicitly, not as invented zero.
- `outcomes`: `expected` and `actual` objects using exactly the schema below.
  The common manifest's `detectionRehearsal.expectedOutcomes` uses the same schema.
  All three locations are validated before comparison. Expected equals actual in
  each run and both runs match. Freeze expected results before executing either
  run. Keep actual timestamps/latencies outside this comparison object.

Each outcome object has exactly these six fields:

| Field | Required value |
|---|---|
| `counts` | Object containing exactly `episodes`, `detections`, `duplicates`; each is a nonnegative integer, never a boolean or floating-point value |
| `episodeIds` | Array of unique, nonblank strings; length equals `counts.episodes` |
| `detectionIds` | Array of unique, nonblank strings; length equals `counts.detections` |
| `controls` | Nonempty object mapping named control scenarios to nonblank decision strings |
| `recovery` | Nonempty object mapping named recovery scenarios to nonblank result strings |
| `replay` | Nonempty object mapping named replay scenarios to nonblank result strings |

Counts describe distinct episode/detection identities; `duplicates` separately
counts duplicate emissions. Include every approved scenario in the three decision
maps, using the actual deterministic result (for example `NO_DETECTION`,
`TECHNICALLY_RECOVERED`, `IDENTITIES_UNCHANGED`), not a generic suite PASS.
The reviewer still checks scenario coverage and result meaning against pinned raw
evidence. Schema validation does not establish authorship or prove execution.

After PR #116's review corrections, use the new detection freeze below. The
original `detection-inputs.json` remains immutable historical evidence and pins
the earlier verifier/tests; do not use it for the repaired candidate.

Run the final structural and hash check:

```powershell
python scripts/verify-sergiu-day6.py --manifest docs/evidence/assets/sergiu-day6/detection-inputs-after-pr116-review.json --release docs/release/trace-link-release-manifest.json --run '<run-1.json>' --run '<run-2.json>' --output tmp/day6-connected-verification-new.json
```

Missing/changed artifacts, incomplete runs and failed gates yield INCOMPLETE and
a nonzero exit. Output is never overwritten. Technical PASS always retains
`humanAcceptance=PENDING` and `sharedReleaseApproval=false`. A real human records
their attestation separately against the common manifest digest after reviewing
all evidence. The manifest must not include its own hash or a circular signature.
