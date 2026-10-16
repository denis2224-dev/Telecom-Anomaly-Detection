# Review independent — Sergiu, PR #111

**Verdict formal: APPROVE.**
Review publicat: [APPROVED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/111#pullrequestreview-5533312847), ID `5533312847`, commit `5d5a9d596beed8c47e72d5fc3e2b03c8fc8809b7`.

Reviewer: **Codex (OpenAI), verificare automată independentă**, publicată prin contul GitHub conectat `itsjohnyoff`. Nu atribui execuția sau semnătura unei persoane.
Data: **15 octombrie 2026**. Receipt finalizat la `2026-10-15T12:48:28.974593+00:00` UTC / `2026-10-15T15:48:28.974593+03:00` Europe/Chisinau.
PR: https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/111
Source revision: `5d5a9d596beed8c47e72d5fc3e2b03c8fc8809b7`. Git tree: `1caf6bb7188583c2b590fb9f0cbe86588d7e165f`.
Instrucțiuni de reproducere: exclusiv `docs/runbooks/detection-and-models.md` din această revizie. Codul și diff-ul au fost citite separat pentru review.

## Rezultatul verificării

Rebuild-ul Windows s-a făcut într-un director nou. Exportul Java pentru studiul arhivat a fost generat în această execuție, din input-ul arhivat, fără a folosi exportul publicat ca input al evaluării. Exportul publicat a fost folosit ulterior numai pentru comparație.

| Verificare | Rezultat / dovadă |
|---|---|
| Checkout izolat | Două worktree-uri detached la același SHA; al doilea creat direct cu LF, inițial fără modificări |
| Rebuild candidat Windows | 11 fișiere identice cu pachetul aprobat; `candidate-comparison.json` |
| Poarta Windows explicită | PASS; proces CLI curat, inventar complet și respingerea unei copii corupte; `windows-byte-gate.log` |
| Export Java propriu pentru arhivă | PASS; identic byte-for-byte cu exportul publicat; `lf-fresh-archived-java-export.log` |
| Rezultate / predicții / praguri arhivate | Toate identice byte-for-byte; `archive-comparison.json` |
| Verifier complet Windows | `PASS`, scope `WINDOWS_FULL`, Windows gate `PASS`; `verification/verification.json` |
| Reactor Java și drill-uri Docker | 1416 raportate, 0 failures, 0 errors, 8 skips; detalii mai jos |
| Model lipsă / corupt | PASS, 7 teste; lipsă, checksum/manifest, deserializare blocată, FALLBACK și readiness legacy păstrat; `model-failure-drills.log` |
| HTTP real local | PASS, cele trei domenii și respingerea input-urilor adversariale; `http-diagnosis.log` |
| Date lipsă | Export Java propriu și cele trei SELECT-uri documentate în PostgreSQL disposable, tranzacție READ ONLY; `missing-data-diagnosis.json`, `lf-read-only-data-diagnosis.log` |
| Imutabilitate | 2848 fișiere tracked și 24 fișiere reference verificate înainte/după; modificări: 0 |
| CI remote | Patru workflow-uri success la SHA-ul verificat; `remote-state.json`. CI nu înlocuiește acest receipt |

## Mediu și pregătire

- Windows 11, build 26100, AMD64; Windows PowerShell 5.1.26100.9168 (pwsh absent); CPU Intel64 Family 6 Model 140, 8 procesoare logice.
- Python **3.13.9**, runtime local verificat; Python-ul implicit 3.13.7 nu a fost folosit pentru reproducere.
- Java **Temurin 21.0.12.1+1**, Maven Wrapper **3.9.16**; detalii în `lf-java.log`, `lf-maven.log`.
- Docker Desktop **4.66.1 (222799)**, client/engine **29.3.1**, Linux/amd64 pentru containere. Docker a fost pornit pentru resursele Testcontainers.
- Pinned requirements instalate în venv-ul izolat. Inventar complet, inclusiv dependențele tranzitive: `lf-dependencies.log`.
- Reference pack: `C:/OrangeSystems/task/Trace_Link_Integration_Pack`; verificarea celor 29 de fișiere V2/reference și a fixture-ului independent a trecut.
- Workspace final: `C:/OrangeSystems/Program/pr111-independent-lf-20261015`. Venv-ul izolat din primul worktree a fost reutilizat în al doilea, cu aceleași versiuni; nu provine din venv-ul autorului.
- Override-uri exclusiv pentru tooling: `JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot`; `MAVEN_OPTS=-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2`; `PYTHONPATH=<workspace>/services/ml-service`.

```powershell
git -C Telecom-Anomaly-Detection fetch origin refs/pull/111/head
git -C Telecom-Anomaly-Detection worktree add --detach C:/OrangeSystems/Program/pr111-independent-20261015 5d5a9d596beed8c47e72d5fc3e2b03c8fc8809b7
& C:/OrangeSystems/Program/sergiu-day4-review-evidence-20261014/python-runtime/cpython-3.13.9-windows-x86_64-none/python.exe -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt -r services/ml-service/requirements-ml.txt
git -c core.autocrlf=false -C Telecom-Anomaly-Detection worktree add --detach C:/OrangeSystems/Program/pr111-independent-lf-20261015 5d5a9d596beed8c47e72d5fc3e2b03c8fc8809b7
```

Fetch, ambele worktree-uri, crearea venv-ului și instalarea cu acces de rețea au ieșit cu 0. Comenzile de reproducere și exit-urile sunt în anexele de mai jos și în `commands.jsonl`; helper-ul `run_receipt.py` captează argv, cwd, UTC, durată, exit și log fără a schimba sursele.

## Comparație SHA-256

| Artefact | SHA-256 publicat = SHA-256 proaspăt |
|---|---|
| processor-export | `44b42b01f8fbc7ccf8b6fe3e6e47420468672a63ff2bc55c553a871913efb8c6` |
| results.json | `131c39292a94e5ddb4b40597eed3d7facfe212598a551f21a9466d7b8875d7bb` |
| predictions.json | `a569508ab29e619f94d09cc02c89a4b7d73f0929aafb78abf86ed7c7d47c265c` |
| thresholds.json | `4c7badefec7cc9b98e69771c7d2f5ccde7cd26bec5b5d26aab701126f59f0c4b` |

Praguri neschimbate: TRANSPORT **0.95**, ROAMING **0.95**, MOBILE_DATA **0.90**; `testUsed=false`. Studiu: 280 grupuri / 3.360 ferestre, 1.680 ferestre test. Detectorul determinist: 65 TP / 0 FP / 0 FN la episoade; fără baseline: 20 TP / 0 FP / 45 FN. ML shadow rămâne opțional și nu are politică de episoade/cauze.

| Input arhivat | SHA-256 |
|---|---|
| inputs.json.gz | `8ef1f46d5e246f2de9cd201b37fb2e886b1b0fcc95f8abc9168a89562e298f69` |
| manifest.json | `4a0d40f4bf7fdd7a0cab9eb64d0b8d2e3b88182b36b134bcecfd6971eed6a40e` |
| protocol.json | `c0b08f47ccabad04187102c025721de0ff6dd9bb3b1b9c44e03c40fdbf2d470e` |
| truth.json | `34f96a641a8e60ef9340a32ed4aad25fcb8e22267b7cc2755cccad503c8b8493` |

Pachetul candidat, modelele existente, politicile, baseline-urile și fixture-urile nu diferă față de baza PR. Arhiva Day 5 originală nu diferă între commit-ul de arhivare `be68245` și head-ul corectat. Snapshot-urile complete înainte/după sunt păstrate; nu s-au regenerat artefacte aprobate în loc, nu s-au modificat truth/protocol/seeds și nu s-au ajustat praguri.

## Findings, eșecuri și limite

1. **P3 — checkout Windows cu CRLF.** Prima evaluare a arhivei a eșuat cu exit 1: `Source pin mismatch: contracts/README.md`. 206 pin-uri diferite erau exclusiv conversii CRLF/LF. Runbook-ul cere LF, dar nu oferă comanda de checkout. Un worktree nou creat cu `git -c core.autocrlf=false ... worktree add` reproduce pin-urile și raportul fără modificarea input-urilor. Recomandare: includeți această comandă în runbook. `archived-evaluation.log` și `crlf-pin-diagnosis.json` păstrează eșecul și diagnosticul.
2. **P3 — diagnoza manuală operațională este incomplet descrisă.** Runbook-ul oferă SELECT-urile și cere restart normal, dar nu dă comenzi de conectare la o bază locală disposable și de stop/start worker. Am executat cele trei SELECT-uri în containerul PostgreSQL al acestei verificări, într-o tranzacție READ ONLY: pending source=0, pending shadow=0, nicio lease pending la acel snapshot. Nu am interogat o bază partajată. Restart-ul este verificat prin drill-ul automat documentat; un worker operațional live nu a fost repornit. Recomandare: adăugați comenzile locale și rezultatele așteptate în runbook.
3. **Pregătire mediu.** Prima instalare pip a eșuat cu exit 1 din cauza restricției de rețea WinError 10013; reluarea autorizată a trecut. Docker a raportat inițial pipe absent / exit 1, apoi client și server au fost verificați după pornire. Primul `maven -version` prin helper a eșuat cu exit 1 deoarece helper-ul a transmis `./mvnw.cmd` către Windows subprocess; calea absolută a remediat harness-ul, fără schimbări în repository. Log-urile inițiale sunt păstrate.
4. **Comenzi auxiliare de inspecție.** Două încercări Python inline pentru auditul hash-urilor au ieșit cu SyntaxError/exit 1 din cauza quoting-ului PowerShell; auditul a fost efectuat ulterior prin fișiere Python, exit 0. Acestea sunt erori ale harness-ului de review, nu ale produsului.
5. **Linux nativ: NOT_RUN în acest receipt.** Ramura Linux și respingerea porții Windows sunt confirmate prin code review și regresiile documentate din suita root; acestea folosesc simularea platformei. Nu pretind că am rulat verifier-ul complet într-un Linux separat.
6. **Acceptare:** `secondPersonReproduction=PENDING` și `sharedReleaseApproval=false` rămân neschimbate în manifest. Acest receipt identifică explicit un reviewer automat; nu este o semnătură umană. Review-ul GitHub privește codul și dovezile tehnice măsurate. Receipt-ul trebuie revizuit de persoana responsabilă înaintea acceptării Day 5. PR-ul rămâne draft; fără merge, promovare model sau activare.

Date lipsă: exemplele proaspete păstrează `quality=MISSING`, `coverage=0`, `observedAt=null`, populație/primitives indisponibile și verdict `UNKNOWN`. Golurile în impairment/recovery nu sunt convertite în sănătate sau recovery. Exemplul de low volume rămâne ineligibil/UNKNOWN. Evaluarea este `EVENT_TIME_REPLAY`; nu dovedește freshness live sau capacitate de producție.

## Toate skip-urile

Prima suită ML: 100 teste raportate, 99 trecute, un skip: `test_fresh_java_exports_match_independent_python`, deoarece exportul `TraceLinkFeaturesTest` nu exista încă. Parity-ul Java/Python ulterior are propriul rezultat și log în verifier. Nu adunăm invocările suprapuse.

| Suită / test | Motiv skip |
|---|---|
| `md.utm.telecom.processing.GeographicDetectionTest.pinnedHttpScorerAcceptsCityVectorsAndRejectsChangedBaselineWithoutBlockingRules(String)` | Environment variable [ML_SERVICE_URL] does not exist |
| `md.utm.telecom.processing.history.TraceLinkDatasetBootstrapIT.suppliedFullArchiveImportsOnlyWhenExplicitlySelected` | org.opentest4j.TestAbortedException: Assumption failed: Reference archive must be selected explicitly |
| `md.utm.telecom.processing.kpi.SmsDeliveryTest.livePackagedModelEnrichesRecoveredSmsEpisode` | ML_SERVICE_URL absent (confirmat în condiția assumeTrue din test) |
| `md.utm.telecom.processing.kpi.SmsShadowReplayTest.freshObservationsReachDurableShadowStorageWithoutChangingRuleIncidents` | Environment variable [SMS_SHADOW_REPLAY_DIR] does not exist |
| `md.utm.telecom.processing.kpi.tracelink.TraceLinkProjectionIT.fullSuppliedArchiveHasPersistedNativeAndThirtyDayRollupParity` | org.opentest4j.TestAbortedException: Assumption failed: Full archive must be explicitly selected |
| `md.utm.telecom.processing.kpi.TraceLinkDomainArchiveTest.suppliedFullArchiveProducesIndependentSqlParityWithoutDeliveryWrites` | System property [trace.link.archive] does not exist |
| `md.utm.telecom.processing.kpi.TraceLinkV016CompatibilityTest.frozenV016MigratesAndRemainsHeldWithoutAnyProducerSchemaChange(Path)` | System property [trace.link.v016.directory] does not exist |
| `md.utm.telecom.processing.kpi.VoiceDeliveryTest.livePackagedModelEnrichesRecoveredVoiceEpisode` | ML_SERVICE_URL absent (confirmat în condiția assumeTrue din test) |
| `ml-python.log` | test_fresh_java_exports_match_independent_python (test_trace_link_features.TraceLinkFeaturesTests.test_fresh_java_exports_match_independent_python) ... skipped 'Run TraceLinkFeaturesTest to produce fresh Java parity exports' |
| `root-python.log` | test_day1_invokes_native_wrapper_and_propagates_failure (test_trace_link_verifier_portability.VerifierPortabilityTest.test_day1_invokes_native_wrapper_and_propagates_failure) ... skipped 'PowerShell runtime required for wrapper regression' |

Detalii pentru fiecare suită Java și fiecare skip: `junit-summary.json`, XML-urile și log-urile originale. Testele opționale neexecutate nu sunt revendicate ca PASS.

## Platform correction — code review

- `sys.platform == win32` selectează `WINDOWS_FULL`; Linux selectează `PORTABLE_RUNTIME` și `windowsByteReproduction.status=NOT_RUN`.
- Pe Windows, poarta CLI separată compară inventarul și fiecare SHA; coruperea copiei este respinsă. Un eșec produce `INCOMPLETE`, exit nenul și log-uri păstrate.
- Succesul tehnic pe ambele platforme păstrează `secondPersonReproduction=PENDING` / `sharedReleaseApproval=false`. Cele opt regresii de boundary/verifier verifică aceste ramuri și prerequisite lipsă.
- CI păstrează dependențele Windows byte reproduction → Linux model evaluation → processor evaluation → Day 5 evaluation. Niciun rezultat de workflow nu acordă automat acceptarea independentă.

## Comenzi executate, exit-uri și log-uri

Forma din `commands.jsonl` este argv-ul exact. Mai jos este echivalentul PowerShell; fiecare bloc indică directorul curent. Exit-urile subprocess-urilor interne sunt în manifestul verifier-ului, nu sunt ascunse de exit-ul helper-ului.

### revision — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:36.594281+00:00` → `2026-10-15T12:06:36.676641+00:00`. Log: `revision.log`.

```powershell
& 'git' 'rev-parse' 'HEAD'
```

### tree — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:36.680889+00:00` → `2026-10-15T12:06:36.764093+00:00`. Log: `tree.log`.

```powershell
& 'git' 'rev-parse' 'HEAD^{tree}'
```

### initial-status — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:36.766079+00:00` → `2026-10-15T12:06:36.931991+00:00`. Log: `initial-status.log`.

```powershell
& 'git' '-c' 'core.autocrlf=false' 'status' '--porcelain'
```

### python — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:36.935568+00:00` → `2026-10-15T12:06:36.982356+00:00`. Log: `python.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-VV'
```

### java — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:36.983390+00:00` → `2026-10-15T12:06:37.275128+00:00`. Log: `java.log`.

```powershell
& 'java' '-version'
```

### maven — exit 1

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:37.282809+00:00` → `2026-10-15T12:06:37.509921+00:00`. Log: `maven.log`.

```powershell
& './mvnw.cmd' '-version'
```

### docker — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:37.514079+00:00` → `2026-10-15T12:06:37.938540+00:00`. Log: `docker.log`.

```powershell
& 'docker' 'version'
```

### dependencies — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:37.939396+00:00` → `2026-10-15T12:06:41.620235+00:00`. Log: `dependencies.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-m' 'pip' 'freeze'
```

### pins — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:41.621260+00:00` → `2026-10-15T12:06:41.903608+00:00`. Log: `pins.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/verify-trace-link-pins.py' '--reference-root' 'C:/OrangeSystems/task/Trace_Link_Integration_Pack'
```

### rebuild — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:06:42.620352+00:00` → `2026-10-15T12:07:12.961557+00:00`. Log: `rebuild.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/training/trace_link_candidate.py' '--output' 'tmp/day5-independent-candidate'
```

### windows-byte-gate — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:07:12.963319+00:00` → `2026-10-15T12:07:31.663184+00:00`. Log: `windows-byte-gate.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/tests/windows_candidate_reproduction.py' '-v'
```

### model-load — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:09:32.525260+00:00` → `2026-10-15T12:09:40.258116+00:00`. Log: `model-load.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-c' 'from app.inference.trace_link_scoring import load; load()'
```

### model-failure-drills — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:09:40.260816+00:00` → `2026-10-15T12:09:48.950127+00:00`. Log: `model-failure-drills.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-m' 'unittest' 'discover' '-s' 'services/ml-service/tests' '-p' 'test_trace_link_failure_modes.py' '-v'
```

### http-diagnosis — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:09:48.950843+00:00` → `2026-10-15T12:10:01.850862+00:00`. Log: `http-diagnosis.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/verify-trace-link-ml-http.py'
```

### fresh-archived-java-export — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:07:04.908643+00:00` → `2026-10-15T12:10:18.536437+00:00`. Log: `fresh-archived-java-export.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\mvnw.cmd' '-pl' 'services/processor' '-am' 'test' '-Dtest=TraceLinkStudyExportTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DtraceLink.study.inputs=C:\OrangeSystems\Program\pr111-independent-20261015\docs\evidence\assets\trace-link-day5-20261015\study\inputs.json.gz' '-DtraceLink.study.output=C:\OrangeSystems\Program\pr111-independent-20261015\tmp\day5-independent-archived-processor.json' '--batch-mode' '--no-transfer-progress'
```

### archived-evaluation — exit 1

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:10:18.538074+00:00` → `2026-10-15T12:10:23.093908+00:00`. Log: `archived-evaluation.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/training/trace_link_evaluation.py' '--dataset' 'C:\OrangeSystems\Program\pr111-independent-20261015\docs\evidence\assets\trace-link-day5-20261015\study' '--protocol' 'C:\OrangeSystems\Program\pr111-independent-20261015\docs\evidence\assets\trace-link-day5-20261015\study\protocol.json' '--processor-export' 'C:\OrangeSystems\Program\pr111-independent-20261015\tmp\day5-independent-archived-processor.json' '--candidate' 'services/ml-service/candidate-models/trace-link-shadow-v1' '--output' 'tmp/day5-independent-archived-results'
```

### ml-discovery — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-20261015`. UTC `2026-10-15T12:07:31.667154+00:00` → `2026-10-15T12:11:10.418994+00:00`. Log: `ml-discovery.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-m' 'unittest' 'discover' '-s' 'services/ml-service/tests' '-v'
```

### lf-revision — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:56.136465+00:00` → `2026-10-15T12:12:56.190603+00:00`. Log: `lf-revision.log`.

```powershell
& 'git' 'rev-parse' 'HEAD'
```

### lf-tree — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:56.191104+00:00` → `2026-10-15T12:12:56.244410+00:00`. Log: `lf-tree.log`.

```powershell
& 'git' 'rev-parse' 'HEAD^{tree}'
```

### lf-initial-status — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:56.245212+00:00` → `2026-10-15T12:12:56.325224+00:00`. Log: `lf-initial-status.log`.

```powershell
& 'git' '-c' 'core.autocrlf=false' 'status' '--porcelain'
```

### lf-python — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:56.325759+00:00` → `2026-10-15T12:12:56.352327+00:00`. Log: `lf-python.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-VV'
```

### lf-java — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:56.352889+00:00` → `2026-10-15T12:12:56.681937+00:00`. Log: `lf-java.log`.

```powershell
& 'java' '-version'
```

### lf-maven — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:56.682595+00:00` → `2026-10-15T12:12:59.686778+00:00`. Log: `lf-maven.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\mvnw.cmd' '-version'
```

### lf-docker — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:59.687309+00:00` → `2026-10-15T12:12:59.840953+00:00`. Log: `lf-docker.log`.

```powershell
& 'docker' 'version'
```

### lf-dependencies — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:12:59.841738+00:00` → `2026-10-15T12:13:01.010862+00:00`. Log: `lf-dependencies.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-m' 'pip' 'freeze'
```

### lf-pins — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:13:01.012885+00:00` → `2026-10-15T12:13:01.265955+00:00`. Log: `lf-pins.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/verify-trace-link-pins.py' '--reference-root' 'C:/OrangeSystems/task/Trace_Link_Integration_Pack'
```

### lf-fresh-archived-java-export — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:13:01.725748+00:00` → `2026-10-15T12:15:10.495412+00:00`. Log: `lf-fresh-archived-java-export.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\mvnw.cmd' '-pl' 'services/processor' '-am' 'test' '-Dtest=TraceLinkStudyExportTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DtraceLink.study.inputs=C:\OrangeSystems\Program\pr111-independent-lf-20261015\docs\evidence\assets\trace-link-day5-20261015\study\inputs.json.gz' '-DtraceLink.study.output=C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-archived-processor.json' '--batch-mode' '--no-transfer-progress'
```

### lf-archived-evaluation — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:15:10.496127+00:00` → `2026-10-15T12:18:06.644044+00:00`. Log: `lf-archived-evaluation.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/training/trace_link_evaluation.py' '--dataset' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\docs\evidence\assets\trace-link-day5-20261015\study' '--protocol' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\docs\evidence\assets\trace-link-day5-20261015\study\protocol.json' '--processor-export' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-archived-processor.json' '--candidate' 'services/ml-service/candidate-models/trace-link-shadow-v1' '--output' 'tmp/day5-independent-archived-results'
```

### lf-read-only-data-diagnosis — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:31:30.992606+00:00` → `2026-10-15T12:31:31.139239+00:00`. Log: `lf-read-only-data-diagnosis.log`.

```powershell
& 'docker' 'exec' '1394556f2840' 'psql' '-U' 'processing_app' '-d' 'processing_db' '-v' 'ON_ERROR_STOP=1' '-c' 'BEGIN READ ONLY;
SELECT count(*) AS pending, min(w.created_at) AS oldest_pending_source
FROM app.trace_link_job j JOIN app.trace_link_window w USING(window_id)
WHERE j.completed_at IS NULL;
SELECT count(*) AS pending, min(e.evaluated_at) AS oldest_pending_evaluation
FROM app.trace_link_shadow_job j JOIN app.trace_link_evaluated_window e USING(window_id)
WHERE j.completed_at IS NULL;
SELECT lease_until, completed_at FROM app.trace_link_shadow_job
WHERE completed_at IS NULL ORDER BY lease_until NULLS FIRST LIMIT 20;
ROLLBACK;'
```

### lf-full-verifier — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:18:06.821680+00:00` → `2026-10-15T12:44:02.104762+00:00`. Log: `lf-full-verifier.log`.

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/verify-trace-link-day5.py' '--output' 'tmp/day5-independent-verification' '--reference-root' 'C:/OrangeSystems/task/Trace_Link_Integration_Pack'
```

### lf-final-status — exit 0

Cwd: `C:\OrangeSystems\Program\pr111-independent-lf-20261015`. UTC `2026-10-15T12:44:20.164536+00:00` → `2026-10-15T12:44:20.228662+00:00`. Log: `lf-final-status.log`.

```powershell
& 'git' '-c' 'core.autocrlf=false' 'status' '--porcelain'
```

### Subcomenzile verifier-ului documentat

| Etapă | Exit | Log |
|---|---:|---|
| generate | 0 | `verification/generate.log` |
| processor-export | 0 | `verification/processor-export.log` |
| evaluate | 0 | `verification/evaluate.log` |
| reproduce-evaluation | 0 | `verification/reproduce-evaluation.log` |
| contracts | 0 | `verification/contracts.log` |
| frozen-pins | 0 | `verification/frozen-pins.log` |
| root-python | 0 | `verification/root-python.log` |
| ml-python | 0 | `verification/ml-python.log` |
| windows-byte-reproduction | 0 | `verification/windows-byte-reproduction.log` |
| processor-regression-and-drills | 0 | `verification/processor-regression-and-drills.log` |
| trace-link-parity | 0 | `verification/trace-link-parity.log` |
| voice-parity | 0 | `verification/voice-parity.log` |
| sms-parity | 0 | `verification/sms-parity.log` |
| local-http | 0 | `verification/local-http.log` |

`generate`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/training/trace_link_study.py' '--output' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\study'
```

`processor-export`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\mvnw.cmd' '-pl' 'services/processor' '-am' 'test' '-Dtest=TraceLinkStudyExportTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DtraceLink.study.inputs=C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\study\inputs.json.gz' '-DtraceLink.study.output=C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\processor.json' '--batch-mode' '--no-transfer-progress'
```

`evaluate`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/training/trace_link_evaluation.py' '--dataset' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\study' '--processor-export' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\processor.json' '--protocol' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\study\protocol.json' '--output' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\evaluation'
```

`reproduce-evaluation`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/training/trace_link_evaluation.py' '--dataset' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\study' '--processor-export' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\processor.json' '--protocol' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\study\protocol.json' '--output' 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\tmp\day5-independent-verification\repeated'
```

`contracts`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/check-contracts.py'
```

`frozen-pins`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/verify-trace-link-pins.py' '--reference-root' 'C:\OrangeSystems\task\Trace_Link_Integration_Pack'
```

`root-python`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-m' 'unittest' 'discover' '-s' 'tests' '-v'
```

`ml-python`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' '-m' 'unittest' 'discover' '-s' 'services/ml-service/tests' '-v'
```

`windows-byte-reproduction`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'services/ml-service/tests/windows_candidate_reproduction.py' '-v'
```

`processor-regression-and-drills`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-lf-20261015\mvnw.cmd' '-pl' 'services/processor' '-am' 'verify' '--batch-mode' '--no-transfer-progress'
```

`trace-link-parity`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/check-trace-link-ml-parity.py'
```

`voice-parity`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/check-voice-parity.py' '--geographic' '--java-output' 'services/processor/target/geographic-voice-parity-java.json'
```

`sms-parity`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/check-sms-parity.py' '--geographic' '--java-output' 'services/processor/target/geographic-sms-parity-java.json'
```

`local-http`:

```powershell
& 'C:\OrangeSystems\Program\pr111-independent-20261015\.venv\Scripts\python.exe' 'scripts/verify-trace-link-ml-http.py'
```

## Integritatea bundle-ului

`sha256.json` conține hash-urile log-urilor, manifestelor, XML-urilor și output-urilor; `fresh-archived-java-export.json.gz` conține exportul propriu. Comparația exportului folosește bytes JSON decomprimați, nu header-ul gzip. `review-evidence.zip` împachetează receipt-ul și dovezile fără venv, cache Maven sau credențiale.

Review-ul formal și starea finală a PR-ului sunt în `github-review-result.json` / `remote-final-state.json` după publicare. Nu s-a modificat branch-ul PR-ului.

Drill backlog/restart (PostgreSQL real):

```json
{
  "initialPending": 24,
  "oldestEvaluationAgeSeconds": 2.364758,
  "drainElapsedMs": 1512.8919,
  "pendingAfter": 0,
  "claimReclaimed": true,
  "deterministicLifecycleUnchanged": true,
  "productionCapacityClaim": false
}
```

Pregătire suplimentară: în primul worktree, `git -c core.autocrlf=false checkout-index --all --force` a ieșit cu 0, dar nu a rescris fișierele CRLF deja existente. Worktree-ul LF a fost creat separat, fără conversia sau resigilarea input-urilor aprobate.

Invocarea finală `root-python.log`: Ran 183 tests in 25.683s; OK (skipped=1)

Invocarea finală `ml-python.log`: Ran 100 tests in 113.275s; OK (skipped=1)

Publicare review: conectorul GitHub a returnat HTTP 403 / Resource not accessible by integration; încercarea este păstrată în `github-review-result.json`. Fallback-ul GitHub CLI autentificat cu același cont `itsjohnyoff` a ieșit cu 0 și a publicat review-ul APPROVED, ID 5533312847. PR-ul a fost reverificat ulterior: draft=true, merged=false, head neschimbat.

```powershell
gh api user --jq .login
gh api repos/denis2224-dev/Telecom-Anomaly-Detection/pulls/111/reviews --method POST --input C:/OrangeSystems/Program/pr111-independent-evidence-20261015/review-request.json
```
