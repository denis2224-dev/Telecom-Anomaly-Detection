# PR #104 correction evidence — 14 October 2026

Fresh verification of implementation `67aa3a50cd742719def549d33590a1fc60ccffe9` against pinned main
`9db3e0e5a61a8062310e560e734a5395758f70b7`. This archive is separate from the
unchanged historical `../trace-link-day3` capacity evidence.

- `verification.json`: results, exact suite/skip inventory, actual incident times and remaining gates.
- `executions.json`: command/cwd/source/exit records, instrumented Maven times and explicitly labeled earlier log-time observations.
- `source-manifest.json`: implementation Git blobs and observed checkout byte hashes.
- `prior-migration-hashes.json`: 26 unchanged main migrations.
- `packed-artifacts.json`: compressed and uncompressed hashes/byte counts.
- `junit-reports.tar.gz`: final reactor and separate receiver suite XML.
- `fresh-produced.json.gz` / `fresh-received.json.gz`: frozen 21-detection input and actual 42-delivery/offset reconciliation, three retained timestamp views and exact latest evidence.
- `*-initial-*.gz` / `*-red.log.gz`: retained failures and environment attempts, separate from successful final logs.
- `package-evidence.py.gz` / `invoke-gate.ps1.gz`: exact local collection/orchestration scripts; paths identify the recorded checkouts.
- `artifact-manifest.json`: all archive files except itself, hashing raw bytes preserved by the local attributes file.

Validate this archive from any checkout retaining the implementation Git history:

```powershell
python docs/evidence/assets/trace-link-day3-corrections-20261014/verify-evidence.py
```

The receiver ran separately and subsequently; recorded elapsed receipt time
includes handoff delay. It is not simultaneous source-to-incident latency or
capacity. Incident views come from the production read service, with HTTP/auth
verification covered by separate maintained integration tests. No long capacity
campaign, external acceptance, remote push/merge, deployment or activation is
claimed. See [the correction report](../../2026-10-14-trace-link-day3-corrections.md).
