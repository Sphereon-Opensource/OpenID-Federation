"""Lane scenarios for the identity modes and the signing key of the target images: signing, fresh and external."""
import base64
import json

import keycloak as idp
from lane_support import AT_JWT, http
from provisioning import identifier
from scenarios import bring_up_source, compare, retire_source, source_manifest, start_target, upgrade

FEDERATION_ENTITY = {"organization_name": "Lane", "federation_fetch_endpoint": "https://lane.example/fetch", "federation_list_endpoint": "https://lane.example/list"}


def header_kid(jwt):
    """The kid in the protected header of a compact JWS, or None when the text is not one."""
    try:
        part = jwt.strip().split(".")[0]
        return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))).get("kid")
    except ValueError:
        return None


def signing(env, report, options):
    """The signing key of a migrated account. The upgrade selects none. The memory KMS no longer holds its private key, so the
    first key operation fails, and the node recovers once that key is revoked."""
    upgrade(env, report, options)
    admin, server = env.url("admin"), env.url("server")
    token, alpha = env.token(typ=AT_JWT), {"X-Account-Username": "alpha"}
    report.check(env.psql("SELECT count(*) FROM AccountSigningKey").strip() == "0", "the upgrade stored no signing key selection")
    lost = env.psql("SELECT kid FROM Jwk WHERE account_id = (SELECT id FROM Account WHERE username = 'alpha')").strip()
    answer = http("GET", f"{admin}/admin/v1/configuration/statement", token, alpha)
    report.check(answer[0] == 200 and header_kid(answer[1]) == lost, f"the admin API returns the stored entity configuration of alpha, signed by the migrated key ({answer.describe()})")
    answer = http("POST", f"{admin}/admin/v1/keys", token, alpha, {"generate": {"alg": "ES256"}})
    report.check(answer[0] == 500 and "Could not find key for alias" in answer[1], f"with KMS_PROVIDER=memory the migrated key cannot sign any more, a key operation answers 500 ({answer.describe()})")
    answer = http("DELETE", f"{admin}/admin/v1/keys/{lost}", token, alpha)
    report.check(answer[0] == 409, f"the only signing-capable key cannot be deleted ({answer.describe()})")
    env.psql(f"UPDATE Jwk SET revoked_at = (now() at time zone 'utc') WHERE kid = '{lost}'")
    answer = http("POST", f"{admin}/admin/v1/keys", token, alpha, {"generate": {"alg": "ES256"}})
    if not report.guard(answer[0] == 201, f"after the lost key is revoked a key operation succeeds ({answer.describe()})"):
        return
    created = json.loads(answer[1])["kid"]
    report.check(env.psql("SELECT count(*) FROM AccountSigningKey").strip() == "1", "the key operation stored one signing key selection")
    answer = http("GET", f"{server}/alpha/.well-known/openid-federation")
    report.check(answer[0] == 200 and header_kid(answer[1]) == created, f"the public image serves the entity configuration signed by the new key ({answer.describe()})")


def fresh(env, report, options):
    """A fresh install of the target images: schema, legacy account API, key, publication and the public endpoint."""
    env.start_infrastructure()
    report.anchor(start_target(env, report), "the target images start on an empty database")
    admin, server, token = env.url("admin"), env.url("server"), env.token(typ=AT_JWT)
    version = options["schema_version"]
    report.check(env.psql("SELECT version FROM schema_version ORDER BY version").split() == [str(version)], "a fresh database holds only the target schema version")
    report.check(env.psql("SELECT count(*) FROM schema_migration_history").strip() == str(version - 1), "the history lists every migration file")
    answer = http("GET", f"{admin}/accounts", token)
    report.check(answer[0] == 200 and "root" in answer[1], f"the legacy account API lists the seeded root account ({answer.describe()})")
    answer = http("POST", f"{admin}/accounts", token, body={"username": "gamma", "identifier": identifier("gamma")})
    report.anchor(answer[0] == 201, f"the legacy account API creates an account ({answer.describe()})")
    gamma = {"X-Account-Username": "gamma"}
    answer = http("POST", f"{admin}/admin/v1/keys", token, gamma, {"generate": {"alg": "ES256"}})
    kid = report.anchor(answer[0] == 201 and json.loads(answer[1]).get("kid"), f"the admin API generates a key for the new account ({answer.describe()})")
    answer = http("GET", f"{server}/gamma/.well-known/openid-federation")
    report.check(answer[0] == 404, f"nothing is published before the operator publishes a configuration ({answer.describe()})")
    etag = json.loads(http("GET", f"{admin}/admin/v1/configuration", token, gamma)[1]).get("etag")
    answer = http("PUT", f"{admin}/admin/v1/configuration", token, {**gamma, "If-Match": etag}, {"metadata": {"federation_entity": FEDERATION_ENTITY}})
    report.check(answer[0] == 200, f"the admin API publishes the configuration ({answer.describe()})")
    answer = http("GET", f"{server}/gamma/.well-known/openid-federation")
    report.check(answer[0] == 200 and header_kid(answer[1]) == kid, f"the public image serves the entity configuration signed by the new key ({answer.describe()})")
    answer = http("DELETE", f"{admin}/accounts", token, gamma)
    report.check(answer[0] == 200, f"the legacy account API deletes the account ({answer.describe()})")


def external(env, report, options):
    """Explicit IDK/OIDC mode on an upgraded database: the tenant comes from the token claim and the legacy account routes are gone."""
    upgrade(env, report, options)
    alpha = env.psql("SELECT id FROM Account WHERE username = 'alpha'").strip()
    kid = env.psql(f"SELECT kid FROM Jwk WHERE account_id = '{alpha}'").strip()
    env.stop("admin")
    env.start_app("target", "admin", extra_env={"OIDF_IDENTITY_MODE": "external"})
    report.anchor(env.healthy("admin", "target"), "the target admin image reaches readiness in explicit external mode")
    admin, token = env.url("admin"), env.token(typ=AT_JWT, tenant_id=alpha)
    answer = http("GET", f"{admin}/accounts", token)
    report.check(answer[0] == 404, f"external mode does not register the legacy account routes ({answer.describe()})")
    report.check(http("GET", f"{admin}/admin/v1/keys")[0] == 401, "external mode requires a Bearer token")
    answer = http("GET", f"{admin}/admin/v1/keys", token)
    report.check(answer[0] == 200 and bool(kid) and kid in answer[1], f"a token whose tenant_id is the id of the migrated account lists that account's key ({answer.describe()})")
    answer = http("GET", f"{admin}/admin/v1/keys", token, {"X-Account-Username": "beta"})
    report.check(answer[0] == 200 and bool(kid) and kid in answer[1], f"the entity header does not change the tenant that the token selects ({answer.describe()})")
    answer = http("GET", f"{admin}/admin/v1/keys", env.token(typ=AT_JWT))
    report.check(answer[0] in (401, 403), f"a token without a tenant claim is refused ({answer.describe()})")


def keycloak(env, report, options):
    """The bundled Keycloak of the repository as the OIDC provider, before and after the upgrade. The earlier admin image takes its
    tokens (typ JWT, roles claim), the target admin image takes them once the client issues typ at+jwt, which the shipped realm does."""
    env.options = {**options, "idp": "keycloak"}
    bring_up_source(env, report)
    first = env.token()
    print(f"note  Keycloak token header {idp.header(first)}, roles {idp.claims(first).get('roles')}, aud {idp.claims(first).get('aud')}", flush=True)
    env.allowed_principal = idp.claims(first)["sub"]
    retire_source(env)
    before = source_manifest(env, report, options)
    report.anchor(start_target(env, report), "the target images start on the source database with Keycloak as the provider")
    compare(env, report, before, options["schema_version"])
    idp.set_access_token_typ(env, True)
    admin, alpha = env.url("admin"), {"X-Account-Username": "alpha"}
    answer = http("GET", f"{admin}/accounts", env.token())
    report.check(answer[0] == 200 and "alpha" in answer[1], f"a Keycloak token of the shipped realm lists the migrated accounts ({answer.describe()})")
    answer = http("GET", f"{admin}/admin/v1/keys", env.token(), alpha)
    report.check(answer[0] == 200 and '"items"' in answer[1] and '"items":[]' not in answer[1].replace(" ", ""), f"a Keycloak token reaches the admin API of a migrated account ({answer.describe()})")
    idp.set_access_token_typ(env, False)
    answer = http("GET", f"{admin}/accounts", env.token())
    report.check(answer[0] == 401, f"with the client set to typ JWT the target admin image refuses the Keycloak token ({answer.describe()})")
    idp.set_access_token_typ(env, True)
    answer = http("GET", f"{admin}/accounts", env.token())
    report.check(answer[0] == 200, f"with the client set to typ at+jwt it accepts it again ({answer.describe()})")


MODE_SCENARIOS = {"signing": signing, "fresh": fresh, "external": external, "keycloak": keycloak}
