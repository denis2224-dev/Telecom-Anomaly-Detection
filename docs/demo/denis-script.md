# Denis backend demonstration

Candidate: use the code, image, API, migration, dataset, policy/topology/model, and identity configuration in the release manifest.

1. Log in as the first real enabled analyst. Show an authenticated inventory query and a persisted synthetic historical series. Explain the dataset label, UTC half-open bounds, display timezone, missing states, and independent period/resolution. Export CSV and compare its exact buckets with JSON.
2. Use the verified shared scenario to open one incident. Record run, event, window, detection, and episode IDs. Explain that one episode may contain multiple immutable detections. Estimated impact is a measured affected population estimate, not a count of unique people.
3. Apply a same-severity update, escalation, UNKNOWN, and recovery. Verify only OPEN, ESCALATED, and RECOVERED notification items. Missing/UNKNOWN does not count as recovery. A supported cause is a qualified hypothesis; it is not proof. Unsupported cause remains undetermined.
4. Assign/reassign with a supervisor or authorized workflow actor. Verify the item targets the new enabled assignee and records the flushed incident version. Resolve the workflow separately from technical recovery. Reissue a stale version and show the optimistic conflict without losing evidence.
5. Log in as a second analyst. Verify read state is private; foreign recipient/subscription mutation fails. Mark one owned item read twice and show idempotence.
6. Replay the same detection and demonstrate unchanged episode/event identity. Open a genuinely new episode and show its separate OPEN item. Restart incident service and reconcile REST after reconnect; show preserved history, audit, read state, and pending delivery outcome.
7. Inspect transport direction, roaming partner/direction/country, mobile-data city, and explicit archived catalogue evidence. A retired city remains historical evidence and is absent from active scenarios/UI.
8. On the approved HTTPS origin, verify denied/default/granted browser permission and one privacy-safe alert if real browser delivery is enabled. Otherwise demonstrate durable in-app history and record push disabled as the effective release decision.
9. Log out. Confirm SSE closes and fresh protected reads fail. Record actual errors, measured limits, and every known limitation.

Repeat the entire script a second time with distinct run IDs on the same release identity. Any code/config/image/dataset identity change invalidates prior rehearsal acceptance.
