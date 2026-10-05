# Authenticated live verification — 2026-10-01

> Historical attempt. The blocked acceptance statuses below are superseded by
> [October 2 authenticated acceptance](2026-10-02-authenticated-acceptance.md):
> ACCEPTED / PASS. SSE intentional reconnect is **NOT DIRECTLY VERIFIED**.

Attempted at approximately 18:52 UTC / 21:52 Europe/Chisinau.
Target: http://telecom.test:8080. Intended account: ion-supervisor.

Browser control is unavailable. The browser/app inventory is empty; the Windows
helper cannot connect to its native pipe (OS error 2); opening an in-app browser
returns `Browser is not available: iab`. The login page could not be opened through
browser control, so no password-entry handoff or authenticated browser action occurred.
The user was asked to reconnect browser control and report when the tab is accessible.
No credential/session extraction or authentication workaround was attempted.

## Retry after the user confirmed an authenticated dashboard

The user subsequently confirmed an authenticated session at
`http://telecom.test:8080/dashboard`. Browser discovery still returned an empty
inventory, and resolving the browser by this exact URL returned
`No browser is available`. The updated computer-use skill was read and the
Windows JavaScript kernel was reset. Reinitialization failed before any UI
interaction: `windows sandbox failed: CreateProcessWithLogonW failed: 2`.

The authenticated session could not be attached to or directly verified. No
login navigation, application restart, session export, credential inspection or
authenticated scenario invocation was performed. The verification statuses
below remain unchanged. Browser-control connectivity/runtime must be restored
before the existing authenticated session can be used.

| Requested verification | Result | Evidence / limitation |
| --- | --- | --- |
| 1. AUTH RESULT | BLOCKED | No controllable browser for manual sign-in. |
| 2. /api/auth/me RESULT | NOT VERIFIED | Anonymous GET is 401; authenticated identity and SUPERVISOR role remain unverified. |
| 3. DASHBOARD RESULT | NOT VERIFIED | Shell HTTP 200; authenticated dashboard and LIVE indicator inaccessible. |
| 4. VOLTE RESULT | NOT VERIFIED | Earlier backend evidence remains recorded separately; authenticated service view inaccessible. |
| 5. SMS RESULT | NOT VERIFIED | Earlier backend evidence remains recorded separately; authenticated service view inaccessible. |
| 6. VOLTE SCENARIO RESULT | NOT VERIFIED | Public authenticated Scenario Runner/API not invoked. Earlier private generator run is not a substitute. |
| 7. SMS SCENARIO RESULT | NOT VERIFIED | Public authenticated Scenario Runner/API not invoked. Earlier private generator run is not a substitute. |
| 8. TELEMETRY GAP RESULT | NOT VERIFIED | Earlier backend gap verification is separate; authenticated UNKNOWN/MISSING UI inaccessible. |
| 9. RECOVERY RESULT | PASS | Repository requires three adjacent HEALTHY windows; earlier persisted live evidence recovered only on the third healthy minute. Authenticated UI remains unverified. |
| 10. INCIDENT DETAIL RESULT | NOT VERIFIED | No authenticated VoLTE or SMS incident detail opened. |
| 11. REST REFRESH RESULT | NOT VERIFIED | No authenticated page reload/REST reconstruction performed. |
| 12. SSE RESULT | NOT VERIFIED | Anonymous stream GET is 401; authenticated delivery and safe reconnect not exercised. |
| 13. SECURITY RESULT | NOT VERIFIED | Anonymous protected APIs are 401, CSRF discovery is 200, code retains CSRF and supervisor/admin scenario authorization. Authenticated security behavior remains unverified. |
| 14. DATA INTEGRITY RESULT | NOT VERIFIED | Earlier persisted null-gap/null-subscriber checks remain valid; actual authenticated UI is inaccessible. |
| 15. BROWSER / UI RESULT | BLOCKED | Browser inventory empty; native helper unavailable; in-app browser unavailable. |
| 16. REMAINING ISSUES | BLOCKED | Restore browser control, then manual password entry and the authenticated acceptance workflow can proceed. |
| 17. READY FOR PR? | BLOCKED — NO | Authenticated live acceptance is incomplete. |

Read-only HTTP checks in this attempt: `/login` 200, `/dashboard` 200,
`/api/auth/me` 401, `/api/services` 401, `/api/incidents/stream` 401,
`/api/auth/csrf` 200, OIDC discovery 200. No auth/CSRF bodies or headers were read.

Policy confirmed from `contracts/policies/service-rules-v2.json` and
`RecoveryPolicy.java`: two adjacent breached windows open an episode; three
adjacent healthy windows recover it. UNKNOWN/GRAY/non-adjacent windows reset
the healthy count. SMS policy includes P95 delay >20,000 ms and >3× baseline
with at least 30 delivered samples, and queue depth >=100 with age >60 seconds.
Recovery P95 is <=10,000 ms and <=2× baseline, with pending age <=30 seconds;
the complete rule evaluator determines eligibility/freshness and combinations.

See [the earlier implementation/backend audit](2026-10-01-volte-sms-service-assurance-audit.md)
for prior evidence. Its private scenario and controlled browser results do not
verify this authenticated acceptance request.

Feature branch and all existing changes preserved. No commit, push, merge or
credential modification performed.
