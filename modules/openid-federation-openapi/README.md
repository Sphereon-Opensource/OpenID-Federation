# Open API specs

The Open API specs of OpenID Federation.

## Admin API packaging

| File | Role |
|------|------|
| **`admin-server.yaml`** | **Core** admin contract — keys, metadata, subordinates, trust marks, system, … **No** `/accounts` operations |
| **`admin-accounts.yaml`** | **Optional LEGACY** Account REST (`GET/POST/DELETE /accounts` + Account schemas) |
| **`federation-server.yaml`** | Public federation protocol surface |

Codegen (`openApiGenerateKotlin`) **merges** core + accounts into `build/openapi/admin-merged.yaml` so monorepo LEGACY modules still get `Account` / `CreateAccount` models. Platform hosts should publish/serve **core only** (or omit `admin-accounts.yaml`); runtime `/accounts` exists only with `openid-federation-account-http` + `identity.mode=legacy`.

**`TenantJwk`** (core) is the `/keys` response type (entity-context key record + KMS pointers), not Account CRUD.
JSON property names are unchanged (`accountId`, `kmsKeyRef`, …). OpenAPI and Kotlin keep **`AccountJwk` /
`AccountJwksResponse` as aliases** of `TenantJwk` / `TenantJwksResponse` for historical name resolution.

## Entity Statement

An Entity Statement contains the information needed for the Entity that is the subject of the Entity Statement to 
participate in federation(s). An Entity Statement is a signed JWT. The subject of the JWT is the Entity itself. The 
issuer of the JWT is the party that issued the Entity Statement. All Entities in a federation publish an Entity Statement 
about themselves called an Entity Configuration. Superior Entities in a federation publish Entity Statements about their
Immediate Subordinate Entities called Subordinate Statements.

### Profiles

The Open API generator will generate models, infrastructures and apis by default. To make
it generate models only uncomment `profiles=models-only` from gradle.properties or pass the profile in the comment line.

### Run Open API generator

Generate models, infrastructures and apis:
```shell
gradle clean openApiGenerate
```

Generate only models:
```shell
gradle clean openApiGenerate -Pprofile=model-only
```

Generate the jar file:
```shell
gradle clean build
```
