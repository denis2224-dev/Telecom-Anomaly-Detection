# Observability

Prometheus configuration and alert rules live under `prometheus/`. The
application services expose only health and Prometheus actuator endpoints;
authentication, database credentials, and push secrets are not part of the
monitoring configuration.

The monitoring deployment must be wired to a reviewed Grafana and Prometheus
profile before claiming dashboard or production capacity acceptance. Until
then, use the configuration as a reproducible local baseline and keep missing
metrics explicitly unverified.
