# Raport de implementare și predare — Streaming, Day 01/02

Implementare verificată la 15 septembrie 2026 pentru task-urile revizuite din
15 și 16 septembrie. Acest raport confirmă componentele testate, nu integrarea
completă a platformei sau acceptarea formală de către colegi.

## Starea inițială și inventarul vechi

- Repository local: `C:\OrangeSystems\Program\Telecom-Anomaly-Detection`.
- Branch: `feature/streaming-contracts`; HEAD inițial `321ade5`; working tree curat.
- Nu există `AGENTS.md` aplicabil în repository sau directoarele părinte verificate.
- Commit-uri relevante: `321ade5` integrarea dovezilor EventV1; `5c8b526` teste de
  schemă; `38482f6` handoff; `86fbe51` scenarii; `985e1d5` eliminarea BILLING.
- Verificarea branch-ului remote `feat/incident-api-v3` a fost read-only față de
  working tree; nu s-a făcut merge/rebase. Nici acesta nu conține scaffold-ul cerut.
- Pachetul `C:\OrangeSystems\task` conține `00_Common_Service_Assurance_Guide.pdf`,
  `01_Zavtoni_Ion_Streaming_and_Simulator11.pdf`, `taskvechi.pdf` și `README.md`.
  Specificația pentru aceste zile este în ghidul comun, paginile 8-10, și planul
  lui Ion, paginile 3, 6 și 18. Pachetul nu conține schemele/scaffold-ul menționate.

| Lucrare din 11/14 septembrie | Stare găsită / decizie |
| --- | --- |
| EventV1, CALL/SMS/DATA/AUTH/NETWORK, exemple normale și link-cut | Implementate, păstrate în `contracts/events/v1/`; teste reutilizate pentru regresii. |
| Documentație UTC, unități, topic/key, retry | Reutilizată ca metodă de lucru; regulile v2 sunt documentate separat. |
| Scenarii subscriber-risk și payload EventV1 | Incompatibile cu noul input de service assurance; nu au fost convertite. |
| BILLING | Deja eliminat înaintea task-ului prin commit-ul utilizatorului; istoria rămâne intactă. |
| Generator Java, Clock, determinism, validare Java și health din 14 septembrie | Neimplementate; doar directoare `.gitkeep`. |
| Processor | Doar directoare `.gitkeep`, fără cod sau POM. |
| Observations v2, topology, policy, fixtures seed, check-contracts, tests/reference | Absente; s-a creat baseline-ul minim necesar. Detector policy rămâne pentru task-ul responsabilului. |
| Build root/wrapper | Absente; ADR 002 cere Java 21 și familia Boot 3.5. |

Nu s-au șters sau mutat artefacte legacy, nu s-au rescris commit-uri ale utilizatorului
și nu s-au schimbat topic-urile Compose, API-ul vechi sau implementările altor colegi.
Materialele Revision 2 sunt separate prin căile existente și marcajele README;
nu s-a inventat o migrare destructivă spre un folder archive.

## Day 01 — contract și semantică

Commit: `bbd6ddc` — `feat(contracts): freeze TelecomObservationV2 semantics`.

Fișiere adăugate:

- `contracts/observations/telecom-observation-v2.schema.json`;
- `contracts/README.md` și `contracts/topology/demo-scopes-v2.json`;
- 12 fixture-uri în `contracts/fixtures/observations/`;
- mutații pozitive/negative în `contracts/fixtures/validation/observation-cases-v2.json`;
- `scripts/check-contracts.py`, `scripts/observation_contract.py`;
- `tests/reference/__init__.py`, `tests/reference/test_observation_contract.py`.

Sunt verificate discriminatorul SERVICE/NODE/HEARTBEAT, UUID, UTC/minut, emittedAt,
quality, autoritatea source/scope/node, identitatea contoarelor și limitele numerice.
Validarea între documente întoarce DUPLICATE pentru retry identic și respinge CONFLICT
pentru modificări sub același eventId sau aceeași cheie naturală. Nu adună trafic.
Verificarea in-memory este pentru loturi finite; nu reprezintă receipts persistente.

Decizii explicite deoarece scaffold-ul lipsea: schemaVersion este întregul `2`,
limita demo este 10.000 eșantioane SMS, iar numele metricilor sunt cele din noul
contract. Heartbeat-ul v2 este un interval consolidat de un minut; programarea
pulsurilor la 10 secunde din ghid necesită o limită separată într-un task ulterior.

## Day 02 — servicii și generare

Al doilea commit: `feat(streaming): scaffold generator and processor boundaries`
(SHA disponibil în `git log -1` după commit).

Fișiere/grupuri adăugate sau modificate:

- `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`;
- POM-urile și codul/testele/configurarea din `services/event-generator/` și
  `services/processor/`, în package-urile `md.utm.telecom.generator` și
  `md.utm.telecom.processing`;
- `services/streaming-support/`: bibliotecă comună de validare și readiness,
  fără al treilea serviciu de aplicație;
- planul `services/event-generator/src/main/resources/seeded-intervals-v2.json`;
- `scripts/check-streaming-smoke.py`, acest raport și
  [runbook-ul Streaming](../runbooks/streaming.md);
- README root, `.env.example`, `.gitignore` pentru handoff și ignorarea build-urilor.

Java 21, Boot 3.5.16, Maven wrapper 3.9.16, validator NetworkNT 1.5.9.
Generatorul folosește Clock injectat, fixture-uri v2 și UUID stabil din cheia
observației. Variația seed-ului păstrează identitățile contoarelor și este stabilă
inclusiv pentru minute comune între loturi de lungimi diferite. Seed/profile/run
metadata nu intră în observație. Preview-ul finit nu publică în Kafka.

## Verificări executate

Comenzile au fost rulate din root. Python-ul activ pentru verificări este
`.\.venv\Scripts\python.exe` (dependențele existente). Java folosit:
`C:\OrangeSystems\Program\.tools\jdk21\jdk-21.0.12.1+1\bin\java.exe`.
Toolchain-ul portabil și cache-ul Maven sunt în afara repository-ului, în workspace;
nu sunt incluse în commit-uri.

Pentru Maven, prefixul exact al sesiunii:

```powershell
$env:JAVA_HOME='C:\OrangeSystems\Program\.tools\jdk21\jdk-21.0.12.1+1'
```

| Comandă | Rezultat |
| --- | --- |
| `.\.venv\Scripts\python.exe scripts/check-contracts.py` | PASS: schema și 12 observații independente. |
| `.\.venv\Scripts\python.exe -m unittest discover -s tests/reference -v` | PASS: 5 teste, inclusiv cazuri de validare parametrizate și conflicte. |
| `.\.venv\Scripts\python.exe -m unittest discover -s tests -v` | PASS: 26 teste, inclusiv toate cele 21 legacy. |
| `.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' -pl services/event-generator,services/processor -am test` | PASS: 35 teste Java, zero failures/errors/skips: support 29, generator 4, processor 2. |
| `.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' -pl services/event-generator,services/processor -am package -DskipTests` | PASS: ambele JAR-uri executabile; testele au fost rulate separat înaintea împachetării. |
| `.\.venv\Scripts\python.exe scripts/check-streaming-smoke.py --java C:\OrangeSystems\Program\.tools\jdk21\jdk-21.0.12.1+1\bin\java.exe` | PASS: două preview-uri identice de câte 10 observații, validate independent în Python; ambele JAR-uri pornesc fără Kafka pe 8081/8083, liveness 200/UP, readiness 503/DOWN. |
| `.\.venv\Scripts\python.exe scripts/check-contracts.py --batch target/streaming-smoke/preview.json` | PASS: 10 acceptate, zero conflicte; retry-urile identice sunt verificate separat în smoke. |
| `git diff --check` și `git diff --cached --check` | PASS înainte de commit; fără erori de whitespace. |

Testele Java health folosesc HTTP real și broker Kafka KRaft embedded real:
readiness 200/UP cu broker disponibil; după oprire, readiness 503/DOWN și liveness
200/UP pentru ambele aplicații. Nu s-a înlocuit indicatorul cu un mock.

Încercări inițiale nereușite, corectate și rerulate: Python global nu avea validatorul
OpenAPI (venv-ul existent îl are); sandbox-ul bloca Maven Central (download autorizat);
prima comandă de wrapper a fost interpretată greșit de PowerShell (argument citat
corect la rerulare); testul Kafka folosea inițial alt port decât cel alocat efectiv
de embedded KRaft (corectat); logging-ul DEBUG moștenit polua stdout-ul preview
(dezactivat explicit în scriptul de verificare). Aceste încercări nu sunt dovezi PASS.

## Predare

**Stanislav:** generator 8081, processor 8083. Rute:
`/actuator/health/liveness`, `/actuator/health/readiness`. Variabile:
`KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_READINESS_TIMEOUT`,
`KAFKA_READINESS_POLL_INTERVAL`, `SERVER_PORT`, `GENERATOR_SEED`,
`GENERATOR_COUNT`, `GENERATOR_FIXTURES`, `GENERATOR_LOGICAL_TIME`,
`GENERATOR_PREVIEW`. Timeout implicit 2s, poll 1s; health HTTP citește un snapshot,
nu așteaptă Kafka. Detalii și comenzi în runbook. Docker daemon nu era disponibil;
nu se revendică image build, Compose integration sau brokerul Docker verificat.

**Sergiu:** `normal-volte`/`degraded-volte` au CSSR 99,5%/90% cu eligibile 1.000;
RRC și bearer folosesc separat 1.200/1.100 attempts. Formula este
`100 * technicalSuccesses / (attempts - userOutcomes)`, null la zero.
SMS normal/degradat are p95 2.000/45.000 ms, nearest-rank. Fixture-ul
`sms-no-completions` se combină cu `degraded-smsc`: zero completări, coadă 250,
vârsta 90 secunde. Formula de identitate și toate câmpurile sunt în contracts README.
Valorile preview VoLTE includ variația documentată; fixture-urile sursă rămân fixe.

**Denis:** numele implementate conform ghidului sunt `VOLTE-MD-CENTRAL` și
`SMS-MD-ROUTE-A`; servicii `VOLTE`/`SMS`; noduri IMS-A, SMSC-A, TRANSPORT-A.
Nu s-a modificat contractul legacy de incident evidence. Acceptarea colegilor
și integrarea cu noul API nu sunt încă verificate.

## Limite și următorul task

Nu există publicare/consum Kafka, DB, receipts persistente, finalizare KPI,
detector, ML, episode logic sau simulator API. Readiness confirmă accesul la
metadata Kafka, nu întregul pipeline. Nu există încă scheduler de opt minute,
pulsuri la 10 secunde sau calcul de stale source. Limita SMS și inventarul minim
sunt decizii documentate de baseline și trebuie incluse în G0.

Urmează Day 03: integrarea inventarului runtime, validarea G0 cu colegii și acordul
asupra cheilor/topic-urilor v2; apoi task-urile planificate de publicare și persistență.
DATA, roaming, billing fraud, account compromise și corelația cross-service rămân backlog.
