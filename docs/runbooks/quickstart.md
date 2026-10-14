# Trace Link Quickstart Guide

Author: Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
Target: Developer Workstation / Second-Machine Evaluation  
Window: 14-15 October 2026

## 1. Prerequisites and Hostname Mapping

Before starting the Trace Link application stack, ensure the following tools are installed:
- Docker Engine 24+ and Docker Compose v2
- Java 21 (Temurin recommended)
- Python 3.13 with `pip`
- Node 24 with `npm`

Add the canonical local development hostname to `/etc/hosts`:
```bash
echo "127.0.0.1 telecom.test" | sudo tee -a /etc/hosts
```

## 2. Environment and Secret Configuration

Initialize the local configuration from `.env.example`:
```bash
cp .env.example .env
```
Generate secure random passwords for PostgreSQL databases and Keycloak:
```bash
# Example generator using openssl:
sed -i '' "s/replace-with-secure-password/$(openssl rand -hex 16)/g" .env
```
Confirm that no actual secret is committed to Git or exported in public artifacts.

## 3. Database Initialization & Stack Startup

1. Provision database schemas, isolated roles, and least-privilege readers:
   ```bash
   ./scripts/prepare-databases
   ```
   This creates:
   - `processing_db` with `app` schema owned by `processing_migrator`
   - `incidents_db` with `app` schema and `reporting` schema owned by `incidents_migrator`
   - `keycloak_db` owned by `keycloak_app`
   - `trace_link_reader` login role granted `USAGE, SELECT` on `reporting` schema only.

2. Launch core services and observability profile:
   ```bash
   docker compose --profile observability up -d
   ```

3. Confirm all containers reach healthy state:
   ```bash
   docker compose ps
   ./scripts/verify
   ```

## 4. Authentication and Dashboard Navigation

1. Navigate to `http://telecom.test:8080/dashboard`.
2. The proxy routes you to the branded Keycloak login interface.
3. Authenticate using provisioned analyst credentials:
   - Username: `analyst`
   - Password: configured in `.env` (default seed: `analyst_dev_secret`)
4. Upon successful sign-in, you are redirected to the Trace Link operational workspace.

## 5. Multi-Domain Telemetry and 30-Day History

1. Select the 30-day synthetic dataset (`trace-link-md-30d-v1`):
   - Interval: `2026-09-10T09:00:00Z` to `2026-10-10T09:00:00Z`
   - Native 5-minute resolution (8,640 buckets per series)
2. Explore across domains:
   - **VoLTE:** Call setup success rate, call drop rate, and MOS histogram.
   - **Transport:** 12 intercity links (e.g. `chi-hin`, `chi-orh`), throughput vs capacity, loss, RTT.
   - **Roaming:** Outbound and inbound procedures for partner networks (e.g. country `RO`).
   - **Mobile Data:** EPS bearer activation and DNS query latency.
   - **SMS Delivery:** Queue conservation and delivery ratios.

## 6. Grafana Reporting Dashboard

1. Navigate to `http://telecom.test:8080/grafana/d/trace-link-overview`.
2. Login with Grafana credentials (`admin` / `admin`).
3. Verify all 14 panels populate via the `trace-link-pg` datasource.
4. Verify variable controls (`city`, `country`, `direction`, `resolution`, `dataset`) update panels synchronously.

## 7. Restart and Persistence Verification

1. Stop the stack:
   ```bash
   docker compose --profile observability stop
   ```
2. Restart the stack without destroying volumes:
   ```bash
   docker compose --profile observability up -d
   ```
3. Re-open `http://telecom.test:8080/dashboard` and confirm 30-day history and incident states persist intact.
