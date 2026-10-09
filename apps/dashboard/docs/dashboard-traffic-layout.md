# Service dashboard layout

The connected overview stacks VoLTE and Roaming on the left and stretches the larger Moldova
map across both panels on the right. Narrow screens use a single column. Existing city selection,
scope navigation, SMS toggle, incident drawer, filters and UTC intervals remain connected.

The header shows attempts, setup success, failed setup share, IMS CPU and open investigations.
VoLTE values come from the latest loaded window for the selected scope and interval. Call results
require complete, consistent numerator/denominator evidence. Missing and incomplete observations
stay unavailable. Counts describe call setup attempts, not distinct subscribers or Erlang traffic.
The donut shows successful and failed attempts; SIP 503 is a separate recorded counter. Other
failure causes are unavailable until the API supplies them.

Roaming remains a labeled sample preview with a chart, country user bars, population shares and
country KPI table. Seven illustrative intercity routes show sample Gbps/utilization on the map.
The links can be hidden; their colors are preview thresholds. They do not claim live traffic or
approved topology membership. City markers still use the existing geography/service evidence.
Required backend additions are recorded in `monitoring-contract-gaps.md`.

## Verification

Run from `apps/dashboard`:

```sh
npm test
npm run build
E2E_PORT=4218 npm run test:e2e -- --workers=2
E2E_CONNECTIONS_LIVE=1 npx playwright test --config playwright.connections.config.ts
```

- 131 unit checks passed. Coverage includes missing/incomplete call evidence, zero volume,
  inconsistent numerator/rate pairs and count conservation.
- 162 browser checks passed across the full suite and an isolated zoom rerun; 19 opt-in checks
  were skipped. The zoom check timed out during a concurrent rebuild and passed in isolation.
- Desktop and phone screenshots were inspected. Country columns fit without clipping; the map
  fills the right column alongside both left panels. Link toggling preserves all city markers.
- Production build passed with a 495.17 kB initial bundle, within the existing size budget.
- The authenticated local API audit stopped at the existing backend's `LOGIN_FAILED` response,
  before protected city/node assertions. Containers were healthy and a read-only comparison
  confirmed the active OIDC client secret matched the identity provider. The cause remains
  unresolved. Real API verification for this revision is incomplete; no backend files changed.

Screenshots and detailed test output are generated under ignored `test-results` directories.
