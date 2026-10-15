# Day 4 response buffering and decoding bounds

Review: [remaining P2 on PR #110](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/110#pullrequestreview-5523528444),
reviewed head `cd0847d459132523055217819c6b4d898ff1142e`. The original four
reproductions now pass, but the reviewer independently demonstrated a 6,000,004-byte
JSON array completing transfer in 109 ms while retaining the worker/permit past
607 ms and returning after 890 ms on Java 21. The prior deadline only bounded
arrival; synchronous unbounded string/tree processing followed it.

## Reproduction and correction

The new real-HTTP regression sends the same `"[" + "{},".repeat(2_000_000) + "{}]"`
body. Before the fix, this Windows/Java 25 run returned MALFORMED_RESPONSE after
267.6214 ms instead of rejecting the oversized body at the boundary. This is a
fresh byte-limit reproduction, not a claim to reproduce the reviewer's exact
Java 21 timing. The failing log is preserved unchanged.

Implementation commit: `8483cca9`. `TraceLinkResponseBody` replaces `ofString()`
with a fixed 16 KiB byte buffer and explicit one-batch demand. Oversized declared
lengths cancel without requesting data; streamed/chunked overflow cancels before
copying the excess chunk. Both 200 and error responses use the same bound.
The completion future includes JSON decoding and success-schema validation,
so the existing remaining 250 ms wait covers that work too. Unfinished exchanges
are cancelled and the permit is released on every exit path.

Before tree allocation, JSON preflight limits processing to 512 tokens, depth 16,
strings/field names of 2,048 characters and numbers of 64 characters. The constrained
mapper also rejects duplicate keys and trailing JSON. Absolute deadline checks
run during collection, per token, and around tree/schema processing. No new
executor or unbounded queue is introduced. The byte/token/depth limits bound work
that cannot be interrupted mid-instruction; this is not a JVM real-time scheduling
guarantee. TIMEOUT remains explicit when the overall wait expires.

Oversized responses record FALLBACK/RESPONSE_TOO_LARGE; malformed or over-complex
JSON records MALFORMED_RESPONSE; neither contains an accepted score. Results keep
their existing immutable job identity and persisted audit format. No schema,
migration, model artifact, truth, seed, deterministic rule or shared flag changed.

## Regression evidence

- Real HTTP covers fixed-length and chunked 200/503 bodies of 6,000,004 bytes.
  The focused run rejected these at 7.6455 / 9.1479 / 5.0323 / 5.7956 ms respectively,
  restored all eight permits, and accepted the next ordinary request in every case.
  Timing is diagnostic, not a performance benchmark or a replacement for the bound.
- A valid response at exactly 16 KiB succeeds; one additional streamed byte rejects.
  Depth, token count, string length, number length, duplicate keys and trailing JSON
  reject without scores and permit reuse succeeds afterward.
- Independent parser tests use error status 503 (no success schema) and valid inputs
  exactly at each complexity boundary. Removing a preflight/parser limit therefore
  cannot hide behind eventual success-schema rejection.
- Subscriber tests prove declared overflow requests zero body batches, streamed
  overflow leaves the excess buffer unread, cancellation occurs, and completion
  cannot start a delayed decoding pass. An expired decoding deadline returns TIMEOUT.
- Real HTTP/PostgreSQL persists a chunked oversized 503 fallback, preserves
  deterministic delivery/state, and retains exactly one result across restart.
  The complete lifecycle failure matrix also includes RESPONSE_TOO_LARGE.
- The current Java client was exercised against the unchanged packaged candidate:
  all three demo domains returned OK, decoded responses were 868/861/839 bytes, and
  observed client times were 49.8667/51.3105/70.2045 ms. These are ordinary fixture
  requests, not held-out model evaluation. The disposable container was removed.

## Verification

Source hashes, red/green logs and fresh test evidence are retained under
`assets/trace-link-day4-body-bounds-20261014/`. Earlier review bundles remain historical.
Independent re-review is required before merge; shared activation stays off.

| Fresh check | Result |
| --- | --- |
| Complete processor/dependency Maven `verify` | BUILD SUCCESS, 13:53 total |
| Processor | 1,117 tests, 0 failures/errors, 8 existing opt-in skips |
| Streaming support / event generator | 139 / 158 tests, 0 failures/errors/skips |
| Client / subscriber / worker tests in the full run | 4 / 4 / 7, all pass without skips |
| Root Python | 174 tests, 0 failures/errors/skips |
| Feature parity | 34 fresh Java/Python vectors, exact integers / 1e-9 floats |
| Contracts and frozen inputs | Pass; all 29 repository/reference pins unchanged |
| Current Java client + real packaged candidate | All three domains OK; all permits restored |

Environment: Windows, Java 25.0.4.1 with compilation release 21, Python 3.13.9,
PostgreSQL 16.4-alpine in disposable test containers. This is not an additional
Java 21 runtime campaign. The same eight external live/replay/archive/V016 opt-ins
remain unexecuted; exact names/reasons and fresh JUnit reports are in the bundle.
No new skip was added. ML Python and candidate files are unchanged; their previous
91-test full result is not re-claimed as fresh here. No new Linux byte-rebuild,
connected-capacity, deployment, or held-out Day 5 evaluation is claimed.

The five implementation/test files are pinned at
`8483cca9d3020a085d3f3e0a8ccbb3d9cf1f6448`. Archived JUnit classes are selected by
the fresh Maven log rather than including stale report files. The independent
packaged-HTTP probe source, exact attack hash/recipe and original raw window are
retained alongside the logs. The original review bundles are not rewritten.
