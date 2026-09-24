# Day 05 — voice KPI trend and incident cards

After signing in, open <http://telecom.test:8080/services/VOLTE-MD-CENTRAL>.
Choose a UTC time range. The green line shows actual call success; the dashed
line shows the expected rate. Missing data stays a gap. Each episode gets one
incident card, with technical recovery separate from the analyst's workflow.

- [Graph and history code](../../../apps/dashboard/src/app/features/service-kpi-history)
- [Incident cards](../../../apps/dashboard/src/app/features/incident-investigation/incident-list.component.ts)
- [Day 05 browser tests](../../../apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts)
- [Complete startup and generated scenario](../day-06-protected-voice-investigation/README.md)

For a design-only preview, run `npm --prefix apps/dashboard run start:fixtures`
from the repository root and open <http://127.0.0.1:4200/login>.
The preview is explicitly labelled and does not authenticate. Use port 8080 for
the real protected flow with persisted backend evidence.
