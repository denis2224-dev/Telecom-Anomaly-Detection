# Verification artifacts

These artifacts accompany the implementation commit recorded in `verification.json` and `candidate-pins.json`. Candidate pins cover source, application image IDs/architecture, migrations, contracts, models, baselines, policies, topology, identity configuration, Grafana and actual browser build assets. They do not assert a populated release or owner approval.

`capacity-*.json` and `browser-resources.json` contain actual production-build Chromium measurements with controlled REST/SSE input. `grafana-queries.json` records real isolated Grafana/PostgreSQL queries; arithmetic parity uses bounded controlled rows. `isolated-restore.json` records a real custom PostgreSQL dump/restore between fresh databases. `packaged-ml-http-results.json` records actual HTTP validation in an isolated container network. None of these substitute for the absent full reference archive.

The upstream CI record applies to unchanged model source and inputs at its recorded commit. Full branch CI has not been triggered. The complete Linux ML run's strict Windows-byte assertion failure remains recorded; model artifacts and assertions were preserved.

No secure-origin push acceptance, populated rehearsal, full archive import/restore, offline installation, human review or signature is asserted. Complete release gates remain pending. All artifact hashes are listed in `artifact-index.json`; verify those hashes before reusing the evidence.
