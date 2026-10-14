# Engineering Tasks and Integration Packs

This directory contains specialized integration test drivers, replay fixtures, and multi-day interface definitions developed during team sprints:

- [`sergiu-day5-acceptance/`](sergiu-day5-acceptance/) — Contains `check_geographic_http.py`, the standalone HTTP detector verification script executed during CI workflow integration.
- [`sms-ml-shadow-integration/`](sms-ml-shadow-integration/) — Contains offline replay drivers, validation datasets, and verification manifests for SMS shadow ML scoring (pinned in `.gitattributes`).
- [`trace-link-day1/`](trace-link-day1/) & [`trace-link-day2/`](trace-link-day2/) — Contains cross-language arithmetic interfaces, handoff contracts, and offline policy reproduction manifests for the Trace Link subsystem.

For user-facing feature guides and service overviews, refer to [`docs/tasks/README.md`](../docs/tasks/README.md).
