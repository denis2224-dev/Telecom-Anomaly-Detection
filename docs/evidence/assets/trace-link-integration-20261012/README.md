# Isolated integration evidence

Verified code/tool snapshot: `f572ebd27c71cb809eb43fa51f07f40740ae181c`.
Base: `ae9a53626ac7ee62efec40a19ebb20c6e2cf7049`.
The containing Draft PR head identifies the subsequent evidence publication.

`verification.json` records separate commands, counts, skip reasons, failed
invocations, source identities and release blockers. `input-identity.json`
proves the full reactor and final tool snapshot share committed runtime/test/
contract inputs and records the Windows V016 packaging correction.

`test-reports.zip` preserves complete XML from each independently collected
Java invocation. `reports/*/summary.json` includes suite counts, source XML
hashes and individual skipped cases. Focused overlapping counts are separate.
`logs/*.gz` contain full command output; decompress before comparing each
original log SHA in `verification.json`. No truncated console excerpt replaces
the retained log. Failed invocations remain failed in the command records.

`github-initial.json`, `github-final.json`, `ci-*.json`, `reviews-final.json`
and `lineage*.json` preserve the observed GitHub state. `*-resolution.patch`
and merge-tree outputs retain actual conflict findings. `source-changed-files.txt`
and `publication-changed-files.txt` give exact path/status lists against base.

`outputs/` contains fresh native/projection, arithmetic, legacy parity and replay
results. `packaged-integration/producer-examples.jsonl` preserves eight original
producer payloads; its verification report gives source path, line, time, event
ID and hashes. `owner-transport.jsonl` is the bounded eight-window CLI artifact;
the replay command reports EXACT_REPLAY. `compatibility-probe/result.json`
records expected live/offline rejection, not connected acceptance.

`artifact-manifest.json` hashes every artifact except itself. Validate from the
repository root with this read-only Python command:

```powershell
python -c "import hashlib,json,pathlib; p=pathlib.Path('docs/evidence/assets/trace-link-integration-20261012'); m=json.loads((p/'artifact-manifest.json').read_text()); assert all(hashlib.sha256((p/x['path']).read_bytes()).hexdigest()==x['sha256'] for x in m['files']); print('PASS',len(m['files']),'artifact hashes')"
```

New-domain Kafka stays DISABLED, projection delivery stays PENDING_REVIEW,
receiving-owner results stay NOT_VERIFIED, and overall release stays BLOCKED.
