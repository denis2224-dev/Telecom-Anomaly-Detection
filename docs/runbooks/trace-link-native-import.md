# Trace Link native source import

Run this job explicitly against an approved processing database. The normal
processor does not import an archive. The standalone configuration has no
component scan, Kafka auto-configuration, detector, scheduler, or live listener.
Existing demo containers, accounts, data, and volumes need no reset.

The reference directory must contain `data/manifest.json` and the seven files it
names. Use the supplied files without regeneration. Their SHA-256 hashes, byte
counts, row counts, headers, scope membership, native bounds, counters and
histogram cohorts are checked before source inserts. Files are copied into a
private temporary snapshot before import; later changes to the supplied files
cannot change an in-progress batch. Temporary snapshot files are removed at exit.

Build from the repository root using Java 21:

```powershell
./mvnw.cmd --batch-mode --no-transfer-progress -pl services/processor -am package -DskipTests
```

If the local wrapper cannot start, use installed Maven 3.9.16 with the same goals.
Use the existing provisioned runtime and migrator environment variables:
`PROCESSING_DB_URL`, `PROCESSING_DB_USER`, `PROCESSING_DB_PASSWORD`,
`PROCESSING_MIGRATOR_USER`, `PROCESSING_MIGRATOR_PASSWORD`. Keep their values
opaque; never put them in command arguments, reports or shell history.

```powershell
$previousTraceMode = $env:TRACE_LINK_BOOTSTRAP_MODE
try {
    $env:TRACE_LINK_BOOTSTRAP_MODE = 'true'
    java -jar services/processor/target/processor-0.3.0-SNAPSHOT.jar `
      --telecom.trace-link.import-directory=C:/OrangeSystems/task/Trace_Link_Integration_Pack/data `
      --telecom.trace-link.batch-size=500
    if ($LASTEXITCODE -ne 0) { throw 'Trace Link import failed; inspect the error and checkpoint' }
} finally {
    $env:TRACE_LINK_BOOTSTRAP_MODE = $previousTraceMode
}
```

Do not enable `HISTORY_BOOTSTRAP_MODE` at the same time. Flyway uses the existing
migrator role; the job itself uses `processing_app` and cannot create objects.
Batch size is bounded to 1–2,000 rows. The job prints safe dataset/hash/count
progress, not measurements or credential values.

The immutable manifest selects the job identity. Exact retry returns the same
completion timestamp and count. Interruption leaves only committed batches, with
rows and checkpoint in the same transaction. Rerun the same command and files to
resume. A different manifest under an existing dataset ID fails. Never fix a
conflict by deleting source rows or resetting the database; investigate provenance
and assign a new approved dataset identity when inputs intentionally change.

Inspect progress with the runtime role in **processing_db**:

```sql
SELECT dataset_id, file_name, rows_imported, updated_at
FROM app.trace_source_import_checkpoint
ORDER BY dataset_id, file_name;

SELECT dataset_id, manifest_sha256, native_rows, completed_at
FROM app.trace_source_import_completion;

SELECT source_kind, count(*)
FROM app.trace_source_native_window
WHERE dataset_id = 'trace-link-md-30d-v1'
GROUP BY source_kind;

SELECT window_start, window_end, payload, source_row_sha256
FROM app.trace_source_native_window
WHERE dataset_id = 'trace-link-md-30d-v1'
  AND source_kind = 'LINK' AND scope_id = 'chi-orh' AND direction = 'a-to-b'
  AND window_start >= '2026-09-10T09:00:00Z'::timestamptz
  AND window_start < '2026-09-10T09:05:00Z'::timestamptz;
```

A completed reference import has 86,400 CITY, 103,680 LINK and 172,800 ROAMING
rows. The 18 scenario truth rows are checksum/count verified only; they are not
persisted in detector-accessible tables. Archive rows retain 300-second windows.
Raw row checksums cover each UTF-8 CSV record without its line terminator.
Parsed payloads preserve the actual supplied columns, including histogram bins.
Source `quality` and `coverage` were not supplied and remain null. Revision zero
and finalized=true describe the frozen source snapshot under
`trace-link-native-source-v1`, not a live finalizer or detector acceptance result.

Both directions exist in the inventory/schema. Only a-to-b measurements are
imported; no reverse measurements are generated. City references remain lowercase
in `trace-link-reference-v1`, with an explicit `contract_city_id` binding. This
binding grants no legacy runtime source authority.

Run bounded integration checks without requiring the external archive:

```powershell
./mvnw.cmd -pl services/processor -am test `
  '-Dtest=DomainObservationValidatorTest,TraceLinkDatasetBootstrapIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

Select the supplied full archive explicitly for its disposable PostgreSQL test:

```powershell
./mvnw.cmd -pl services/processor -am test `
  '-Dtest=TraceLinkDatasetBootstrapIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DtraceLink.referenceRoot=C:/OrangeSystems/task/Trace_Link_Integration_Pack'
```

The checked-in fixture is one unchanged native slot (42 rows) under
`trace-link-bounded-fixture-v1`. It never reports a complete 30-day import. The
full-archive test writes `services/processor/target/trace-link-native-import-result.json`.

Backend projection, authenticated reads, metric rollups, catalogue activation and
receiving-owner reproduction remain separate gates. In particular,
`voice.success_rate` from PR #90 has no approved automatic mapping to
`volte.setup.success`; neither name is emitted by this source-import job.

## Incident-owned authenticated history projection

The processing import above remains separate from the dashboard read model. After additive migrations through V015, validate and project the same full pinned archive into `incidents_db`:

```sh
python3 scripts/import-trace-link-reference.py /approved/reference/data --validate-only
python3 scripts/import-trace-link-reference.py /approved/reference/data
```

Use a standard PostgreSQL client and existing `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and protected `PGPASSFILE` configuration. Do not put passwords in arguments or reports. The second command takes an advisory lock and imports all 362,880 native rows in one transaction. Exact repeat is idempotent; dataset, catalogue, source identity and payload conflicts abort. The job cannot activate the catalogue or ingest scenario ground truth. The seven supplied files must match the committed manifest; the bounded native slice is rejected before connecting.

`app.trace_reference_import` is the completion marker. Authenticated `GET /api/v2/datasets` reports `archiveReady` only after completion. The generic metric API reads this immutable projection. No full-source execution is claimed while the required archive is absent.
