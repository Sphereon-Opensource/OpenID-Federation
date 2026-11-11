# Upgrade fixtures

Fixed inputs for the migration upgrade tests. They describe a database the way a supported source release leaves
it, so a test can upgrade it with the current code and compare the result. Treat the files as immutable. A new
source row means new files, not edits.

## Source rows

| Row | Schema class | Built from |
|---|---|---|
| A | `507e2e3d` (`0.20.10-beta` up to `0.23.1-beta.7f6c091`, official `0.23.0`) | `frozen/v0.23.0/1.sqm` to `16.sqm`, seed `02-jwk-row-a.sql` |
| B | `dae7b01c` (`0.15.3-beta` up to `0.20.9-beta`, official `0.16.8` to `0.20.7`) | the same files but `frozen/v0.20.7/2.sqm` replaces `2.sqm`, seed `02-jwk-row-b.sql` |

Row B differs from row A only because `2.sqm` was edited after `0.20.9-beta`: row B has no `Jwk.alg` column.

## Files

| Path | Content |
|---|---|
| `frozen/` | Byte copies of the shipped source migrations. `provenance.json` records tag, commit, git blob id and a SHA-256 over the LF-normalised bytes of every file. |
| `seed/01-accounts.sql` | Eight accounts: the seeded `root`, trust-anchor, trust-mark-issuer, leaf-op, a deleted `retired` and an active account reusing the username `retired`, `dormant` (only revoked keys) and `keyless` (no keys). |
| `seed/02-jwk-row-a.sql`, `02-jwk-row-b.sql` | Ten keys. Includes the same `kid` and the same `kms_key_ref` under different accounts, a revoked key whose reason is exactly one of the three tokens (after trim and lower case) and one that is free text, and in row A one key whose `alg` column is empty while its public JWK carries `alg`. |
| `seed/03-entity-data.sql` | Subordinates (the same identifier under two accounts, one deleted), their keys, metadata and statements, entity configuration statements, metadata, authority hints, critical claims, Trust Mark types, issuers, issued and received Trust Marks, metadata policies and log rows. All identifiers are fixed UUIDs. |
| `manifest/preserved.sql` | Queries whose values must be identical before and after the upgrade: row counts and a digest of the surviving columns of every table, plus relationship digests spelled with account usernames. `Jwk.kms` is left out of the key digest because the upgrade rewrites it, and is covered by its own expected value. |
| `manifest/expected-*.sql` | Queries run on the source database only. They state what the upgrade must produce: no signing key selection at all (the services publish only with a key an operator selected, so the count stays zero), revocation tokens, one initial revision per Trust Mark type, the algorithm per key, and the KMS provider id per key (the legacy enum names `MEMORY`, `AWS` and `AZURE` in lower case, anything else unchanged). |
| `manifest/actual.sql` | Queries run after the upgrade whose names mirror the expected ones with the prefix `actual.`. |

The suites that consume them are `SchemaMigratorUpgradeTest`, `SchemaMigratorRestartTest` and `SchemaMigratorRecoveryTest`.
`FrozenMigrationFixturesTest` checks the frozen files against `provenance.json`.

Every manifest query returns one row of `name` and `value`. The seed and manifest files hold one statement per
line and no semicolon inside a string, so any statement splitter reads them.

## Building a source database

Mirror the release that wrote it: create `schema_version`, apply the frozen migrations in order, insert the
version row `17`, then apply the seed files in the order of their numbers. The frozen files are SQLDelight
sources, so remove the `import` line and the ` AS kotlin.String` annotations before executing them as SQL.
