# Observability and capacity baseline

This runbook records how to collect a bounded capacity profile without
confusing HTTP transport availability with telecom-service health.

## Instrumentation

Prometheus scrapes the Spring Actuator Prometheus endpoint for the event
generator, processor, and incident service. The configured alerts cover source
freshness, finalization delay, outbox age, ML fallback, API errors, and
application availability. Labels intentionally exclude event, window,
incident, analyst, endpoint, subject, and scope identities.

## Capacity profile

Run the bounded profile against a read-only health or API endpoint:

```sh
python scripts/load-test.py \
  --url http://127.0.0.1:8082/actuator/health/readiness \
  --output tmp/performance-baseline.json
```

The profile uses warmup, steady, burst, and recovery phases. Correlate its UTC
intervals with Prometheus samples for offered, accepted, persisted, finalized,
published, backlog, consumer-lag, database-pool, and API-latency metrics.
Report hardware, image versions, configuration, rates, percentiles, peak
resources, and recovery time together. Do not call the result a capacity limit
until queues return to baseline and every measured boundary is accounted for.

## Gate interpretation

- A service HTTP failure is a platform signal, not evidence that the monitored
  telecom service is impaired.
- A bounded backlog with recovery is distinct from an unbounded queue.
- Missing Prometheus data is an unverified gate, not a zero measurement.
- Browser request/render behavior and authenticated workflows require a
  separate browser run.
