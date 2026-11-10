# Upgrading to 0.26

This document covers upgrading an existing installation of the open source OpenID Federation server and admin
server to the 0.26 line. The 0.26 line is built on the Identity Development Kit (IDK). Earlier releases were not.
It states which installed versions can be upgraded and what happens to their data.

Terms: the **source** is the installation you run today. The **target** is the 0.26 line built from this
repository. A **schema class** groups releases whose database migrations have identical content.

## Upgrade matrix

### Sources

All images are published on Docker Hub as `sphereon/openid-federation-server` (public federation endpoints) and
`sphereon/openid-federation-admin-server` (administration), for `linux/amd64`. Both images of one row must be
upgraded together and must run the same version.

| Row | Schema class | Image tags | Official releases | `schema_version` | Path to the target |
|---|---|---|---|---|---|
| A | `507e2e3d` | `0.20.10-beta.06662c4` up to `0.23.1-beta.7f6c091`, which is also `latest` | `0.23.0` | 17 | Direct. Migrations 17 to 29 apply. |
| B | `dae7b01c` | `0.15.3-beta.03c9017` up to `0.20.9-beta.48ddfa1` | `0.16.8`, `0.20.1`, `0.20.4`, `0.20.7` | 17 | Direct. The migrator adds the `Jwk.alg` column these releases never had before it applies the numbered files, and migration 29 fills it. |
| C | older | `0.11.2` and every earlier tag | none supported | 16 or 17 | None. The `0.12.0` changelog states that the data of earlier releases is not carried forward, start from an empty database. |

Reference images for the two rows with the most likely installations:

| Release | Server digest | Admin digest |
|---|---|---|
| `0.23.0` (last official release before 0.26) | `sha256:695d1c2c258d089c42023c987f6979a979781bdfbbd08fc4fcc18d377a90d421` | `sha256:99f1f0fccdbe4921771ae9e57892d849fce0bb78c0fa21f36afdc54ff170855b` |
| `0.23.1-beta.7f6c091` (`latest`, newest image published before 0.26) | `sha256:8d52aaa302a8cc2b3cbdd9a8056e6ecf9c508b3b41d9122ccda54ef7f9af7124` | `sha256:708427920a8d7c1d49288026ad97f2e7e25f0924f6ac410df13c8f743da6a9e7` |

An installation can run an image that is not the last official release. Read the repository digest of what is
deployed (`docker image inspect <image>`, field `RepoDigests`) and the highest `version` in the `schema_version`
table, then place the installation in the table above by image tag. Do not assume the deployed image is `0.23.0`.

Why rows A and B differ: the migration file `2.sqm` was edited after `0.20.9-beta` was published, which added the
`alg` column to the `Jwk` table. A database created by a row B image has no `alg` column although its recorded
schema version is the same 17. A row A image creates the column, and a row B database never receives it from a
later release because its recorded version already equals 17.

### Target

| Item | Value |
|---|---|
| Release family | 0.26.0 |
| Migrations | `1.sqm` to `28.sqm`, plus migration 29 described below. Migrations 1 to 16 are byte-identical to the ones shipped in `0.23.0`. The migrator applies them in one transaction behind an advisory lock. |
| Public image | `sphereon/openid-federation-server`, entry point `./bin/openid-federation-public-server-ktor`, port 8080, health path `/health` |
| Admin image | `sphereon/openid-federation-admin-server`, entry point `./bin/openid-federation-admin-server-ktor`, port 8080 (the image sets `OIDF_SERVER_ADMIN_PORT=8080`, the Ktor default is 8081), health path `/health` |
| Base image | `eclipse-temurin:21-jre` (earlier releases used `openjdk:21-jdk`), users `federation-server` (uid 1001) and `admin-server` (uid 1002) as before |
| Database | PostgreSQL. Versions 16 and 17 are the versions the compose files in this repository have used. Keep the major version of the existing data directory, a data directory written by one major version cannot be opened by another. |
| Legacy account API | module `openid-federation-account-http` on the admin server class path, identity mode `account` |

### Intermediate steps

None are needed for rows A and B. Upgrading through `0.23.0` first does not help row B, because the earlier
runner never repaired the missing column. Row C has no path. A downgrade of the schema is not supported, see the
recovery section.

## Upgrade procedure

Steps 1 to 4 change nothing. Downtime starts at step 5 and ends when step 7 passes. The migration applies thirteen
small files (17 to 29) in one transaction and copies no data, so the time is dominated by stopping and starting the
containers. The scripted lane measured 43 to 61 seconds from stopping the source until both target images answered their
health check, on 8 CPUs with an almost empty database, most of it JVM start. Replace the placeholders in angle brackets with the names of your installation.

### 1. Identify what you run

```bash
docker image inspect <deployed public image> --format '{{index .RepoDigests 0}}'
docker image inspect <deployed admin image> --format '{{index .RepoDigests 0}}'
docker exec <database container> psql -U <user> -d <database> -Atc "SELECT max(version) FROM schema_version"
docker exec <database container> psql -U <user> -d <database> -Atc "SHOW server_version"
```

Place the installation in the matrix above. The highest schema version must be `17`, otherwise stop: the installation
is not one of the supported rows. Note the PostgreSQL major version, the target must keep it.

### 2. Back up

```bash
docker exec <database container> pg_dump -U <user> -d <database> --no-owner > backup-before-0.26.sql
```

Keep with it everything that is not in the database: the environment files, the mounted `/app/config` directory, the
KMS configuration and the vault access of the installation. Rows refer to private keys by `kms` and `kms_key_ref`
only. The `memory` KMS keeps its private keys in the container process, so no backup holds them and any restart of the
source containers already discards them, in every release.

### 3. Get the target images

Pull the images of the release you are upgrading to, `sphereon/openid-federation-server:<tag>` and
`sphereon/openid-federation-admin-server:<tag>`, or build both from the same checkout of this repository:

```bash
docker build -f .docker/openid-federation-server/Dockerfile -t sphereon/openid-federation-server:0.26.0 .
docker build -f .docker/openid-federation-admin-server/Dockerfile -t sphereon/openid-federation-admin-server:0.26.0 .
```

The public and the admin image must come from the same build. Mixing builds is not supported.

### 4. Carry the configuration over

Keep the environment of the installation, the earlier variable names still work (table at the end). Add what the
target needs:

| Setting | Value |
|---|---|
| `OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS` | Required. `3600` is the usual value. |
| `OIDF_OAUTH2_ISSUER_URI` | Admin image. The earlier `OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI` still works. The image refuses to start without an issuer. |
| `OAUTH2_CLIENT_ALLOW_INSECURE_HTTP=true` | Admin image, only when the issuer URL is `http://` on a host other than `localhost`, for example `http://keycloak:8080/realms/...` inside a Docker network. Without it every call answers 401 `IdP discovery failed`. The earlier images accepted such an issuer. Prefer `https://`. |
| `OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM` and `OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS` | Admin image, only when clients select an account with `X-Account-Username`. Set the claim to `sub` and list the token subjects that may do it. The list is empty by default and an empty list answers 403 to the header. |
| `OIDF_DATASOURCE_SCHEMA_MANAGEMENT=verify` | Optional on the public image and on extra admin replicas: the service runs no DDL and refuses to start unless another instance migrated the schema. |

Entry points, ports and health paths of the target images:

| Image | Entry point | Port | Health path | User |
|---|---|---|---|---|
| Public | `./bin/openid-federation-public-server-ktor` | 8080 | `/health` | `federation-server` (uid 1001) |
| Admin | `./bin/openid-federation-admin-server-ktor` | 8080 | `/health` | `admin-server` (uid 1002) |

The earlier admin and public images started with `java -jar` and answered `/status`. The database connection string,
user and password keep their names. The images need no volume of their own: the database volume stays where it is,
`/app/config` is an optional read-only mount, and logs go to `/tmp/logs`. The two uids must be able to write there if
you mount it.

### 5. Stop the source

```bash
docker stop <source public container> <source admin container>
```

Stop both. A source and a target instance running against one database at the same time is not supported.
Writes that reach the source after the backup of step 2 are not in that backup. Once both source containers have stopped, run the
backup command of step 2 once more and keep that file as the restore point: it is the only dump that holds every change the source
acknowledged. The dump of step 2 shows beforehand that the backup procedure works.

### 6. Start the target, admin first

```bash
docker run -d --name <admin container> <the environment of the installation> sphereon/openid-federation-admin-server:<tag>
# wait until it answers: curl -fsS http://<admin host>:8080/health
docker run -d --name <public container> <the environment of the installation> sphereon/openid-federation-server:<tag>
```

With Compose, change the two `image:` tags and run `docker compose up -d openid-federation-admin-server`, then
`docker compose up -d openid-federation-server`. The admin image owns the migration. Starting several instances at
once is safe: they queue on one database lock, one of them applies each file, and the others find the schema current. A
failed migration rolls back completely and the message names the file that failed. Fix the cause and start again.

### 7. Validate

```bash
docker exec <database container> psql -U <user> -d <database> -Atc "SELECT version, migrated_from FROM schema_version ORDER BY version"
docker exec <database container> psql -U <user> -d <database> -Atc "SELECT count(*) FROM schema_migration_history"
curl -fsS http://<admin host>:8080/health
curl -fsS http://<public host>:8080/health
curl -fsS http://<public host>:8080/.well-known/openid-federation
```

The first query returns the `17` row and a `30` row with `migrated_from` 17, the second returns `29`. Compare the
record counts with the ones you took before step 5:

```bash
docker exec <database container> psql -U <user> -d <database> -Atc "SELECT (SELECT count(*) FROM Account), (SELECT count(*) FROM Jwk), (SELECT count(*) FROM Subordinate), (SELECT count(*) FROM EntityConfigurationStatement), (SELECT count(*) FROM TrustMark)"
```

Then call the admin API with a token of the issuer: `GET /accounts` lists every account and `GET /admin/v1/keys` with
the `X-Account-Username` header of one account lists its keys. The repository ships a scripted version of these
checks, `.docker/tests/upgrade/upgrade_lane.py`, that runs the source and the target images against a disposable
database and compares the independent manifests.

### 8. Go back

If the upgrade cannot be completed, restore the backup of step 2 into an empty database and start the source images on
it, see the recovery section. Do not start a source image on the upgraded database.

## Data model mapping

Rows are migrated in place. No identifier is regenerated: UUID primary keys, usernames, entity identifiers, key
references and published statement bytes keep their values. The numbers in the last column are migration numbers.

### Accounts, entities and tenants

| Source (rows A and B) | Target | What happens |
|---|---|---|
| `Account.id` (UUID) | `Account.id`, used as the tenant id when the identity mode is `account` | Unchanged. Every other table points at it with `account_id`. |
| `Account.username` | `Account.username`, unique among active accounts | Unchanged. The `root` account seeded by migration 1 is kept. |
| `Account.identifier` | `Account.identifier` | Unchanged. The root entity identifier is not stored, it comes from configuration (`ROOT_IDENTIFIER`, now `oidf.federation.root.identifier`) and must keep its value. |
| `Account.deleted_at` | same | Unchanged, deleted accounts stay deleted. |
| none | `Account.tenant_source` | Added with the default `account` for every existing row (17). No row becomes `idk`. |

### Keys and KMS references

| Source | Target | What happens |
|---|---|---|
| `Jwk.id`, `account_id`, `kid`, `key` (public JWK), `created_at` | same | Unchanged. |
| `Jwk.kms`, `Jwk.kms_key_ref` | same | The pair that locates the private key in the KMS is unchanged. Old releases wrote the KMS type name in upper case (`MEMORY`, `AZURE`, `AWS`) unless the create request named another value. The target uses the stored value as the KMS provider id when it signs, and its provider registry matches ids exactly. Migration 29 therefore lower-cases exactly these three names and leaves every other value alone, so configure the providers as `memory`, `azure` and `aws`. `kms_key_ref` never changes. |
| `Jwk.revoked_at`, `revoked_reason` | same, plus `expires_at` and `revocation_reason` | `expires_at` is set to `revoked_at` for revoked keys, `revocation_reason` receives `unspecified`, `compromised` or `superseded` when the old text was exactly one of them (23). |
| `Jwk.alg` | same | Present in row A. Row B has no column: the migrator adds it before the numbered files, because those releases shipped a `2.sqm` that was edited afterwards. Migration 29 fills empty values for both rows from the `alg` member of the public JWK. |
| unique index on `(kms_key_ref, account_id)` for active keys | non-unique index | Replaced so that two logical keys can share one physical key during rollover (28). |
| none | `AccountSigningKey` | Selected signing key per account. Empty in every source and left empty by the upgrade, because an upgrade never infers that choice. The standalone admin API (`/admin/v1`) signs with the newest signing-capable key while no selection is stored, which is the key old releases signed with, and writes the selection the first time a key operation changes or confirms the signer. The statement renewal runs through the services commands, which refuse to sign without a stored selection. No key is created. |

KMS material is never stored in the database. `memory` KMS keeps private keys in the process, so they are lost
whenever the container restarts, in every release. This is unchanged by the upgrade. Production installations use
an external KMS (Azure Key Vault or AWS KMS).

### Configuration of the entity

| Source | Target | What happens |
|---|---|---|
| `Metadata` | same | Unchanged. |
| `AuthorityHint`, `Crit` | same | Unchanged. |
| `MetadataPolicy` | same | Rows unchanged. The unique constraint on `(account_id, key)` is replaced by a unique index over active rows (24). |
| none | `AccountMetadataPolicyRevision` | New and empty (24). |
| none | `TrustAnchorHint` | New and empty (18). |
| none | `AdminDocument` | New and empty (27). |

### Subordinates and statements

| Source | Target | What happens |
|---|---|---|
| `Subordinate`, `SubordinateJwk`, `SubordinateMetadata` | same | Unchanged. |
| `SubordinateStatement`, `EntityConfigurationStatement` | same | Unchanged, the signed statement text and `expires_at` are kept and served until they expire. Old releases signed entity configuration statements with a lifetime of one year. The target requires `oidf.federation.statement.lifetime.seconds` and renews stored statements before they expire. |
| none | `SubordinateConstraint` | New and empty (19). |
| none | `ForeignSubordinateStatement` | New and empty (20). |

### Trust Marks

| Source | Target | What happens |
|---|---|---|
| `TrustMarkType`, `TrustMarkIssuer`, `TrustMark`, `ReceivedTrustMark` | same | Unchanged. |
| none | `TrustMarkType.delegation`, `definition`, `version` | Columns added. `delegation` and `definition` are empty and `version` is 1 for existing types (22, 26). |
| none | `TrustMarkTypeRevision` | One row per existing type with version 1 and no definition (26). |
| none | `TrustMarkOwner` | New and empty (22). |

### Logs and version tracking

| Source | Target | What happens |
|---|---|---|
| `Log` | `Log` | Unchanged. |
| `schema_version(version)` | adds `migrated_from`, `migrated_at`, `description` | Existing rows keep their values. |
| none | `schema_migration_history` | New. Filled for migrations 1 to 16 on first start because those were applied before tracking existed. |

### Data types absent from every source

These tables or columns have no source rows, the upgrade creates them empty or with defaults and does not invent
data for them: `AccountSigningKey`, `AccountMetadataPolicyRevision`,
`TrustAnchorHint`, `SubordinateConstraint`, `ForeignSubordinateStatement`, `TrustMarkOwner`, `AdminDocument`,
`Jwk.expires_at`/`revocation_reason` for keys that were never revoked, and `Account.tenant_source` values other
than `account`.

## Recovery

### Backup and restore constraints

These limits hold for every backup you restore and every image you start against a database. The commands that take
and restore a backup follow in the procedure.

- **A backup is tied to the schema version it was taken at.** A backup taken before the upgrade holds the source
  schema (version 17), and starting the target image on it upgrades it again. A backup taken after the upgrade holds
  the upgraded schema and may only be started with the target image. The dump carries `schema_version` and
  `schema_migration_history`, so restore them together with the data and never a subset of the tables.
- **An earlier image does not refuse an upgraded database.** The runner of every release up to
  `0.23.1-beta.7f6c091` migrates only when the stored version is lower than its own (`currentVersion < newVersion`),
  so it starts on a higher one and ignores the signing key selection and the tables added since. That use is not
  supported. To go back, restore the backup taken before the upgrade and start the source image on it.
- **The target image refuses what it cannot use.** A schema version higher than the image needs, and tables with no
  recorded schema version, stop the start with a message that says so. Nothing is changed.
- **A failed migration leaves the database as it was.** The target applies all pending migrations in one
  transaction, so an error or a lost connection rolls everything back to the version the start began at. Fix the
  cause and start again. A restore is only needed when the cause cannot be fixed.
- **Restore into an empty database.** Drop and recreate the database first. The migrator does not repair a database
  that mixes tables from two backups.
- **Stop every instance before a restore.** The advisory lock orders migrations between instances, it does not
  protect a restore that runs under live services.
- **A PostgreSQL data directory is tied to its major version.** Compose files of the `0.23.x` era used
  `postgres:latest`, which resolved to PostgreSQL 17, and `postgres:16` cannot open that volume. Restore a logical
  dump (`pg_dump`) into the version the target compose file pins, or keep the earlier major version until the data is
  dumped.
- **Key material is not in the database.** Rows refer to private keys by `kms` and `kms_key_ref`. Restore the KMS
  configuration, the vault access and any key store file together with the database, otherwise signing fails after
  the restore.

### Interrupted upgrade

Stopping a container, losing the database connection or crashing the host in the middle of the migration leaves the
database exactly as it was before the start, because PostgreSQL discards an uncommitted transaction. Start the admin
image again. There is no half upgraded state to repair and no file to delete. A database that was already at version 30
is recognised on the next start and nothing is applied again.

### Restore

Use this when the upgrade cannot be completed, or to return to the source release.

```bash
docker stop <target public container> <target admin container>
docker exec <database container> psql -U <user> -d postgres -c "DROP DATABASE \"<database>\""
docker exec <database container> psql -U <user> -d postgres -c "CREATE DATABASE \"<database>\""
docker exec -i <database container> psql -U <user> -d <database> -v ON_ERROR_STOP=1 < backup-before-0.26.sql
```

Then start the source images, admin first, with the environment they had. The restored database holds schema version
17 and its original rows. Restore only into an empty database, with every instance stopped, and restore the KMS
configuration with it. Upgrading the restored database again is the same procedure as before.

### Restrictions on earlier images

Never start an earlier image on a database the target has upgraded. The earlier runner migrates only when the stored
version is lower than its own, so it starts on version 30 without complaint, ignores the new tables and writes rows
the target reads differently. The target itself refuses a database that is newer than it is. To go back, restore a
backup taken before the upgrade, never the upgraded database.

## Configuration moved between releases

| Earlier name | Target name | Note |
|---|---|---|
| `ROOT_IDENTIFIER` | `OIDF_FEDERATION_ROOT_IDENTIFIER` | The earlier name still works. Keep the value. |
| `DATASOURCE_URL`, `DATASOURCE_USER`, `DATASOURCE_PASSWORD` | `OIDF_DATASOURCE_*` | The earlier names still work. |
| `OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI` | `OIDF_OAUTH2_ISSUER_URI` | The earlier name still works. The admin server refuses to start without an issuer. |
| `KMS_PROVIDER` | `OIDF_KMS_DEFAULT_PROVIDER` | The earlier name still works. |
| `APP_KEY` | none | Accepted in the environment and read by neither release. |
| none | `OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS` | New and required. Without it nothing can be published. 3600 is the usual value. |
| `X-Account-Username` open to any authenticated principal | `OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS` | The target answers 403 to the header unless the caller's token subject is on this list. The list is empty by default. |

## Legacy accounts and OIDC

### Legacy account clients (the default for an existing database)

An existing database starts in identity mode `account`, the model of the earlier releases: federation entities are
`Account` rows, the seeded `root` account serves the well-known document of `ROOT_IDENTIFIER`, and further accounts are
managed through `/accounts`. Nothing has to be configured for this. It needs the module
`openid-federation-account-http` on the admin class path, which the default admin image has.

What a legacy client keeps, on the migrated accounts and with their stored ids and identifiers:

- `GET /accounts`, `POST /accounts` and `DELETE /accounts` with `X-Account-Username`.
- The `X-Account-Username` header to act on another account, now only for a token subject on the allow-list
  (`OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS`). An unknown account answers 404 with a JSON body, a subject that is not
  on the list answers 403.
- A Bearer token from the OIDC issuer the admin image trusts. No provider ships with the image, any issuer whose
  `iss` equals `OIDF_OAUTH2_ISSUER_URI` and that serves its keys works.

What changes for such a client and cannot be avoided:

| Earlier | Target |
|---|---|
| Anonymous or any authenticated principal may use `X-Account-Username` | Only allow-listed token subjects, the list is empty by default |
| `/keys`, `/keys/{id}` | `/admin/v1/keys`, `/admin/v1/keys/{kid}` |
| `/subordinates` and `/subordinates/{id}/statement` | `/admin/v1/subordinates` and `/admin/v1/subordinates/{entity_id}/statement` |
| `/trust-mark-types` | `/admin/v1/trust-marks` |
| `/entity-statement` | `/admin/v1/configuration` and `/admin/v1/configuration/statement` |
| Statement lifetime fixed by the release | `OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS` is required |
| Access token header `typ` `JWT` | Only `typ` `at+jwt` (RFC 9068) is accepted, anything else answers 401 `Malformed token`. Configure the identity provider to issue it. In Keycloak enable *Use "at+jwt" as access token header type* on the client, the realm bundled in `.docker/keycloak` does. The earlier admin image refuses `at+jwt`, so a rollback needs the setting reverted. |
| The `admin` role in the `roles` claim was required on every path | Roles are not read. A valid token of the configured issuer and audience is enough, and `X-Account-Username` needs an allow-listed subject. Set `OIDF_OAUTH2_AUDIENCE` to a client that only administrators obtain tokens for. |

The root level routes of the earlier releases are not served any more. The complete list of the current routes is in the
published admin OpenAPI document of the target. `/accounts` stays as it was.

### Explicit IDK/OIDC mode

Set `OIDF_IDENTITY_MODE=external` to take the entity from the token instead of from `Account` rows. The tenant id comes
only from the claims of the access token, `X-Account-Username` has no effect, and `/accounts` is not registered. The
database of an upgraded installation can be used in this mode, the tenant id of a token must then equal the `id` of
the account it stands for, because `Account.id` is the tenant id. Nothing else about the upgrade changes.

### Signing after the upgrade

An upgrade stores no signing key selection (`AccountSigningKey` stays empty). The admin API signs with the newest
signing-capable key of the account and stores the selection the first time a key operation confirms or changes it.
The KMS must still hold that key. With a persistent KMS (AWS KMS, Azure Key Vault) it does and the admin API signs without further steps. The scheduled statement
renewal runs through the services commands, which refuse to sign without a stored selection and report `No signing key is
selected for this account`, so run one key operation per account after the upgrade (for example `POST /admin/v1/keys`, which
creates a key, stores the selection and republishes the statement) before the stored statements expire.
With `KMS_PROVIDER=memory` the private key died with the source container, so the stored statements are still served
but every operation that signs, including `POST /admin/v1/keys`, answers 500 `Could not find key for alias`, and
`DELETE` of the only key answers 409 `last_signing_key`. Revoke the lost key row, after which the next key operation
creates a key in the running KMS, stores the selection and republishes the statement:

```bash
docker exec <database container> psql -U <user> -d <database> -c "UPDATE Jwk SET revoked_at = (now() at time zone 'utc') WHERE kid = '<lost kid>' AND account_id = '<account id>' AND revoked_at IS NULL RETURNING id"
```

Do this only for keys whose private key is gone, and name the account too: a `kid` is unique per account only, so a statement that matches the `kid` alone can revoke the key of another account. The statement must return exactly one row. The `memory` KMS is for development, a restart loses its keys in every release.

### Statements stored by 0.20.x and earlier

Schema class B releases signed statements with a JOSE header that has no `alg`, which is not a valid JWS for a strict
verifier. The target serves the stored bytes unchanged, also after the first admin call of an account, and judges them
by their `exp` claim. Renewal and any new publication sign a complete header. Consumers that verify strictly will reject
the old statements until the configuration is published again, so plan that publication after the upgrade with a key
whose private part the KMS holds.

### Diagnostics

| Symptom | Cause | What to do |
|---|---|---|
| The admin or public image exits with status 1 and logs `The persistence layer could not start, stopping: Schema migration from version 17 to 30 failed while applying <n>.sqm` | A migration statement failed, for example a table that already exists with another shape | The message ends with the database error. The migration ran in one transaction and was rolled back, so the database is unchanged at version 17. Remove the cause and start the image again |
| `Schema migration gave up after waiting <n> seconds for a lock` | Another instance is still migrating, or a session holds a lock on a table | Wait for the other work to finish and start again, nothing was changed |
| `The database is at schema version <n>, but this build needs version 30` | A newer release migrated the database | Start the release that did, or restore a backup taken before that upgrade |
| `The database has tables but no recorded schema version` | The tables were not created or upgraded by a supported release | Restore a backup, or record the version you know the schema to be at: `INSERT INTO schema_version (version) VALUES (<version>)` |
| The admin image refuses to start without an issuer | `OIDF_OAUTH2_ISSUER_URI` is empty | Set it, the earlier `OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI` also works |
| Publishing an entity configuration or a subordinate statement fails | `OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS` is not set | Set it |
| 401 from every admin call | The token is missing, expired, or its `iss` or audience is not the one configured | Mint a token for the configured issuer and audience |
| 401 with `error_description="IdP discovery failed"` | The issuer is `http://` on a host other than `localhost` | Use `https://` or set `OAUTH2_CLIENT_ALLOW_INSECURE_HTTP=true` |
| 401 with `error_description="Malformed token"` | The token header `typ` is not `at+jwt` | Configure the identity provider to issue `at+jwt` access tokens |
| 403 with `X-Account-Username` | The token subject is not on `OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS` | Add the subject, or drop the header to act on the root account |
| 404 with `X-Account-Username` | No account has that username | List the accounts with `GET /accounts` |
| Signing fails with `Invalid KMS id <value> provider` | The `kms` of a key row does not name a configured provider | Configure the provider with the id stored in the row. Rows written by the earlier releases hold `memory`, `aws` or `azure` after the upgrade |
