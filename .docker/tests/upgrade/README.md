# Image level upgrade lane

Runs the real source images and the real target images against one disposable PostgreSQL volume and checks that the
data survives. The source images create their own data through their admin API, the target images take the volume
over, and the result is compared with the SQL manifests of the persistence module
(`modules/openid-federation-persistence/src/jvmTest/resources/upgrade/manifest`).

The OIDC provider is not part of the upgrade, so a minimal stand-in issuer (`issuer.js`, started in a `node` container)
mints the Bearer tokens the admin images validate. It ships in no image.

## Prerequisites

- Docker, with access to the pinned source images and the locally built target images.
- Python 3.9 or later. The lane uses the standard library only.
- The target images built from this checkout, see the build commands in `docs/UPGRADE.md`. To test a build that the build
  coordinator packaged (the `installDist` equivalent distributions under `modules/*/build/distributions`), build diagnostic
  candidates around them with `build_candidates.py --server-distribution <tar> --admin-distribution <tar>`. Each candidate
  is the runtime stage of its product Dockerfile with the copy from the builder stage replaced by the unpacked
  distribution, so the base image, health check, user, environment and entry point are the product's own. Candidates are
  never release images.

## Run

```bash
python .docker/tests/upgrade/upgrade_lane.py \
  --source-server sphereon/openid-federation-server@sha256:695d1c2c258d089c42023c987f6979a979781bdfbbd08fc4fcc18d377a90d421 \
  --source-admin  sphereon/openid-federation-admin-server@sha256:99f1f0fccdbe4921771ae9e57892d849fce0bb78c0fa21f36afdc54ff170855b \
  --target-server <locally built public image> \
  --target-admin  <locally built admin image> \
  [--schema-version 30] [--source-class a|b] [--report lane-report.json] [scenario ...]
```

The digests are the `0.23.0` images (schema class A). For the `0.16.8` to `0.20.7` images, whose `Jwk` table has no `alg`
column, pass `--source-class b`. Without scenario names every scenario runs. `selftest` needs no images and proves
that a broken run ends in `FAIL` lines and a non-zero exit instead of a stack trace.

| Scenario | What it proves |
|---|---|
| `upgrade` | Source images provision accounts, keys, a subordinate and statements. The target images start on the volume. All preserved manifest values are equal, the derived values are what the source rows require, every migration file is recorded once, and the legacy account API, the admin API and the public endpoint answer. |
| `restart` | Two restarts of each target image replay no migration and rewrite no history row. |
| `blocked` | A migration that fails (a conflicting table) stops the target with the failing file named, leaves the source schema untouched, and finishes after the cause is removed. |
| `simultaneous` | Two admin images and the public image started at the same moment against the source database apply each migration file once. |
| `restore` | A plain SQL backup restored into new databases runs under the source image (rollback) and upgrades again under the target image. |
| `old-on-upgraded` | Records what the source image does on an upgraded database (unsupported either way) and shows the target refusing a database whose schema is newer. |
| `signing` | After the upgrade no signing key selection exists. With `KMS_PROVIDER=memory` the migrated key cannot sign (the private key died with the source container), so a key operation answers 500 and the last key cannot be deleted. After the lost key row is revoked a key operation succeeds, stores the selection and the public image serves the statement signed by the new key. |
| `fresh` | The target images on an empty database: schema version, history, legacy account API, key generation, publication of a configuration and the public endpoint. |
| `external` | Explicit `OIDF_IDENTITY_MODE=external` on the upgraded database: no `/accounts`, a token is required, the tenant comes from the `tenant_id` claim, the entity header is ignored. |
| `keycloak` | The Keycloak bundled with the repository (same image and realm import as `docker-compose.yaml`) as the OIDC provider, before and after the upgrade, including the client setting `at+jwt` that the target requires. |
| `guide` | The fenced commands of `docs/UPGRADE.md` (identify, back up, stop, start, validate, restore) executed with the lane's names, and the outcomes the guide states checked. |

Every scenario creates its own labelled network, volume and containers and removes only those afterwards. `--keep`
leaves them for inspection.
