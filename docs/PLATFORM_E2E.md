# External e2e: in-process IDK authorization server and admin

Historical filename: `PLATFORM_E2E.md`. The product mode name is **external**
(`OIDF_IDENTITY_MODE=external`). The alias `platform` is still accepted in config.

External auth tests boot both pieces in the same JVM:

1. IDK OAuth2 Authorization Server (`services-oauth2-as-rest` / `IdkOauth2AsFixture`)
2. OIDFed admin (`createAdminServerAppGraph` + `configureAdmin` / `ExternalInProcessFixture`)

There is no standalone external admin process and no Keycloak, Auth0, or Azure AD requirement for these tests.

```
┌────────────────────────────────────┐
│ Test JVM                           │
│  ┌──────────────┐  Bearer JWT     │
│  │ IDK OAuth2 AS│ ──────────────► │ ┌──────────────────┐
│  │ (ephemeral)  │  tenant_id      │ │ OIDFed admin     │
│  └──────────────┘                 │ │ external + JWT   │
│         │ discovery / JWKS        │ │ (ephemeral port) │
│         └─────────────────────────┼►│ JwtAuthentication│
│                                   │ └──────────────────┘
└────────────────────────────────────┘
         PostgreSQL (same as account integration tests)
```

## Why this setup

External mode trusts the authorization server for subjects, tenants, and delegation. The e2e suite therefore mints
real access tokens (with `tenant_id` and related claims) from an in-process IDK AS and calls the real admin graph. That
is designed to verify JWT validation, fail-closed behavior without a tenant claim, mutation authorization, and tenant isolation
without disabling admin authentication.

Anonymous admin / no-auth configuration is not available. Tests always present a Bearer token for protected routes.

## Run

### Dependency: `services-oauth2-as-rest`

External tests **consume** `com.sphereon.idk:services-oauth2-as-rest` (and related OAuth2 AS modules) as a normal IDK
dependency — the same way as any other `com.sphereon.idk` artifact. **Publishing that AS is IDK / VDX-infra ownership**,
not an OpenID-Federation build or release task.

OIDFed resolves it like other IDK modules (`settings.gradle.kts`: `mavenLocal`, then open-source snapshots/releases).
If your local cache cannot resolve the coordinate, use whatever path your org already uses for IDK SNAPSHOTs (remote
repo or a local IDK install). No Keycloak is required for this suite; the AS and admin run in-process (see the fixtures).

### Tests

```bash
# OIDFed. Postgres on localhost:5432 (override DATASOURCE_URL if needed)
./gradlew :modules:openid-federation-integration-tests:platformIntegrationTests \
  :modules:openid-federation-integration-tests:jvmTest \
  --tests "com.sphereon.openid.fed.integration.tests.platform.*"
```

Clean CI expectation for **this** project: Postgres + Gradle, with IDK artifacts (including the AS jar) already
resolvable from the same repositories as the rest of IDK.

| Test | What it covers |
|------|----------------|
| `IdkOauth2AsFixtureTest` | AS only: health, discovery, mint |
| `ExternalAuthApiTest` | Full stack: 401 without token, mutation with AS Bearer, isolation, bad token, no `/accounts` |
| `AccountHeaderAllowListApiTest` | Account mode: allow-list fail-closed and allowed rebind (related suite) |

Datasource defaults to `jdbc:postgresql://localhost:5432/openid-federation-db`. Docker hostname `db` is rewritten to
`localhost` for host-side runs.

## Account mode suite

Admin is always authenticated in account mode as well. By default, when `ADMIN_SERVER_BASE_URL` and
`FEDERATION_SERVER_BASE_URL` are unset, `AccountInProcessFixture` boots in-process:

1. IDK OAuth2 AS (mints Bearer tokens)
2. Account-mode admin (`requireAuth=true`)
3. Federation public server (protocol endpoints)

`createAuthenticatedAdminClient()` attaches Bearer tokens. Entity selection is JWT-first session plus optional
`X-Account-Username` rebind when the principal is allow-listed. Business isolation uses `SessionExecution.tenantId`.

To point at an already running topology, set `ADMIN_SERVER_BASE_URL` and `ADMIN_BEARER_TOKEN`, and optionally
`FEDERATION_SERVER_BASE_URL`. The issuer must match the token-minting AS.

```bash
# Full suite (in-process AS + admin + federation when URLs are unset)
./gradlew :modules:openid-federation-integration-tests:integrationTests \
  :modules:openid-federation-integration-tests:jvmTest \
  --tests "com.sphereon.openid.fed.integration.tests.*"
```

External tests do not require a second packaging topology for CI.

## Host IdPs

Commercial IdPs remain optional host concerns for real deployments. Automated tests use the IDK AS only, in-process.
When embedding OIDFed behind your own OIDC issuer, configure `OIDF_OAUTH2_ISSUER_URI` (and audience if needed) the
same way Compose does for Keycloak in account-mode local stacks.

For product semantics of account versus external, root entity URL mapping, and STS or delegation, see
[IDENTITY_AND_IDK_ALIGNMENT.md](./IDENTITY_AND_IDK_ALIGNMENT.md) and the identity section of the root README.
