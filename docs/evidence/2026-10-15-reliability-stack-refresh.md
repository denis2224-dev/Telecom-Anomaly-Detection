# Reliability stack refresh

Observed 15 October 2026, Europe/Chisinau. The reliability draft is based on feature #107 head 95f1cc99abb4c6fdc4043a33bea1dd77562455b6 after integration with merged foundation #103.

The enabled-account filter, historical-capacity repair, protected subscription tests, delivery fencing/startup tests and authenticated export verifier were carried into #107 before rollout. This draft now contains the remaining operational runbooks and dated reliability/restore evidence. The 14 October results retain their original source identities; they are not rewritten as observations of the newer integrated image.

The newer production-source results are in [PR #107 integration](2026-10-15-pr107-integration.md): 205 unit tests (one existing skip), 142 integration tests, current contract/dashboard verification and the packaged historical-database upgrade. Main V001–V012 remain unchanged and V013 preserves both known history tracks. Required final CI remains attached to #107.

No complete release, coworker reproduction, capacity/Grafana/offline acceptance or five-owner sign-off is inferred from this restack. Optional activation flags remain off.
