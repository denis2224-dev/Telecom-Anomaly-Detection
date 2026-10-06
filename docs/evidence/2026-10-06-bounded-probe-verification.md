# Bounded probe verification

## Scope

This record covers the controlled diagnostic probe adapter, its protocol
separation, worker lifecycle, freshness semantics, and the disposable database
verification path.

## Validation record

| Field | Value |
| --- | --- |
| Revision | `feat/bounded-diagnostic-probes` |
| Configuration | Controlled local inventory; no public targets |
| Fixture | Local HTTP readiness server and patched TCP timeout |
| Timestamp | 2026-10-06 (UTC date) |
| Command | `python -m unittest tests.test_diagnostic_probe -v` |
| Expected behavior | Allowlist rejection, bounded timeout, stale classification, stopped-worker distinction, protocol separation |
| Status | PASS for the focused probe suite; disposable database verification requires a Docker-enabled environment |

The implementation does not delete database volumes or alter existing
migrations. Disposable database verification uses a separate Compose project
name and the existing additive initialization/migration tests.
