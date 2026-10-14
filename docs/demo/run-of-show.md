# Trace Link Demonstration Run of Show

Author: Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
Target Environment: Local Compose (`telecom.test:8080`)  
Execution Window: 13-15 October 2026

## 1. Overview and Pre-Flight Checks

This run of show defines the end-to-end execution sequence for rehearsing and demonstrating the Trace Link release candidate. It covers cold start, identity verification, 30-day multi-domain telemetry navigation, incident transitions, exact CSV export, Grafana deep linking, and isolated demo backup/restore.

### Pre-Flight Requirements
- Clean working tree at release commit (`bb2d8d1` or newer)
- Ports available: `8080` (proxy), `8081` (event-gen), `8082` (incident-service), `8083` (processor), `9094` (kafka), `3000` (grafana), `9090` (prometheus)
- Host file mapping configured: `127.0.0.1 telecom.test`
- Local `.env` initialized from `.env.example`

```bash
docker compose config --quiet
docker compose --profile observability config --quiet
python3 scripts/check-contracts.py
python3 scripts/check-trace-link-grafana
```

---

## 2. Demonstration Flow and Timeline

### Step 1: Cold Start and Database Provisioning (00:00 - 02:00)
1. Launch base and observability containers:
   ```bash
   docker compose --profile observability up -d
   ./scripts/prepare-databases
   ```
2. Confirm container health:
   ```bash
   docker compose ps
   ```
   Verify `postgres`, `keycloak`, `kafka`, and `proxy` report `healthy`.

### Step 2: Authentication and Identity (02:00 - 03:30)
1. Open browser at `http://telecom.test:8080/dashboard`.
2. Observe redirection to branded Keycloak login (`http://telecom.test:8080/auth/realms/telecom/...`).
3. Sign in with provisioned analyst credentials (`analyst` / password).
4. Verify session cookie is set (`SESSION_COOKIE_SECURE=false` for local HTTP, CSRF token active).

### Step 3: 30-Day Multi-Domain Telemetry Inspection (03:30 - 07:00)
1. **Time Range & Resolution:**
   - Select 30-day view (`from=2026-09-10T09:00:00Z` to `to=2026-10-10T09:00:00Z`).
   - Toggle resolution between `5m` (8,640 buckets), `15m`, `1h`, and `6h`.
2. **City Geography:**
   - Verify catalogue presents all 10 approved cities: Chișinău, Bălți, Cahul, Ungheni, Orhei, Soroca, Comrat, Edineț, Hîncești, and Căușeni.
   - Confirm Rîbnița and Tiraspol are excluded from active selection.
3. **Intercity Transport Links:**
   - Select link `chi-hin:a-to-b` (Chișinău - Hîncești).
   - Verify throughput gauge vs nominal/effective capacity (20 Gbps).
   - Read latency, jitter, packet loss, and queue drops.
4. **Roaming Procedures:**
   - Switch to Roaming domain, select country `RO` (Romania), direction `outbound`.
   - Inspect registration, data, and voice success ratios.
5. **Mobile Data (LTE):**
   - Verify EPS bearer activation success rate and DNS query latency series.

### Step 4: Incident Lifecycle & Analyst Workflow (07:00 - 10:00)
1. Trigger or navigate to ongoing incident (e.g. transport impairment on `chi-hin`).
2. Observe incident card displaying:
   - Status: `ONGOING` / `OPEN`
   - Severity: `CRITICAL`
   - Cause: `SUPPORTED_HYPOTHESIS` (Mapped transport impairment)
   - Immutable evidence timeline.
3. Assign incident to active analyst, add investigation note.
4. Observe recovery transition upon three consecutive healthy 5-minute windows:
   - Technical state transitions to `RECOVERED`.
   - Analyst workflow remains `INVESTIGATING` (**Awaiting analyst resolution**).
5. Resolve incident with analyst closing note.

### Step 5: Parity, CSV Export & Grafana Deep Link (10:00 - 12:00)
1. Click **Export CSV** on active metrics series.
2. Confirm exported rows match exact visible bucket timestamps and decimal values without chart downsampling.
3. Click **View in Grafana** link:
   - Opens `http://telecom.test:8080/grafana/d/trace-link-overview`.
   - Variables (`city`, `resolution`, `direction`, time bounds) propagate cleanly.
   - All 14 panels populate via read-only `trace_link_reader` role.

### Step 6: Backup & Isolated Restore Verification (12:00 - 14:00)
1. Execute coherent demo backup:
   ```bash
   ./scripts/backup-demo --output-dir backups/demo-run-1
   ```
   Verify `backup-manifest.json` and dump checksums.
2. Verify backup integrity without touching running volumes:
   ```bash
   ./scripts/restore-demo --backup-dir backups/demo-run-1 --verify-only
   ```
3. Test dry-run restore into isolated database prefix:
   ```bash
   ./scripts/restore-demo --backup-dir backups/demo-run-1 --target-prefix isolated_ --dry-run
   ```

### Step 7: Teardown & Clean Exit (14:00 - 15:00)
1. Sign out of dashboard and verify session cleanup.
2. Stop demo stack preserving persistent data volumes:
   ```bash
   docker compose --profile observability stop
   ```
