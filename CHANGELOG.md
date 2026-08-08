# Changelog

## 0.25.0

### Identity modes: account and external

OpenID Federation now documents and implements two identity modes for multi-entity isolation on the admin API. The
mode is selected with `OIDF_IDENTITY_MODE` / `oidf.identity.mode`.

**Account mode** (`account`, default; alias `legacy`) is the pre-0.25.0 standalone model. Federation entities are
`Account` rows managed through `/accounts`. A seeded **root** account owns the federation root entity identifier
(`ROOT_IDENTIFIER` / `OIDF_FEDERATION_ROOT_IDENTIFIER`). Additional entities are ordinary accounts. After a valid
Bearer token is accepted, operators may switch entity context with `X-Account-Username` only when the authenticated
principal is on a configured allow-list. By default that allow-list is empty, so header rebind is denied until you
list the operators who may switch context. Matching uses a JWT claim (default `sub`) against
`OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS`. A single `*` allows any authenticated principal and is intended only for
tests.

**External mode** (`external`; alias `platform`) is for host or platform embeddings where an OAuth2 or OIDC
authorization server owns subjects and tenants. Tenant isolation comes only from claims on the access token
(`tenant_id`, `tid`, and related IDK claim names). `/accounts` is not registered. `X-Account-Username` is never used
for identity. Tokens without a usable tenant claim fail closed (401) on protected admin routes. On first contact the
server can auto-provision a federation row for the token tenant.

### Root entity versus token tenants (external)

External mode does not provide a privileged root login account inside OIDFed. `OIDF_EXTERNAL_ROOT_TENANT_ID` /
`oidf.identity.external.root.tenant.id` only maps which validated token tenant id owns the federation **root entity
URL**. Other tenants typically receive identifiers under `{root}/tenants/{tenantId}`. That mapping is entity URL
ownership, not an authentication bypass.

### Delegation and STS (external)

Acting as another tenant is an authorization-server concern (token exchange, on-behalf-of / STS, actor or delegation
claims). OIDFed validates the token against the configured issuer and fully trusts the resulting tenant claims. It
does not implement its own impersonation API and does not accept client-controlled headers to change tenant.

### Admin authentication always required

Admin endpoints always require `Authorization: Bearer …` (except health and debug). A non-blank OAuth2 issuer
(`OIDF_OAUTH2_ISSUER_URI` or the JVM alias `OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI`) is required at startup.

The previous anonymous admin / no-auth configuration has been **removed for security**. Related environment variables
and config keys (including `allowAnonymousAdmin` / `oidf.identity.allow.anonymous.admin` and any product path that
disabled JWT requirement on the admin server) no longer exist. Local development and integration tests obtain real
Bearer tokens (Keycloak in Compose, or the in-process IDK OAuth2 AS in automated tests) instead of turning auth off.

### Session and pipeline behavior

Admin request identity uses KotlinInject bootstrap, then IDK `JwtAuthentication` on the request session, then a
post-JWT plugin that stamps claims and aligns or rebinds the session. There is no throwaway validation session.
Business `TenantContextResolver` implementations resolve the entity context from `SessionExecution.tenantId` only.

### Documentation and testing

- README: identity modes, root versus STS, and removal of no-auth configuration
- `docs/IDENTITY_AND_IDK_ALIGNMENT.md`: design and IDK alignment
- `docs/PLATFORM_E2E.md`: in-process IDK AS plus admin for external e2e
- Integration fixtures: `AccountInProcessFixture`, `ExternalInProcessFixture`
- Account header allow-list e2e: fail-closed default and allowed rebind

### Breaking changes (summary)

- Admin authentication can no longer be disabled through configuration.
- Default account-mode header rebind is fail-closed (empty allow-list) until principals are listed explicitly.
- Prefer `account` / `external` mode names; `legacy` / `platform` remain accepted aliases.
- Prefer `OIDF_EXTERNAL_ROOT_TENANT_ID` for the external root entity URL mapping; older `platform.root…` names remain
  as aliases where documented.

## 0.12.0 - 20250321

## WARNING

**This version assumes a clean database! From this version on the software will use migrations into the future and the data in the DB will be taken into account.**
If you have existing data backup/discard it first.

### Documentation

- **Postman Collection:** Added a new Postman collection to the documentation, providing ready-to-use API examples including OAuth2 integration.
- **Enhanced README:** Improved examples and instructions for:
    - Setting up environment variables (including guidance on copying the example file).
    - Deploying with Docker and Docker Compose.
    - Configuring the Key Management System with details for in-memory, AWS, and Azure setups.
- **Clarity and Consistency:** Revised text formatting and clarified descriptions for better readability.
- **Key Management Examples:** Updated examples to show usage of parameters like `kmsKeyRef` and provided guidance on signature algorithms.

### Deployment

- **Docker environment variables:** Unified usage of .env and .env.local files for environment values. Moved .env to .env.example as a user should create a .env or .env.local file
  themselves.
    - WARNING: This potentially means you will have to migrate/create the .env or .env.local file.

### API and codebase

- **UUIDs:** instead of using integers for id values in the DB we now use UUIDs everywhere. This makes it impossible to track or guess data.
- **JSON Payload Updates:** Standardized JSON keys (e.g., updated "dry-run" to "dryRun") in multiple API examples. Everything which is a param or response by default is camelCase.
  OIDF spec properties are snake_case.
- **Multiple Keys:** The system now allows to manage and use multiple keys. You can choose a specific key to sign/publish with using either the `kid` or `kmsKeyRef` request params.
  In the near future support for multiple KMS-es at the same time will also land.
- **Response forward compat:** Response now always return objects with potential arrays in them for list endpoints. This is done to ensure maximum future forward
  compatibility/extensibility
- **Migrations:** The DB should migrate to support the new kmsKeyRef values a user can set for keys. Existing keys will get their `kid` value for the `kmsKeyRef`
- **Dependency updates:** Moved to latest versions of Kotlin serialization, coroutines and ktor
- **Swagger UI:** The admin server now also hosts an OpenAPI UI in the form of Swagger UI at http://localhost:8081/swagger-ui/index.html to easy API testing. It has an integration
  with the default clientId and secret for OAuth2 authentication.
 
