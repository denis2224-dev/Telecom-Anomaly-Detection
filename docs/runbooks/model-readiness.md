# ML model readiness

The ML boundary is packaged in
[`services/ml-service/Dockerfile`](../../services/ml-service/Dockerfile). It
does not receive database, Kafka, OIDC, session, or application credentials.
The processor's deterministic rules remain independent of this service.

## Approved artifact contract

[`model-manifest.json`](../../services/ml-service/model-manifest.json) records:

- `modelVersion`
- `featureVersion`
- the local artifact path
- the expected SHA-256 digest

[`check-manifest.py`](../../infra/model/check-manifest.py) rejects a missing
manifest, missing artifact, malformed checksum, checksum mismatch, or feature
version mismatch. The ML readiness endpoint returns HTTP 503 and
`{"status":"DOWN"}` for those failures. This makes a missing or corrupt model
visible without disabling deterministic processor behavior.

The committed manifest intentionally references an owner-supplied artifact that
is not in Git. To enable model readiness locally, place the approved artifact
at `services/ml-service/models/approved-model.bin`, update the manifest digest,
and start:

```bash
docker compose up -d ml-service
docker compose exec ml-service \
  python /app/check-manifest.py --manifest /app/model-manifest.json --feature-version 1
```

## Resource and concurrency limits

Compose applies `ML_CPU_LIMIT` (default `1.0`), `ML_MEMORY_LIMIT` (default
`512M`), and `ML_CONCURRENCY` (default `1`). The service uses a bounded
semaphore so concurrent requests above the configured limit receive HTTP 503
rather than causing unbounded work.

The current service is a readiness/metrics boundary only; scoring implementation
and measured model latency remain Sergiu's handoff.
