# Ion Day 5 evidence assets

These assets record verification of source commit `4044327d8af813d728627eba52d4688790b8f7fa`, not approval of a deployed release. **ION DAY 5 BLOCKED; Shared G5 BLOCKED.**

The [manifest](../../2026-10-10-ion-day5-final-manifest.json) identifies the 38 acceptance records, exact execution intervals, source/configuration digests, limitations and SHA-256 hashes. `executions.json` contains the portable suite and method inventories. The compressed logs, reconciliation records and Java exports are intentional evidence artifacts; build directories, executables, caches, databases and raw Surefire XML are excluded.

All `artifactPaths`, `artifactSha256` keys and `logPath` values are repository-relative and available in this package. Fields named `rawEvidenceArchive`, `rawLogArchive` and `rawXmlArchive`, and absolute executable/working-directory values, record the original local execution. They are optional provenance metadata, not clickable reviewer links or prerequisites for reproduction. Raw digests describe those original files; compressed redacted log digests describe the committed `.gz` files. Raw archives are not included and must not be assumed available to another reviewer.

The directory's `.gitattributes` preserves original bytes, including JSON line endings. Verify hashes from the repository root with Python; this needs no original machine paths:

```python
import gzip
import hashlib
import json
from pathlib import Path

root = Path(".")
manifest = json.loads(
    (root / "docs/evidence/2026-10-10-ion-day5-final-manifest.json").read_text(encoding="utf-8")
)
for record in [manifest, *manifest["cases"]]:
    for name, expected in record["artifactSha256"].items():
        artifact = root / name
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == expected, name
        if artifact.suffix == ".gz":
            gzip.decompress(artifact.read_bytes())
print("All packaged artifact hashes match.")
```

For technical reproduction, follow the [runbook](../../../runbooks/ion-day5-release-verification.md) at the recorded source commit. Use each invocation's own counts: focused and full runs overlap. Retain the initial four-error Docker invocation separately from its four-pass rerun, and retain all four reactor skips. Controlled fixture windows and disposable Kafka/PostgreSQL evidence do not satisfy authenticated live, deployment rollback, capacity, independent owner or mentor acceptance.
