# Day 06 — real login and voice investigation

Day 05 supplies the graph and incident cards. Day 06 connects generated
measurements to the real backend, then proves that login, graph, incident evidence
and replay work together. The measurements are synthetic telecom data; the login,
processing, database records and API responses are real.

## Open the finished work

1. Open Docker Desktop and wait for it to start.
2. In Terminal, select the shared Day 05–06 branch and build the frontend:

   ```bash
   cd ~/Documents/PT/Telecom-Anomaly-Detection
   git switch feature/voice-kpi-incident-investigation
   npm --prefix apps/dashboard run build
   ./scripts/up
   ```

3. Start Spring in a second terminal and leave that terminal running:

   ```bash
   cd ~/Documents/PT/Telecom-Anomaly-Detection/services/incident-service
   ./mvnw clean spring-boot:run -Dspring-boot.run.arguments=--debug=false
   ```

   If port 8082 is already in use, the service may already be running. Check it
   with `lsof -nP -iTCP:8082 -sTCP:LISTEN`; do not start another copy.

4. Open <http://telecom.test:8080/login>. Click **Continue to sign in** and use your
   existing Keycloak account. It needs the ANALYST role and a local analyst profile.
   If needed, run from the repository root, replacing the placeholders:

   ```bash
   ./scripts/provision-analyst --username YOUR_USERNAME --display-name "Your Name"
   ```

5. Select **VoLTE setup** / `VOLTE-MD-CENTRAL`. Open **incident detail** below the
   graph. The verified local incident is also at:
   <http://telecom.test:8080/incidents/5d491dcd-86ec-4ae1-b71a-a3ec8eda0041>.
   This UUID belongs to the verified local database; another database creates its
   own incident UUID, so use the service card there.

6. To view the verified trend, set **From (UTC)** to `2026-09-23 08:36` and
   **To (UTC)** to `2026-09-23 08:44`, then click **Apply time range**.
   Old observations correctly show STALE; that does not erase their history.

## Generate or replay the demonstration

From the repository root:

```bash
bash scripts/voice-scenario 2026-09-23T08:36:00Z
```

This exact command reproduces the verified interval. Repeat it to replay the
same observations; it must not create another incident. To create a new incident,
choose a later, non-overlapping UTC start, with all eight minutes plus ten seconds
already elapsed. Do not use overlapping ranges: an existing observation identity
cannot be replaced with different measured content.

The generator sends measurements to Kafka. The processor calculates CSSR and
opens one episode after two bad minutes. A missing minute becomes UNKNOWN.
Three healthy minutes mark the service RECOVERED. The analyst's workflow stays
OPEN until a separate workflow action resolves it.

## Run the checks

With the full stack running, the following creates and then removes a temporary
local test account. It leaves generated scenario evidence available for inspection.

```bash
cd ~/Documents/PT/Telecom-Anomaly-Detection/apps/dashboard
G1_WINDOW_START=2026-09-23T08:36:00Z npm run test:g1
```

The test checks real login, service selection, exact server KPI parity, missing
data, incident detail, replay after Kafka acknowledgement, and logout.
Read [the evidence record](../../evidence/2026-09-22-g1-ui.md) for results and pending owner sign-off.

## Stop

Stop the Spring terminal with Ctrl+C, then from the repository root run
`docker compose down`. Stored database data remains available next time.
