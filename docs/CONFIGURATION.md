# OpenID Federation configuration

OIDFed configuration goes through the **IDK config pipeline** (`AppConfigService` / property sources) and the
typed binder [`OidfConfigBinder`](../modules/openid-federation-services-impl/src/commonMain/kotlin/com/sphereon/openid/fed/services/config/OidfConfigBinderImpl.kt).
Application code must not call `System.getenv` for product settings.

## Preferred: property / YAML files

YAML is loaded by **IDK `lib-conf-yaml`** (APP, tenant, and principal scopes) into
`AppConfigService` / session config services — **not** by a custom OIDFed YAML parser.

| File | Who loads it |
|------|----------------|
| `reference.properties` / `reference.conf` | OIDFed file tier (packaged defaults) |
| `application.properties` | OIDFed file tier (legacy flat) **or** IDK properties sources |
| `application.yaml` / `application.yml` | **IDK** `YamlFileAppPropertySource` (config dir default `./config`, env `SPHEREON_CONFIG_LOCATION`) |
| `application-{profile}.yml` | **IDK** profile overlay |
| `config/tenant/{tenantId}/tenant.yml` | **IDK** tenant YAML (User/session scope) |
| `config/principal/...` | **IDK** principal YAML |

See [application.yaml.example](../application.yaml.example) and
[config/application.yaml.example](../config/application.yaml.example).

### Minimal `application.properties`

```properties
oidf.federation.root.identifier=http://localhost:8080
oidf.datasource.url=jdbc:postgresql://localhost:5432/openid-federation-db
oidf.datasource.user=openid-federation-db-user
oidf.datasource.password=openid-federation-db-password
oidf.oauth2.issuer.uri=http://localhost:8082/realms/openid-federation
oidf.identity.mode=account
oidf.kms.default.provider=memory
```

### Minimal `application.yaml`

```yaml
oidf:
  federation:
    root:
      identifier: http://localhost:8080
  datasource:
    url: jdbc:postgresql://localhost:5432/openid-federation-db
    user: openid-federation-db-user
    password: openid-federation-db-password
  oauth2:
    issuer:
      uri: http://localhost:8082/realms/openid-federation
  identity:
    mode: account
  kms:
    default:
      provider: memory
```

Flat dotted keys and nested YAML both normalize to the same `oidf.*` property names.

## Still supported: environment variables

Env vars are ideal for secrets, container orchestration, and CI. They override file defaults.

| Precedence (highest first) | Source |
|----------------------------|--------|
| 1 | IDK `AppConfigService` pipeline (includes IDK `EnvPropertySource`, cloud/K8s contributions when present, and OIDFed [OidfEnvBridgePropertySource](../modules/openid-federation-common/src/jvmMain/kotlin/com/sphereon/openid/fed/common/config/OidfEnvBridgePropertySource.kt) for **legacy** SCREAMING_CASE aliases) |
| 2 | Programmatic `DefaultAppMapPropertySource` (KMS bootstrap, tests) |
| 3 | OIDFed env lookup (`getEnvironmentVariable` — IDK Env + legacy aliases) |
| 4 | Classpath / file defaults (`OidfFilePropertySource`) |
| 5 | Hardcoded `OidfConfigDefaults` |

### IDK-normalized env (preferred when using env)

Property key `oidf.federation.root.identifier` → env `OIDF_FEDERATION_ROOT_IDENTIFIER`.

### Legacy env (JVM, still supported)

| Legacy | Property key |
|--------|----------------|
| `ROOT_IDENTIFIER` | `oidf.federation.root.identifier` |
| `DATASOURCE_URL` / `USER` / `PASSWORD` / `DB` | `oidf.datasource.*` |
| `OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI` | `oidf.oauth2.issuer.uri` |
| `KMS_PROVIDER` | `oidf.kms.default.provider` |
| `ADMIN_SERVER_PORT` / `SERVER_PORT` | `oidf.server.admin.port` / `oidf.server.federation.port` |
| `DEV_MODE` / `APP_DEV_MODE` | `oidf.federation.dev.mode` |
| … | See [LegacyEnvMappingPropertySource](../modules/openid-federation-common/src/jvmMain/kotlin/com/sphereon/openid/fed/common/config/LegacyEnvMappingPropertySource.kt) |

Full docker-oriented list: [.env.example](../.env.example).

## Programmatic maps

Tests and embedders can inject keys:

```kotlin
DefaultAppMapPropertySource.addProperty("oidf.identity.mode", "external")
```

Commercial EDK/VDX hosts may also contribute cloud config, Kubernetes ConfigMaps, etc., as additional
IDK `PropertySourceContribution`s — the same binder sees them without OIDFed changes.

## Tenant / principal overrides

### IDK session scopes (automatic)

IDK already cascades configuration by scope:

| Scope | Service | When |
|-------|---------|------|
| APP | `AppConfigService` | Process / app graph |
| TENANT | `TenantConfigService` | User/session with tenant |
| PRINCIPAL | `PrincipalConfigService` | User/session with principal |

Host or commercial stacks that contribute tenant/principal property sources get overrides when
session code reads `TenantConfigService` / `PrincipalConfigService`. OIDFed `getEffective*` methods
accept an optional session config so bare keys (e.g. `oidf.kms.default.provider` on tenant sources)
win over APP defaults.

### Tenant-overridable settings

| Setting | APP key | Tenant catalog `oidf.tenant.<id>.*` |
|---------|---------|-------------------------------------|
| Root entity URL | `oidf.federation.root.identifier` | `…federation.root.identifier` |
| KMS provider | `oidf.kms.default.provider` | `…kms.provider` |
| Cache localities | `oidf.cache.*.locality` | `…cache.*.locality` |
| Header principal claim | `oidf.identity.account.header.principal.claim` | `…identity.account.header.principal.claim` |
| Header allow-list | `oidf.identity.account.header.allowed.principals` | `…identity.account.header.allowed.principals` |

### Client offline Trust Chain freshness (optional)

Applied when verifying a pre-built chain offline (`trust_chain` header / `verifyOfflineTrustChain`),
**in addition to** statement `iat`/`exp`. Empty values = disabled (default).

| Property | Meaning |
|----------|---------|
| `oidf.client.offline.trust.chain.max.age.seconds` | Reject if `now - max(iat)` exceeds this |
| `oidf.client.offline.trust.chain.min.remaining.seconds` | Reject if `min(exp) - now` is below this |
| `oidf.client.offline.trust.chain.clock.skew.seconds` | Skew for both bounds (default `5`) |

```yaml
oidf:
  client:
    offline:
      trust:
        chain:
          max:
            age:
              seconds: "3600"       # 1 hour max snapshot age
          min:
            remaining:
              seconds: "60"         # at least 1 minute remaining
```

Preset for code: `OfflineTrustChainPolicy.SHORT_LIVED_DEFAULT` (3600 / 60).

**Still APP-only:** ports/host, datasource, `identity.mode`, OAuth2 issuer/audience, CORS, logger.

```yaml
oidf:
  tenant:
    acme-corp:
      federation:
        root:
          identifier: https://acme.example
      kms:
        provider: azure
      cache:
        http:
          resolver:
            locality: DISTRIBUTED_ONLY
      identity:
        account:
          header:
            principal:
              claim: preferred_username
            allowed:
              principals: ops-acme,bot-acme
```

Use `getTenantConfig` / `getEffectiveFederationConfig` / `getEffectiveKmsConfig` /
`getEffectiveIdentityConfig` / `getEffectiveCacheLocality`.

## App graph property sources

1. `OidfConfigEnvironment.install()` — process environment as the config-system env tier
2. `OidfConfigBootstrap.seed(...)` — OIDFed **reference** defaults + KMS maps
3. `create*AppGraph` → `initRootScopeProvider` + `ensurePropertySourcesRegistered` — IDK
   contributions including **`yaml.app`** (`lib-conf-yaml`), **`oidf-legacy-env`**, etc.
4. Session open — IDK tenant/principal YAML contributions when those files exist

Resolution: `OidfPropertyResolution` / `OidfConfigBinder` on top of `AppConfigService` (and
session config for effective tenant keys). No parallel OIDFed YAML engine.

## Identity and OAuth2

See [IDENTITY_AND_IDK_ALIGNMENT.md](IDENTITY_AND_IDK_ALIGNMENT.md). Admin always requires a Bearer JWT and a non-blank
`oidf.oauth2.issuer.uri`.

## File-only (zero OIDFed env) Docker

```bash
docker compose -f docker-compose.file-only.yaml up --build
```

Admin and federation containers have **no** `env_file` / `OIDF_*` environment. They load
`/app/config/application.yaml` from [config/application.file-only.yaml](../config/application.file-only.yaml).
Postgres and Keycloak still set their own image bootstrap env in compose (not product config).

In-process (Gradle):

```bash
./gradlew :modules:openid-federation-integration-tests:fileOnlyConfigTests \
  :modules:openid-federation-integration-tests:jvmTest \
  --tests "com.sphereon.openid.fed.integration.tests.platform.FileOnlyConfigE2eTest"
```

## KMS provider resolution

Key creation uses [resolveEffectiveKmsProviderId](../modules/openid-federation-services-impl/src/commonMain/kotlin/com/sphereon/openid/fed/services/config/OidfEffectiveKms.kt):
request body `kms` → session tenant bare `oidf.kms.default.provider` →
`oidf.tenant.<id>.kms.provider` → APP default. Signing uses the provider stored on each key row.

## Regression tests

Env compatibility is locked by:

```bash
./gradlew :modules:openid-federation-common:jvmTest \
  :modules:openid-federation-services-impl:jvmTest \
  --tests "com.sphereon.openid.fed.common.config.*" \
  --tests "com.sphereon.openid.fed.services.config.OidfConfigBinderEnvRegressionTest"
```

Architecture guard (no `System.getenv` in module sources): `NoSystemGetenvArchTest`.
