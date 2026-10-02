# Future VoLTE / SMS scenarios

These are proposals, not current measurements or implemented anomaly types.
The current types remain VOLTE_SETUP_DEGRADATION and SMS_DELIVERY_DELAY.

| Proposal | Evidence/model work required first |
| --- | --- |
| VoLTE radio/access degradation | Validated RRC/bearer episode rules and scenarios with supported evidence. |
| VoLTE transport degradation | Transport/service correlation validation; link status does not prove quality. |
| SMS signaling failure | New validated signaling observations and baseline semantics. |
| SMS reachability failure | Reachability observations and defensible aggregate impact semantics. |
| SMS partner-route failure | Route-specific observations, topology and baseline contracts. |

Protection rerouting, silent transport degradation and busy-hour congestion must not be
represented as measured incidents until supported contracts and scenarios exist.
