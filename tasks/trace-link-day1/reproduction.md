# Portable Day 1 reproduction

Obtain `Trace_Link_Integration_Pack.zip` and its `.zip.sha256` sidecar from Sergiu.
The archive is prepared locally under `output/trace-link-pr88-review-resolution/`;
it is outside tracked source and has not been published or sent to receivers.
It contains all 24 unchanged supplied pack files. The accompanying
`reference-files.sha256.json` records every original file hash.

Archive SHA-256:
`4d109f9420e900398856ed0800c912bb8994e21430a780b5aa2b6d0afeae6a55`.

From a clean checkout of the PR's final commit, with the received archive in the
current directory, verify before extracting:

```powershell
$expectedHash = (Get-Content -LiteralPath './Trace_Link_Integration_Pack.zip.sha256').Split(' ')[0]
if ((Get-FileHash -LiteralPath './Trace_Link_Integration_Pack.zip' -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Reference archive checksum mismatch'
}
Expand-Archive -LiteralPath './Trace_Link_Integration_Pack.zip' -DestinationPath './reference-input'
$referenceRoot = (Resolve-Path -LiteralPath './reference-input/Trace_Link_Integration_Pack').Path
```

Prerequisites: Python 3.13 with `requirements-dev.txt` and
`services/ml-service/requirements-ml.txt` installed, JDK 21, the repository Maven
wrapper and its dependencies, Node.js, Docker, and `postgres:16.4-alpine`.
Do not regenerate the reference dataset; its supplied files are pinned.
Use installed interpreter/JDK paths appropriate to your machine:

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements-dev.txt -r services/ml-service/requirements-ml.txt
./.venv/Scripts/python.exe scripts/verify-trace-link-pins.py --reference-root $referenceRoot
./scripts/verify-trace-link-day1.ps1 -Python './.venv/Scripts/python.exe' -JavaHome $env:JAVA_HOME -ReferenceRoot $referenceRoot
```

Both verification entrypoints require an explicit reference root. They check
required files and pinned hashes before executing reproduction. The default run
checks contracts, both Python suites, the processor reactor, legacy/geographic
parity and all 54 frozen cases using fresh Java/Python/JavaScript/PostgreSQL exports.
`-Focused` omits broad ML/reactor/legacy regression and must be reported as focused.

PostgreSQL uses a disposable network-none container with no published ports or
application database access. Exports are generated under
`tmp/trace-link-day1-reproduction/` and `services/processor/target/trace-link-java.json`.
The comparator checks exact fixture SHA and contexts, exact counters, and derived
numbers within absolute `1e-9`. Saved historical exports are not fresh execution.

Record reviewer identity, checkout SHA, UTC run time, exact commands, output hashes,
skips and disposition in `handoffs.md`. Archive delivery, receiver acceptance,
real browser acceptance and production integration remain separate gates.
