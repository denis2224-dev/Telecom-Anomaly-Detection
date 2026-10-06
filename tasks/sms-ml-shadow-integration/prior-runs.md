# Preserved earlier replay attempts

All inputs and logs remain in their original ignored directories. The classifier and cutoff were unchanged; these were delivery verification runs, never model selection.

- Run 1: **ABORTED**. Unpooled test datasource made finalization too slow; no model selection. Directory: `tmp/sms-ml-shadow-work/replay-20261006-1`.
- Run 2: **FAILED_DELIVERY**. Old serving connection cap and request timeouts; failure statuses preserved. Directory: `tmp/sms-ml-shadow-work/replay-20261006-2`.
- Run 3: **HARNESSTIMEOUT**. All 11424 predictions OK and rule parity passed; the 24-minute API wait expired during delivery. Directory: `tmp/sms-ml-shadow-work/replay-20261006-3`.

Hash provenance is recorded in [prior-runs.json](prior-runs.json). No earlier report was overwritten.
