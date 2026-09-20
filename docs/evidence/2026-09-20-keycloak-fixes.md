# Keycloak login fixes — verification

Date: <actual verification date>
Application commit tested: <commit after the fixes>
Infrastructure/frontend configuration: <commit or relevant versions>

| Check | Observed result |
|---|---|
| Angular dashboard and signed-out routes | <result> |
| Repeated provisioning preserves analyst UUID | <result> |
| Real Keycloak login returns to dashboard | <result> |
| Authenticated /api/auth/me | <status and expected fields, no secrets> |
| Dashboard refresh and assets | <result> |
| CSRF-protected logout and anonymous /me | <result> |
| Focused authentication tests | <counts and result> |
| Existing backend regression suite | <result> |
| Infrastructure verification | <result> |

Known limitations or remaining failures: <list, or state none within this scope>