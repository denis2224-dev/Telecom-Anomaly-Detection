# Trace Link Day 4 CI Gates, Defect Coordination, and Recovery Tooling

Recorded: **13 October 2026**, Europe/Chisinau (UTC+3).  
Owner: Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
Branch: `devops/observability-capacity`

## 1. Objective and Scope

Day 4 converts the integrated Trace Link candidate into a reproducible release pipeline, provides coherent demo backup/restore automation, coordinates defect resolution across teammates, and establishes CI gates preventing configuration drift.

## 2. CI Verification Pipeline

Added the `trace-link-verification` workflow job to [`.github/workflows/integration.yml`](../../.github/workflows/integration.yml) with bounded timeout (15m):
1. **Contracts & Schemas:** `scripts/check-contracts.py` validates all 43 KPIs, 8 evidence cases, and 60 cause fixtures.
2. **Grafana Provisioning & Boundaries:** `scripts/check-trace-link-grafana` validates `trace-link-pg` datasource, 14-panel dashboard, and `trace_link_reader` security boundaries in `incidents_db`.
3. **Query Parity Matrix:** `scripts/check-trace-link-parity.py --matrix` tests 6 core parity targets across VoLTE, transport, roaming, SMS, LTE mobile data, and incidents.
4. **Compose Profiles:** `docker compose config --quiet` and `docker compose --profile observability config --quiet` validate container definitions and environment variable substitutions.
5. **Recovery & Backup Unit Tests:** `tests/test_backup_restore.py` validates backup manifest creation, dump hash integrity, and isolation boundary enforcement.

## 3. Coherent Demo Backup & Isolated Restore

Implemented tooling under `scripts/`:
- **`scripts/backup-demo`:**
  - Creates coherent, coordinated snapshots of `processing_db`, `incidents_db`, and `keycloak_db`.
  - Captures versioned non-secret configuration files (`01-create-schemas.sh`, `trace-link-views.sql`, `alerts.yml`, `.env.example`).
  - Emits cryptographic `backup-manifest.json` binding git commit, dataset ID, topology version, and SHA-256 hashes of every dump file.
  - Redacts plain secrets; configuration and database state are separated from credential delivery.
- **`scripts/restore-demo`:**
  - Verifies SHA-256 checksums of all dumps against the manifest before taking action.
  - Enforces database isolation: refuses to overwrite primary source volumes; restores exclusively into an isolated namespace via `--target-prefix` (default `isolated_`).
  - Supports `--verify-only` for non-destructive archive validation and `--dry-run` for execution planning.

### Automated Test Coverage (`tests/test_backup_restore.py`)
- `test_backup_creates_manifest_and_sha256`: PASS
- `test_restore_verify_only_succeeds`: PASS
- `test_restore_detects_tampered_dump`: PASS (`ValueError: Checksum mismatch`)
- `test_restore_refuses_overwrite_without_prefix`: PASS (`PermissionError: Refusing to restore over source database volumes`)
- `test_restore_dry_run_with_isolated_prefix`: PASS

## 4. Defect Coordination

Coordinated reproducible defect records in [`docs/evidence/rehearsal-defects.md`](rehearsal-defects.md):
- **D4-1 (Backup overwrite risk):** Mitigated by isolation prefix guard in `scripts/restore-demo`.
- **D4-2 (Corrupted dump acceptance):** Mitigated by strict manifest checksum validation.
- **D4-3 (Portability launcher failure):** Resolved by platform-aware launcher execution.

Created the demonstration runbook in [`docs/demo/run-of-show.md`](../demo/run-of-show.md) detailing cold start, login, 30-day telemetry, incident lifecycle, CSV export, Grafana deep link, and backup verification.

## 5. Day 4 Gate Exit Checklist

- [x] No unresolved critical defect lacks an owner, failing reproduction, and regression test.
- [x] CI fails on contract drift, migration mutation, wrong database grants, or secret exposure.
- [x] Isolated restore reaches a valid ready state without mutating or overwriting primary volumes.
- [x] Demonstration run-of-show establishes timed steps and expected outcomes for rehearse and sign-off.
