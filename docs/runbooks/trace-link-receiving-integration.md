# Receiving-owner reproduction checklist

Prepared 12 October 2026, Europe/Chisinau. Each owner must record their own
result; agent execution is not an owner signature. All commands below use the
code/tool snapshot `f572ebd27c71cb809eb43fa51f07f40740ae181c`, based on main
`ae9a53626ac7ee62efec40a19ebb20c6e2cf7049`, frozen #97
`5d2b6d102ac2914ede7bfc812ad51ebb3b20dc2c`, and current #92
`b987aa809101eedc1d40d52c95dff94a1e9e27b8`.

Use a new detached worktree, Java 21, Python/jsonschema and Docker for disposable
tests. Preserve existing working changes, databases and volumes. On this host,
the verified cached launcher is:

```powershell
$maven = 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$cache = '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2'
git worktree add --detach ../trace-link-owner-reproduction f572ebd27c71cb809eb43fa51f07f40740ae181c
Set-Location ../trace-link-owner-reproduction
& $maven -B -ntp $cache -pl services/processor -am package '-DskipTests'
python scripts/verify-trace-link-integration.py `
  --base-ref ae9a53626ac7ee62efec40a19ebb20c6e2cf7049 `
  --producer-ref 5d2b6d102ac2914ede7bfc812ad51ebb3b20dc2c `
  --projection-ref b987aa809101eedc1d40d52c95dff94a1e9e27b8 `
  --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot' `
  --output target/receiving-package
```

Expected: sixteen packaged migrations, frozen V016 SHA-256
`bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848`,
required schemas/resources, fail-before-startup conflicting modes, unchanged
detector/consumer source and eight original producer example records. Retain
`target/receiving-package/{verification.json,producer-examples.jsonl}`. Use a
new output directory on each invocation. Cached paths are host-specific;
substituting the local Java 21/Maven paths does not change source authority.

## Sergiu — detector, UNKNOWN and recovery

Read [trace-link-live-evaluation-v1](../architecture/trace-link-live-evaluation-v1.md).
Inputs are the actual #97 mobile-data JSONL and current main's
`contracts/fixtures/trace-link/detection-cases.json`, not evaluator expectations.

```powershell
& $maven -B -ntp $cache -pl services/processor -am `
  '-Dtest=TraceLinkInputsTest,TraceLinkDomainRulesTest,TraceLinkOfflineIntegrationTest,TraceLinkRecoveryPolicyTest,TraceLinkCausePolicyTest,TraceLinkServiceTransportTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' verify
python scripts/probe-trace-link-compatibility.py `
  --merged-worktree . `
  --measurements docs/evidence/assets/trace-link-day6/run-1/mobile-data-fault.jsonl `
  --output target/sergiu-live-boundary `
  --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
```

Expected: current offline tests pass; positive five-minute fixture is accepted;
raw live wire shape and a 60-second context are rejected, the latter with
`Expected aligned five-minute window`. UNKNOWN does not recover in the existing
offline policy; wrong scope/time and utilization-only cause controls remain
negative. Archive Surefire XML and `target/sergiu-live-boundary/result.json`.
The legacy probe's hard-coded mainCommit label is historical policy provenance;
record the actual checkout SHA alongside it. Record explicit decisions on the
live source, scope, freshness, baseline, identity, policy and recovery questions.
Do not claim minute-domain detection or incidents until an approved adapter and
policy actually execute.

## Denis — mapping, idempotency and authenticated reads

Read the [isolated acceptance specification](../architecture/trace-link-consumer-acceptance-spec-v1.md)
and the separate VoLTE agreement. Inputs are the bounded native projection and
#93 primitive-result fixture; current consumer source is exactly main above.

```powershell
python scripts/check-trace-link-consumer-compatibility.py `
  --consumer-ref ae9a53626ac7ee62efec40a19ebb20c6e2cf7049 `
  --output target/denis-compatibility.json
& $maven -B -ntp $cache -f services/incident-service/pom.xml `
  '-Dtest=DatabaseMigrationTest,TraceInventoryIT,MetricQueryServiceIT,TraceQueryTest' `
  '-Dspring.kafka.bootstrap-servers=127.0.0.1:65534' verify
```

Expected: comparison says INCOMPATIBLE/PENDING_REVIEW; unchanged backend tests
pass its existing supported `volte/voice.success_rate` fixture. These component
tests use disposable PostgreSQL and synthetic authenticated MockMvc sessions;
they do not accept producer input or prove real login/session expiry. Retain
the JSON report and incident-service Surefire XML. The specification defines
exact/rejected replay and additive migration checks after the contract decision.
Connected Kafka commit/ACK and genuine authenticated producer-backed reads are
BLOCKED until Denis supplies an approved mapping, consumer and deployment.

## David — five supported types, statuses and gaps

Inputs are `scenario-command-v1.json`, the #97 measurement/control files, and
the pure scheduler's checkpoint fixtures. Read the scenario runbook and demo
script. The only five new-catalogue types are TRANSPORT_LINK_OVERLOAD,
ROAMING_REGISTRATION_FAILURE, MOBILE_DATA_DNS_FAILURE, NORMAL_CONTROL and
TELEMETRY_GAP. The other thirteen definitions remain unsupported.

```powershell
& $maven -B -ntp $cache -pl services/event-generator -am `
  '-Dtest=TraceLinkScenarioEngineTest,TraceLinkScenarioBoundaryTest,TraceLinkCheckpointTest,TraceLinkReliabilityRegressionTest,TraceLinkTransportScenarioTest,TraceLinkRoamingScenarioTest,TraceLinkMobileDataScenarioTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' verify
java '-Dloader.main=md.utm.telecom.generator.tracelink.TraceLinkScenarioApplication' `
  -cp services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  contracts/fixtures/trace-link/scenario-command-v1.json target/david-transport.jsonl
python scripts/trace_link_scenario_contract.py
```

Expected: bounded CLI has eight minute windows / 24 transport observations;
exact replay retains identical bytes. Independent DNS/activation and roaming
directions remain separate. Scheduler tests retain STOPPED/INTERRUPTED, reject
overlap/reused identities, produce no early/duplicate tick and preserve gaps.
Archive the JSONL/hash and Surefire XML. Display null/UNKNOWN honestly; a stopped
producer is not healthy recovery. Public UI scenario wiring, permissions,
notifications and real-browser state transitions are BLOCKED until the same-SHA
API and detector support them. No UI completion is inferred from this CLI.

## Stanislav — roles, topics, capacity, restore and deployment

Inputs are the frozen archive at `C:/OrangeSystems/task/Trace_Link_Integration_Pack`,
V016 bytes, `scripts/load-profile.json`, actual migration/grant tests and runbooks.

```powershell
& $maven -B -ntp $cache -pl services/processor -am `
  '-Dtest=IngestionIntegrationTest,TraceLinkDatasetBootstrapIT,TraceLinkProjectionIT,TraceLinkV016CompatibilityTest,TraceLinkNativeFailureTest,DependencyOutageIT,ReplayIT,FinalizerRaceIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DtraceLink.referenceRoot=C:/OrangeSystems/task/Trace_Link_Integration_Pack' `
  '-DtraceLink.projectionReferenceRoot=C:/OrangeSystems/task/Trace_Link_Integration_Pack' `
  '-Dtrace.link.v016.directory=C:/OrangeSystems/Program/ion-domain-producer-evidence/v016' `
  '-Dtrace.link.v016.sha256=bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848' verify
python scripts/verify-trace-link-pins.py --reference-root C:/OrangeSystems/task/Trace_Link_Integration_Pack
Get-Content scripts/load-profile.json
```

Expected: real disposable PostgreSQL upgrade/grant/retry/restart controls pass;
runtime cannot mutate finalized rows or publish held intent. V016 and source
identities remain unchanged; 29 frozen inputs verify. Archive source/grant SQL
evidence, Surefire XML and target/native/projection reports. Existing Kafka outage
tests concern the legacy test pipeline and do not prove new-domain delivery.

Before connected execution, Stanislav must supply dedicated approved topic/key,
partitions, role ownership, disabled auto-creation, image digests, resource limits,
one approved configuration and disposable restore target. No new-domain topic
has been provisioned here. The load profile specifies 120s warmup and 900s steady
state; target rate, bounded burst and recovery ceiling remain unset. Its status
is BLOCKED, not a measured capacity result.

The deployment owner must then provide executable environment-specific commands
for send/ACK/mark failure, matching offered/ACK/persisted/finalized/published
intervals, CPU/RAM/lag/drain, consistent backup and restore of each owned DB plus
configuration, restored row/checkpoint/outbox hashes and authenticated smoke.
Those commands cannot be truthfully specified or executed against an unspecified
deployment/backup target. Missing approved targets and publisher/consumer are
the exact blockers; no backup, restore, deployment or capacity PASS is claimed.

## Acceptance record

For each owner record name, exact source SHA/tree, configuration/input hashes,
actual command, start/end and exit status, output hashes, receiving result and
remaining limitations. Only the owner can approve the semantic decisions. The
five-owner matrix includes Ion; producer-ready engineering evidence is not his
personal sign-off. Two connected rehearsals and five genuine signatures remain
required on one approved release configuration. GitHub merging a PR, green CI,
agent tests or a quota-limited bot comment does not supply those signatures.
