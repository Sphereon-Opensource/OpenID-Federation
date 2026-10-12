# Identity modes and IDK alignment

This document describes how OpenID Federation multi-entity isolation relates to IDK tenants and principals, and where
OIDFed deliberately does not reimplement IDK capabilities. For a shorter product-facing overview, see the identity
section in the root [README](../README.md).

## Target security model

Both identity modes **authenticate with a trusted OAuth2 or OIDC access token first**. The admin API has no
unauthenticated product path. Earlier admin-specific “allow anonymous admin” / no-auth configuration was removed
for security. The generic `OIDF_OAUTH2_JWT_AUTH_ENABLED` setting remains, but disabling JWT authentication is
unsupported for the admin server and is rejected at startup.

Request handling on the admin server follows this order (no throwaway validation session):

1. KotlinInject bootstraps a User and Session (fixed or root bootstrap principal).
2. IDK `JwtAuthentication` validates the Bearer token with the **request** `SessionScope` `JwtValidationService`.
3. A post-JWT identity plugin stamps validated claims, then aligns the session:
   - **external:** 401 if no usable tenant claim; otherwise bind session tenant and principal from the token.
   - **account:** bind JWT tenant or root Account; if `X-Account-Username` is present and the principal is
     allow-listed, rebind to that Account id (403 if not allowed, 404 if the account is unknown).
4. Business `TenantContextResolver` reads only `SessionExecution.tenantId`.

| Concern | Account | External |
|---------|---------|----------|
| Authentication | Bearer required | Bearer required |
| Entity switch | `X-Account-Username` when principal is allow-listed | None via headers |
| Who may use the header | Config allow-list (default empty means deny). Match claim `account.header.principal.claim` (default `sub`). Explicit `*` means any authenticated principal (tests only). | Not applicable |
| Act as another tenant | Header after JWT, if allowed | Token only; fully trust the AS |
| Root | Seeded `root` Account plus `/accounts` | No root-account login. `external.root.tenant.id` maps which token tenant owns the federation root **entity URL** |
| Config values | `account` (alias `legacy`) | `external` (alias `platform`) |
| **Default when mode unset** | Upgrade (existing DB) **or** `account-http` on classpath | Greenfield install **without** account modules |

### Why account-mode rebind exists

The JWT proves the caller is an allowed operator (typically already in the root account context). Only then may that
caller switch federation entity context with `X-Account-Username`. Rebind is a controlled privilege of the standalone
multi-entity UX. It is not a substitute for authentication.

### Why external mode has no root login and no header switch

External mode assumes an external authorization server owns subjects, tenants, and **who may act as whom**. If tenant
A may operate as tenant B, the AS mints a token whose tenant claims (and any actor or delegation claims the AS uses)
already say so. OIDFed validates the token against the configured issuer, binds `SessionExecution.tenantId` from
those claims, auto-provisions a federation row under that tenant id when needed, and never elevates or switches
tenant from client-controlled headers.

## Account mode (standalone, pre-0.25.0 model)

Account mode is the historical open-source multi-entity model. Use it for **upgrades from develop / pre-0.25** and
for all-in-one packaging that ships `openid-federation-account-http`.

Isolation uses the `Account` table: domain rows carry `account_id` foreign keys. Operators manage entities with
`GET` / `POST` / `DELETE /accounts`. The seeded **root** account uses `oidf.federation.root.identifier` /
`ROOT_IDENTIFIER` as its entity identifier. Other accounts use `{root}/{username}` or an explicit identifier.

Business resolution goes through `AccountBasedTenantContextResolver` (selected by `ModeAwareTenantContextResolver`).
Authentication is always Bearer. Entity selection after JWT uses the account header only when the principal is on the
allow-list.

## External mode (host AS / OIDC)

External mode is for host or platform embeddings and is the **default for new empty databases when account modules
are not on the classpath** (`OIDF_IDENTITY_MODE=external` or leave mode unset on a greenfield platform host).

Isolation still uses the same database key space, but the tenant id is the **session tenant from the JWT**. Account
REST route descriptors are omitted. The account handler returns 410 if it is nevertheless dispatched; callers
must not rely on one HTTP status for an omitted route. OIDFed does not manage parties or natural-person users. First contact with a new
token tenant can auto-provision via `FederationTenantProvisioner` (`tenant_source = idk`). Business resolution uses
`SessionTenantContextResolver`, which reads only `SessionExecution.tenantId`.

### Root entity URL versus token tenants

Do not confuse the federation **root entity identifier** (a public URL that hosts `/.well-known/openid-federation`)
with a privileged login.

In **account** mode the seeded username `root` is both a database account and the default entity context for that URL.

In **external** mode there is no privileged root login inside OIDFed.
`oidf.identity.external.root.tenant.id` / `OIDF_EXTERNAL_ROOT_TENANT_ID` (alias `platform.root…`) only answers: which
validated session tenant id maps to the federation root entity identifier URL? When this setting is absent,
the compatibility tenant id `default` maps to the root URL. Other tenants typically get
`{root}/tenants/{tenantId}` unless the host overrides that layout.

### Delegation and STS

Impersonation, on-behalf-of, and token exchange are authorization-server concerns. When the AS issues a token for a
target tenant (with whatever actor or delegation claims it supports), OIDFed consumes the resulting validated tenant
claims and does not implement a separate impersonation API.

## Configuration

| Property | Environment | Values / notes |
|----------|-------------|----------------|
| `oidf.identity.mode` | `OIDF_IDENTITY_MODE` | `account`, `external` (aliases `legacy`, `platform`). **If unset**, see auto default below |
| `oidf.identity.account.header.principal.claim` | `OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM` | JWT claim for the header allow-list (default `sub`) |
| `oidf.identity.account.header.allowed.principals` | `OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS` | Comma list; default empty denies rebind; `*` is any authenticated principal (tests only) |
| `oidf.identity.external.root.tenant.id` | `OIDF_EXTERNAL_ROOT_TENANT_ID` | Token tenant that owns the federation root entity URL (alias `platform.root…`) |
| `oidf.oauth2.issuer.uri` | `OIDF_OAUTH2_ISSUER_URI` | Required for admin; blank issuer fails startup |

### Auto default when mode is unset (`IdentityModeDefaults`)

| Situation | Effective mode |
|-----------|----------------|
| Explicit `account` / `external` / aliases | That value |
| Existing database (`schema_version` &gt; 0 or legacy `Account` table) — **upgrade from develop** | **account** |
| New empty DB + `openid-federation-account-http` on classpath | **account** |
| New empty DB + no account modules | **external** |

Persistence calls `IdentityInstallState.markExistingDatabase` before migrations so upgrades keep ACCOUNT without
operators setting env. Prefer setting `OIDF_IDENTITY_MODE=account` explicitly on known upgrades for clarity.

### develop → 0.25 migration (config + DB)

| Area | What happens |
|------|----------------|
| **DB** | SQLDelight migrations run on startup (`schema_version` / `*.sqm`). Pre-existing data is preserved; empty schema is a greenfield install. |
| **Config** | Prefer `oidf.*` / `OIDF_*`. Legacy JVM env aliases still map via the env bridge. Unset identity mode → **account** if the DB already has schema. |
| **Identity** | Pre-0.25 had no mode (account-shaped). Auto default keeps **account** on upgrade. New platform installs without `account-http` get **external**. |
| **Auth** | Admin requires Bearer JWT in both modes (anonymous admin removed). Configure `OIDF_OAUTH2_ISSUER_URI`. |

### How we verify schema upgrade ≡ greenfield

CI / local (Postgres required, same env as product):

```bash
./gradlew dockerStartDb
./gradlew :modules:openid-federation-persistence:jvmTest --tests "*.SchemaMigrationEquivalenceTest"
```

`SchemaMigrationEquivalenceTest`:

1. Builds a **greenfield** schema: migrate `0 → Database.Schema.version`.
2. Builds an **upgrade** schema: migrate `0 → baseline (17)` (includes `tenant_source`; pre–TA-hints / pre–SubordinateConstraint), seeds an `Account` row, then migrate `baseline → latest`.
3. Compares **catalog fingerprints** (columns, indexes, FKs) — must be identical.
4. Asserts seeded account still readable and post-baseline tables exist.
5. Asserts `IdentityInstallState` + `IdentityModeDefaults` yield **account** for the upgrade path.

This does **not** dump a live `develop` production database; it uses the in-repo migration series as the only authoritative history. For a real develop snapshot, capture `pg_dump --schema-only` and restore as the baseline in a follow-up fixture if needed.

## Concept map

| Concept | Meaning |
|---------|---------|
| OIDFed Account / tenantId | Federation **entity context** (keys, entity configuration, trust marks for one entity) |
| TenantJwk (OpenAPI; alias AccountJwk) | Managed key record for that entity context. JSON shape stays stable (`accountId`, `kmsKeyRef`, …) |
| OIDFed Subordinate | OpenID Federation trust hierarchy. Not an IDK sub-tenant. |
| IDK Tenant | Platform organizational isolation from JWT or session |
| IDK Party / Identity / Identifier | Natural persons, organizations, credential identities. Managed by the host, not OIDFed. |
| Impersonation / delegation | AS concern (token exchange, actor claims). OIDFed consumes validated tenant claims. |

## Implementation status

| Area | Behavior |
|------|----------|
| Admin authentication | Always Bearer; issuer required at startup |
| Session open | Bootstrap tenant and principal; validated claims are stamped and the session aligned after JWT authentication; ACCOUNT may then rebind via allow-listed header |
| ACCOUNT header | `OidfAccountSessionRebindPlugin` after `JwtAuthentication` |
| EXTERNAL headers | Never used for identity |
| Impersonation | Trust validated `tenant_id` / `tid` / related claims only (AS policy) |
| Throwaway validate session | Removed; `JwtAuthentication` uses the request session |
| Anonymous admin config | Removed |

Business `AccountBasedTenantContextResolver` and `SessionTenantContextResolver` both resolve tenant id from
`SessionExecution.tenantId` (header rebind happens only at admin ingress in account mode).

### JWT authentication details

Admin requires a non-blank `oidf.oauth2.issuer.uri` and installs IDK `JwtAuthentication` with `requireAuth=true` in
both modes. Integration tests mint Bearer tokens via the in-process IDK OAuth2 AS
(`AccountInProcessFixture` / `ExternalInProcessFixture`).

| Server | `requireAuth` | Paths that stay open without a token |
|--------|---------------|--------------------------------------|
| Admin | Always true | `/health`, `/debug/**` only |
| Federation public | False when JWT is installed | Public OpenID Federation protocol endpoints |

```bash
OIDF_IDENTITY_MODE=external
# Primary automated story: in-process IDK OAuth2 AS (services-oauth2-as-rest), not Keycloak
OIDF_OAUTH2_ISSUER_URI=http://127.0.0.1:<as-port>
# optional:
OIDF_OAUTH2_AUDIENCE=openid-federation-admin
OIDF_OAUTH2_JWT_AUTH_ENABLED=auto   # admin supports auto|true; false is rejected at startup
```

Automated external e2e boots the real IDK OP (`createOAuth2AsAppGraph` / `configureOAuth2As` /
`CreateAccessTokenCommand`) through `IdkOauth2AsFixture`. See [PLATFORM_E2E.md](./PLATFORM_E2E.md). Tokens carry
`tenant_id` for isolation. Acting as another tenant means minting a token for that tenant (or whatever delegation
claims the AS issues). OIDFed does not implement AS policy.

**Metro classpath note:** `lib-oauth2-jwt-validation-impl` contributes session-scoped services that depend on
`VerifyJwtCommand` and `FetchAuthorizationServerMetadataCommand`. Those live in `lib-oauth2-server-resource-impl` and
`lib-oauth2-client-impl`, which are only runtime-transitive in the JWT validation POM. Admin and federation Ktor
modules therefore declare them as **implementation** so Metro can aggregate `@ContributesBinding` at compile time.

**Public federation note:** path-based entity selection still uses `resolveTenantIdByName`. Account mode may still use
the account header for multi-entity on admin. The public protocol remains open.

## What IDK already owns (do not duplicate)

| Area | Prefer IDK | OIDFed keeps |
|------|------------|--------------|
| Config pipeline | `AppConfigService`, property sources, protection | Domain keys `oidf.*`, typed `FederationConfig`, legacy env aliases |
| Secrets | `OpaqueSecretResolver`, protected properties | `EnvOpaqueSecretResolver` for standalone; cleartext env still acceptable |
| KMS / JWT | `KeyManagerService`, `JwtService`, KMS providers | `Jwk` domain table (kid / kms ref / revoke per entity) |
| Logging | `Log`, `SessionLogService`, IDK `LoggerConfig` | Optional domain audit `Log` table; tags only |
| Cache | `CacheManager` / `ScopedCache` / `CacheBackend` | IDK types plus portable serializers (`HttpMetadataCacheSerializers`); identity-only local serializers where appropriate |
| JWE | IDK `JweService` | Optional `OidfJweService` / `IdkOidfJweService`; protocol default remains JWS |
| Auth identity | JWT to session tenant and principal | Account-mode header selector only as product mode |

## Config and KMS bootstrap

Call before AppGraph creation (admin and federation `main`):

```kotlin
OidfConfigBootstrap.seed(appId = "...", profile = "default")
```

| Action | Destination |
|--------|-------------|
| Load `reference.properties` / `reference.conf` / `application*.properties` | `OidfFilePropertySource` (below env) |
| Default software KMS `kms.providers.*` | IDK `DefaultAppMapPropertySource` plus principal map |

Resolution order (`OidfPropertyResolution` / `OidfConfigBinderImpl`):

1. Optional IDK `AppConfigService` when the host provides it
2. `DefaultAppMapPropertySource` (explicit and KMS only; not silent oidf defaults)
3. Environment (IDK-normalized names plus legacy aliases on JVM)
4. `OidfFilePropertySource` (classpath and working-dir files)
5. Hardcoded `OidfConfigDefaults`

HOCON uses a minimal flattener for shipped `reference.conf` (comments inside quotes are preserved).

### Secrets

| Mechanism | Usage |
|-----------|--------|
| Cleartext | `OIDF_DATASOURCE_PASSWORD` / `oidf.datasource.password` |
| Opaque id | `oidf.datasource.password.secret.id=db-password` resolves via `OIDF_SECRET_DB_PASSWORD` and `EnvOpaqueSecretResolver` |

Hosts with a real vault should bind IDK `OpaqueSecretResolver` instead of the env resolver.

## External admin mutations

`AdminMutationGuard` (session-scoped) is injected into mutating admin HTTP commands (Create, Delete, Revoke, Set,
Publish, Add, Remove, Clear*). The first line of `doExecute` is:

```kotlin
adminMutationGuard.denyIfUnauthorized()?.let { return it }
```

In account mode the JWT plugin already enforces Bearer authentication; the mutation guard is effectively a no-op for
tenant selection (entity context comes from the session after optional header rebind). In external mode the JWT
plugin plus the mutation guard reject anonymous DI sessions. Reads (GET and list) still require JWT at the plugin
layer.

## SPI entry points

- `TenantContextResolver`: single binding `ModeAwareTenantContextResolver`
- `FederationTenantProvisioner`: `ensureTenantExists` for external (and future provision flows)
- `OidfConfigBinder.getIdentityConfig()`: mode and external root tenant mapping
- `OidfConfigBootstrap` / `OidfPropertyResolution`: config and KMS seed pipeline
- `AdminMutationGuard` / `PlatformAdminAuth`: external fail-closed mutations

## Related modules

- `openid-federation-account-public` / `-impl`: account service, both resolvers, provisioner
- `openid-federation-core-public`: `IdentityMode`, SPI interfaces, config keys, bootstrap
- `openid-federation-admin-server-ktor`: `AdminMutationGuard` on mutation endpoints
- `openid-federation-account-http`: account-mode `/accounts` REST (optional jar)

### Account REST: classpath is the deployment switch

| Classpath | Effect |
|-----------|--------|
| With `openid-federation-account-http` | Metro contributes `/accounts` (still only registered when identity mode is account/legacy) |
| Without that module | Empty account endpoint contribution sets; no account REST handlers. Shared API model types may still be present. |

There is no separate runtime packaging mode flag for production:

- Open-source all-in-one (`admin-server-ktor` in this repo) depends on `account-http` by default.
- Platform host assemblies (IDK / EDK / VDX) depend on admin and federation modules **without** adding
  `account-http`, and usually set `identity.mode=external`.

Optional monorepo smoke only (not a product contract):

```bash
# Compile admin without account-http on the classpath
./gradlew :modules:openid-federation-admin-server-ktor:compileKotlin "-Poidf.omitAccountHttp=true"
# (legacy alias: -Poidf.packaging=platform)
```

`AdminHttpAdapter` injects `Set<AdminAccountEndpointContribution>`. That set is empty without the jar. Tenant
resolvers stay in `account-impl` either way.

### OpenAPI packaging

| Spec | Contents | Who consumes it |
|------|----------|-----------------|
| `admin-server.yaml` (core) | Keys, metadata, subordinates, trust marks, health, cache stats, and related operations. No `/accounts` operations or `accounts` tag. | All hosts; external assemblies advertise only this surface as required |
| `admin-accounts.yaml` (optional) | `GET` / `POST` / `DELETE /accounts` plus `Account` / `CreateAccount` / `AccountsResponse` | Account mode / all-in-one when `account-http` is present; monorepo codegen merges it so Account models still generate |

Runtime registration remains classpath plus `identity.mode=account` (or `legacy`). Codegen builds from a merged temp
spec so account-mode modules keep Account models; the served core contract stays split. External hosts must not treat
Account REST as a required API even if shared model types exist on the classpath.

## Distributed cache (host)

OIDFed uses IDK `CacheManager` / `ScopedCache` with portable JSON serializers for HTTP-resolver metadata
(`HttpMetadataCacheSerializers`). It does not ship Redis or other distributed backends.

Hosts that need multi-node cache should:

1. Register an IDK `CacheBackend` (for example Redis) on the app or session graph before or with `CacheManager`
   construction (same pattern as other IDK embeds).
2. Configure locality for shared namespaces via `oidf.cache.*.locality` (or host CacheRequirements) so values
   round-trip as portable JSON, not identity-only serializers (`IdentityCacheSerializer` is for local-only identity
   namespaces).
3. Keep entity and tenant isolation in cache keys (tenant id plus namespace). Do not share identity material across
   nodes via distributedFallback.

Unit coverage lives in the HTTP resolver / `OidfCache` tests for portable `HttpMetadata` serializers.

## JWE (optional)

The protocol default remains **JWS** entity statements. Optional `OidfJweService` / `IdkOidfJweService` wraps IDK
`JweService` when a product flow needs encrypted payloads. That path is opt-in only and not required for account-mode
CI or external e2e.

## Session open identity (summary)

Admin installs KotlinInject first, then IDK `JwtAuthentication` (request-session `JwtValidationService`). A post-JWT
plugin stamps claims, fails closed for external mode without tenant claims, and aligns the DI session to JWT principal
and tenant. Account mode may rebind further via allow-listed `X-Account-Username`. Business isolation uses
`SessionExecution.tenantId` only.
