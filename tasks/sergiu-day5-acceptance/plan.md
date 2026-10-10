# Rusu Serghei — Day 5 detector acceptance

## Approved PR #86 reconciliation

Rebase the existing branch onto pinned main `9e8501b627a00d59f995e410e35d08ceb0ada149`.
Preserve canonical DetectionAuthority, worker transactions/leases/order, historical
missing-baseline handling, FeatureContext and inferred service absence. Drop the
superseded DetectionEvidence helper and old migration assertion changes. Keep the
unique Day 5 acceptance cases and rename their persisted exporter Day5GeographicParityTest.

Add real database activation/replay coverage across all twenty city scopes,
including SMS queue-only inferred absence, strict receipt selection and a failing
CONTRACT_ONLY Spring startup. Keep canonical frozen-input parity and expose the
broader Day 5 export through `--geographic-persisted`, requiring an explicit Java
export for either geographic mode. Use explicit ML_SERVICE_URL for HTTP acceptance.

Run focused tests and historical-guard mutation checks, then full contracts,
Python, processor verification, both parity matrices and packaged-model profiles.
Pin an integrated code SHA and regenerate counts/hashes/evidence. Preserve the
original candidate manifest as historical evidence. Update PR #86 with a guarded
force-with-lease against captured remote head `76945124ae93ad6a6fd853887f8adc2732bc08fe`.
Shared authenticated runtime/owner acceptance remains BLOCKED; no merge is planned.

## Original implementation plan (historical)

Implement the approved plan against starting revision
`cd5b013d979e51e27ff1d4b1c1fff3693a2f4e79` without changing frozen policy,
feature order, model artifacts or strict V2 payloads.

1. Record baseline hashes and restore the local Java 21/Docker test runtime.
2. Prove city detector failures, then implement pinned role/source selection,
   opt-in geographic peer baselines and backwards-compatible Python context.
3. Add fresh persisted geographic parity exports and acceptance cases for all
   twenty scopes; preserve pending legacy windows and replay identities.
4. Run contracts, Python, processor reactor and packaged/fallback ML checks.
5. Publish exact results and a case ledger; distinguish local verification,
   authenticated live evidence and owner review. Missing evidence blocks G5.

Optional power correlation is NOT_IN_SCOPE. Core negative cause controls remain
mandatory. No owner signature or shared release acceptance is inferred from tests.
