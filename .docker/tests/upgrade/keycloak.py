"""The Keycloak bundled with the repository (.docker/keycloak, the same image and realm import as docker-compose.yaml) as the
OIDC provider of a lane environment. It replaces the stand-in issuer in the keycloak scenario only."""
import base64
import json
import re
import urllib.error
import urllib.parse
import urllib.request

from lane_support import LANE, http, wait_for

IMAGE = re.search(r"image: (keycloak/keycloak:\S+)", (LANE.parents[2] / "docker-compose.yaml").read_text(encoding="utf-8")).group(1)
REALM, CLIENT, SECRET = "openid-federation", "openid-client", "th1s1s4s3cr3tth4tMUSTb3ch4ng3d"
REALM_DIR = LANE.parents[1] / "keycloak"
ISSUER = f"http://keycloak:8080/realms/{REALM}"


def _form(url, fields):
    request = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode(), method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{url} answered {error.code}: {error.read().decode()[:200]}") from error


def start(env):
    """Starts Keycloak under the network alias keycloak, which is the frontend URL the realm declares, and waits for the realm."""
    env.run("keycloak", IMAGE, ["--network-alias", "keycloak", "-v", f"{REALM_DIR}:/opt/keycloak/data/import/:ro"],
            {"KC_BOOTSTRAP_ADMIN_USERNAME": "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD": "admin", "KC_HEALTH_ENABLED": "true"}, command=["start-dev", "--import-realm"])
    discovery = f"{env.url('keycloak')}/realms/{REALM}/.well-known/openid-configuration"
    env.report.anchor(wait_for(lambda: http("GET", discovery, timeout=5)[0] == 200, 240), "Keycloak serves the imported realm")
    set_access_token_typ(env, False)  # the earlier admin image accepts typ JWT only


def access_token(env):
    """A client credentials access token of the realm client, exactly as Keycloak issues it."""
    return _form(f"{env.url('keycloak')}/realms/{REALM}/protocol/openid-connect/token",
                 {"grant_type": "client_credentials", "client_id": CLIENT, "client_secret": SECRET})["access_token"]


def claims(token):
    part = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def header(token):
    part = token.split(".")[0]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def set_access_token_typ(env, at_jwt):
    """Switches the client between typ at+jwt and typ JWT through the Keycloak admin API, the setting an operator changes."""
    base = env.url("keycloak")
    admin = _form(f"{base}/realms/master/protocol/openid-connect/token",
                  {"grant_type": "password", "client_id": "admin-cli", "username": "admin", "password": "admin"})["access_token"]
    listing = http("GET", f"{base}/admin/realms/{REALM}/clients?clientId={CLIENT}", admin)
    client = json.loads(listing[1])[0]
    client["attributes"]["access.token.header.type.rfc9068"] = "true" if at_jwt else "false"
    answer = http("PUT", f"{base}/admin/realms/{REALM}/clients/{client['id']}", admin, body=client)
    env.report.anchor(answer[0] in (200, 204), f"Keycloak accepts the client setting ({answer.describe()})")
