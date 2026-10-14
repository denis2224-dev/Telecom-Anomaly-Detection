# Repository Verification Guide

This guide describes how to verify the entire **TraceLink** platform, ensuring contracts, backend services, machine learning models, and frontend applications satisfy all requirements.

---

## 1. Prerequisites

- **Java Development Kit:** JDK 21 (Temurin or OpenJDK)
- **Python Environment:** Python 3.13 with installed development dependencies:
  ```bash
  python -m pip install -r requirements-dev.txt
  python -m pip install -r services/ml-service/requirements-ml.txt
  ```
- **Node.js Environment:** Node.js 20+ (LTS) and npm:
  ```bash
  npm --prefix apps/dashboard ci
  ```
- **Docker & Compose:** Docker Desktop with Compose V2 (for containerized integration checks).

---

## 2. Test Execution Commands

### A. Contract and Schema Validation
Validates all JSON schemas (Draft 2020-12), contract fixtures, detection policies, and parity suites:
```bash
python scripts/check-contracts.py
```
*Expected Result:* 19 passing assertions validating observation schemas, parity matrices, and Trace Link definitions.

---

### B. Python Contracts and Unit Tests
Runs the root Python test suite covering observation boundaries, geographic invariants, and Trace Link policies:
```bash
python -m unittest discover -s tests -v
```
*Expected Result:* 160 unit tests pass (1 skipped on non-PowerShell environments).

---

### C. ML Service Unit and Evaluation Tests
Executes unit tests and evaluation pipelines for VoLTE and SMS machine learning inference:
```bash
python -m unittest discover -s services/ml-service/tests -v
```
*Expected Result:* 64 unit tests pass, validating model scoring, Isolation Forest thresholds, and candidate model evaluation.

---

### D. Java Streaming, Generator, and Processor Verification
Validates the Maven multi-module reactor containing `streaming-support`, `event-generator`, and `processor`:
```bash
./mvnw -B -ntp test-compile
./mvnw -B -ntp test
```
*Expected Result:* Clean compilation and test pass across all modules.

---

### E. Java Incident Service Verification
Compiles and executes test suites for the incident service backend:
```bash
./mvnw -f services/incident-service/pom.xml test-compile
./mvnw -f services/incident-service/pom.xml test
```
*Expected Result:* Build success, verifying entity mappings, controllers, migrations, and security filters.

---

### F. Frontend Dashboard Build and Unit Tests
Verifies branding synchronization, compiles the Angular 21 production bundle, and executes Vitest component specs:
```bash
npm --prefix apps/dashboard run check:branding
npm --prefix apps/dashboard run build
npm --prefix apps/dashboard test
```
*Expected Result:* Production bundle built into `apps/dashboard/dist/dashboard` (under 500 kB initial load), 35 Vitest spec files passed (181 unit tests).

---

### G. Evidence and Pinned Hash Verification
Verifies immutable evidence artifacts and pinned source file checksums:
```bash
python scripts/verify-trace-link-day6-evidence.py
```
*Expected Result:* 124 immutable evidence files match frozen rehearsal hashes and source pins.

---

## 3. GitHub Actions CI Workflows

| Workflow | Path | Triggers | Executed Checks |
|---|---|---|---|
| **Service Integration** | [`.github/workflows/integration.yml`](../.github/workflows/integration.yml) | Pull Request, Push to `main` | Geography alignment, contracts, Python unit tests, ML evaluation, processor verification. |
| **Incident Service** | [`.github/workflows/incident-service.yml`](../.github/workflows/incident-service.yml) | Changes in `services/incident-service/**` | Maven build and backend tests. |
| **Dashboard** | [`.github/workflows/dashboard.yml`](../.github/workflows/dashboard.yml) | Changes in `apps/dashboard/**` | Branding check, Angular build, Vitest specs. |
| **PR #77 Connected** | [`.github/workflows/pr77-connected.yml`](../.github/workflows/pr77-connected.yml) | Manual dispatch / PR check | Isolated runner safety checks and connected workflow tests. |
