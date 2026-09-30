# Security and Boundary Hardening Specification

This document details the boundary hardening, identity model, reverse proxy controls, and database privilege isolation implemented across the platform.

---

## 1. Overview and Security Architecture

The platform implements defense-in-depth across five decoupled boundaries:

```
[ Browser / Client ]
        │
        ▼ (Port 8080)
┌─────────────────────────────────────────────────────────────┐
│ NGINX Reverse Proxy (infra/proxy/default.conf)              │
│ - Canonical Host: telecom.test:8080                         │
│ - Strips untrusted forwarded headers                        │
│ - Security headers (CSP, Frame-Options, nosniff)            │
│ - Method restriction (rejects TRACE/TRACK)                  │
└──────────────┬──────────────────────────────┬───────────────┘
               │                              │
     /auth/*   │                    /api/*    │
               ▼                              ▼
┌──────────────────────────────┐ ┌────────────────────────────┐
│ Keycloak OIDC Provider       │ │ Incident Service (Spring)  │
│ - Realm: telecom             │ │ - PKCE S256 Code Flow      │
│ - Client: telecom-web        │ │ - HttpOnly JSESSIONID      │
│ - PKCE Authorization Code    │ │ - 30-min absolute deadline │
└──────────────┬───────────────┘ │ - CSRF protection          │
               │                 └─────────────┬──────────────┘
               │                               │
               ▼                               ▼
┌─────────────────────────────────────────────────────────────┐
│ PostgreSQL 16 (Isolated Logical Databases)                  │
│                                                             │
│   keycloak_db          incidents_db        processing_db    │
│   (keycloak_app)      (incidents_app)     (processing_app)  │
│         ▲                    ▲                   ▲          │
│   [No Cross-DB]        [No Cross-DB]       [No Cross-DB]    │
│   [Denied DDL]         [Denied DDL]        [Denied DDL]     │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Reverse Proxy Hardening (`infra/proxy/default.conf`)

The edge proxy enforces transport and header hygiene before any request reaches internal services:

### 2.1 Canonical Host and Routing
- The server responds exclusively to `telecom.test:8080` (with local loopback aliases `localhost` and `127.0.0.1` for local administration).
- Direct access to hidden files (`.*`) is blocked with a 404 response.
- Unsupported and dangerous HTTP methods (`TRACE`, `TRACK`) return `405 Method Not Allowed`.

### 2.2 Forwarded Header Sanitization
- External `Forwarded` headers supplied by untrusted clients are stripped (`proxy_set_header Forwarded "";`).
- Standard headers are canonically reconstructed:
  - `Host`: `telecom.test:8080`
  - `X-Forwarded-Host`: `telecom.test:8080`
  - `X-Forwarded-Port`: `8080`
  - `X-Forwarded-Proto`: `http`
  - `X-Forwarded-For`: `$remote_addr` (ensures accurate client IP attribution)

### 2.3 Browser Security Headers
The following headers are appended to all responses:
- `X-Content-Type-Options: nosniff`: Prevents MIME-type sniffing.
- `X-Frame-Options: SAMEORIGIN`: Prevents cross-origin clickjacking while permitting legitimate same-origin framing.
- `Referrer-Policy: strict-origin-when-cross-origin`: Restricts referrer leakage.
- `Permissions-Policy: geolocation=(), camera=(), microphone=()`: Disables unused browser hardware capabilities.
- `Content-Security-Policy`: Restricts resource execution to `'self'`, preventing external script injection.

### 2.4 Open Redirect Mitigation
- `proxy_redirect off;` prevents internal host/port exposure via `Location` header rewrites.
- The `/auth` redirect uses strict relative canonical mapping (`return 308 /auth/;`).

---

## 3. Identity, Session, and Authorization Hardening

### 3.1 Authentication Flow
- **Protocol**: OpenID Connect Authorization Code Flow with **PKCE (S256)**.
- **Client Configuration**: Confidential client `telecom-web` in Keycloak realm `telecom`.
- **Token Containment**: Access tokens and refresh tokens are stored strictly within the backend session store. No tokens are ever sent to or stored in browser storage (`localStorage` or `sessionStorage`).
- **Client Session**: The browser holds only an opaque, `HttpOnly` session cookie (`JSESSIONID`) with `SameSite=Lax`.

### 3.2 Session Lifecycles and Invalidation
- **Idle Expiration**: 15 minutes of inactivity.
- **Absolute Deadline**: 30 minutes from initial authentication (`SessionDeadlineFilter`). Background polling or SSE connections cannot reset or extend this deadline.
- **Session Fixation**: Session IDs are automatically rotated upon successful authentication.
- **Logout Sequence**:
  1. Client sends `POST /logout` with valid CSRF token.
  2. Spring Security invalidates the HTTP session, clears `SecurityContextHolder`, and deletes the `JSESSIONID` cookie.
  3. RP-initiated logout redirects the user to the provider logout endpoint to terminate the Keycloak session.

### 3.3 CSRF and Error Contracts
- **CSRF Token**: Backed by `HttpSessionCsrfTokenRepository` with discovery via `GET /api/auth/csrf`.
- **Mutating Requests**: `POST`, `PUT`, `PATCH`, `DELETE` require the `X-CSRF-TOKEN` header or `_csrf` form parameter.
- **Strict Error Payloads**:
  - `401 UNAUTHENTICATED`: Anonymous access to protected endpoints (`Sign in to continue.`).
  - `403 CSRF_INVALID`: Missing or mismatched CSRF token on mutating requests (`A valid CSRF token is required.`).
  - `403 FORBIDDEN`: Insufficient role permissions or missing analyst entity (`You do not have permission.`).

---

## 4. Database Privilege Isolation

The PostgreSQL deployment isolates operational domains across three logical databases on PostgreSQL 16:

| Database | Migrator Role (DDL Owner) | Runtime Role (DML Only) | External Roles Allowed |
| :--- | :--- | :--- | :--- |
| `processing_db` | `processing_migrator` | `processing_app` | None (`PUBLIC CONNECT` revoked) |
| `incidents_db` | `incidents_migrator` | `incidents_app` | None (`PUBLIC CONNECT` revoked) |
| `keycloak_db` | `keycloak_app` | `keycloak_app` | None (`PUBLIC CONNECT` revoked) |

### 4.1 Strict Cross-Database Denial
`REVOKE CONNECT ON DATABASE processing_db, incidents_db, keycloak_db FROM PUBLIC;` ensures no account can connect to a database unless explicitly granted. Attempts by `processing_app` to access `incidents_db` or by `incidents_app` to access `processing_db` are rejected immediately at connection handshake.

### 4.2 Runtime DDL Denial
- The `app` schema in each database is owned by the respective migrator.
- `REVOKE CREATE ON SCHEMA app FROM :"runtime";` denies runtime roles the ability to execute `CREATE TABLE`, `ALTER TABLE`, or `DROP TABLE`.
- `REVOKE CREATE ON SCHEMA public FROM PUBLIC;` prevents unauthorized public schema sprawl.
- Default privileges grant runtime roles only `SELECT, INSERT, UPDATE, DELETE` on tables and `USAGE, SELECT` on sequences.

---

## 5. Deployment Profiles Comparison

| Security Control | Local Demonstration Profile | Production / Shared Profile Target |
| :--- | :--- | :--- |
| **Origin Protocol** | HTTP (`http://telecom.test:8080`) | HTTPS (`https://telecom.example.com`) |
| **TLS Termination** | Edge proxy termination or host-gateway | NGINX / Cloud Ingress with valid CA cert |
| **HSTS** | Disabled for local loopback | `Strict-Transport-Security: max-age=63072000; includeSubDomains` |
| **Cookie Flags** | `HttpOnly; SameSite=Lax` | `HttpOnly; SameSite=Lax; Secure` |
| **Secrets Management** | `.env` file (local developer workstation) | HashiCorp Vault / Cloud Secret Manager / KMS |
| **Session Storage** | In-memory Spring session | Redis or clustered JDBC session repository |
| **Database Network** | Bridge network with port bindings | Private Docker network; no host port mapping |

---

## 6. Verification and Acceptance Tests

Automated verification ensures security constraints cannot regress:

1. **Database Grants & Proxy Boundary Test**:
   ```bash
   python tests/security/check-db-grants.py
   ```
   Validates role declarations, database ownership, public connect revocation, cross-database access isolation, runtime DDL denial, and proxy security headers.

2. **Backend Authentication & Session Test**:
   ```bash
   ./mvnw -pl services/incident-service test -Dtest=AuthSecurityTest
   ```
   Validates anonymous 401 response, CSRF token discovery and enforcement, PKCE parameter generation, 30-minute session deadline expiration, and cookie deletion on logout.

3. **Contract and Parity Checks**:
   ```bash
   python scripts/check-contracts.py
   ```
