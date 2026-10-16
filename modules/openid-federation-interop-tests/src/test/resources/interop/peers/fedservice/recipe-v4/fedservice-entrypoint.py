#!/usr/bin/env python3
"""Fail-closed wrapper for the upstream fedservice entity.py test process."""
import ipaddress
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

APP = Path("/opt/fedservice/edu_federation")
STATE = Path("/var/lib/fedservice").resolve()
TLS = Path("/run/tls").resolve()
PEER_CA = Path("/run/peer-ca").resolve()


def fail(message: str) -> None:
    print(f"fedservice runtime config rejected: {message}", file=sys.stderr)
    raise SystemExit(78)


def under(path_text: object, root: Path, label: str) -> Path:
    if not isinstance(path_text, str) or not path_text:
        fail(f"{label} path is required")
    path = Path(path_text)
    if not path.is_absolute():
        fail(f"{label} path must be absolute")
    resolved = path.resolve(strict=False)
    if not resolved.is_relative_to(root):
        fail(f"{label} path must remain under {root}")
    return resolved


def main() -> None:
    if len(sys.argv) < 2:
        fail("entity role argument is required")
    role = sys.argv[1]
    role_dir = (APP / role).resolve()
    if not role_dir.is_relative_to(APP) or not role_dir.is_dir():
        fail("entity role must name a packaged federation role")
    config_path = role_dir / "conf.json"
    if config_path.is_symlink() or not config_path.is_file():
        fail("a regular, mounted role conf.json is required")

    # Do not permit environment overrides to silently replace operator config.
    for name in ("FEDSERVICE_ENTITYID", "FEDSERVICE_WEBCERT_KEY",
                 "FEDSERVICE_WEBCERT_CHAIN", "FEDSERVICE_WEBCERT_CERT",
                 "FEDSERVICE_PORT"):
        if os.environ.get(name):
            fail(f"{name} override is not allowed; set the mounted config")
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy",
                 "https_proxy", "all_proxy"):
        if os.environ.get(name):
            fail("proxy environment is not allowed for this peer")

    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        entity = config["entity"]
        server = config["webserver"]
    except (OSError, ValueError, KeyError, TypeError):
        fail("role config is not valid JSON with entity and webserver sections")

    entity_types = entity.get("entity_type")
    federation_entity = entity_types.get("federation_entity") if isinstance(entity_types, dict) else None
    if not isinstance(federation_entity, dict) or set(entity_types) != {"federation_entity"}:
        fail("entity_type must contain only the native federation_entity block")
    entity_url = urlsplit(entity.get("entity_id", ""))
    if (entity_url.scheme != "https" or not entity_url.hostname or
            entity_url.username or entity_url.password or entity_url.query or
            entity_url.fragment or entity_url.path not in ("", "/")):
        fail("entity_id must be an HTTPS origin without credentials or extra URL parts")
    try:
        ipaddress.ip_address(entity_url.hostname)
    except ValueError:
        pass
    else:
        fail("entity_id must use a DNS name covered by the TLS certificate")
    if entity_url.hostname.endswith(".invalid") or entity_url.hostname == "localhost":
        fail("entity_id must use the provisioned interop DNS name")
    logging_root = config.get("logging", {}).get("root", {})
    if str(logging_root.get("level", "")).upper() not in ("WARNING", "ERROR", "CRITICAL"):
        fail("root logging level must suppress request and metadata INFO/DEBUG output")
    httpc_params = federation_entity.get("httpc_params", {})
    if not isinstance(httpc_params, dict) or httpc_params.get("allow_redirects", False):
        fail("federation_entity HTTP parameters must disable redirects")
    verify = httpc_params.get("verify")
    ca_path = under(verify, PEER_CA, "peer CA bundle")
    if not ca_path.is_file():
        fail("configured peer CA bundle does not exist")
    if server.get("debug") is not False:
        fail("Flask debug mode must be disabled")

    cert = under(server.get("server_cert"), TLS, "server certificate")
    key = under(server.get("server_key"), TLS, "server private key")
    if not cert.is_file() or not key.is_file():
        fail("mounted TLS server certificate and key must exist")
    if os.geteuid() != 0:
        fail("entrypoint must initialize TLS key ownership before dropping privileges")
    try:
        os.chown(key, 10001, 10001)
        os.chmod(key, 0o400)
        os.setgroups([])
        os.setgid(10001)
        os.setuid(10001)
    except OSError:
        fail("could not restrict TLS key access and drop to the unprivileged peer user")

    key_config = federation_entity.get("key_config", {})
    for name in ("private_path", "public_path"):
        path = under(key_config.get(name), STATE, f"key_config.{name}")
        path.parent.mkdir(parents=True, exist_ok=True)
    subordinate = federation_entity.get("subordinate", {})
    if not isinstance(subordinate, dict) or subordinate.get("class") != "idpyoidc.storage.abfile.AbstractFileSystem":
        fail("subordinate storage must use the pinned upstream file-system backend")
    fdir = subordinate.get("kwargs", {}).get("fdir")
    path = under(fdir, STATE, "subordinate.fdir")
    path.mkdir(parents=True, exist_ok=True)

    os.chdir(APP)
    script = APP / "entity.py"
    os.execv(sys.executable, [sys.executable, str(script), role, *sys.argv[2:]])


if __name__ == "__main__":
    main()


