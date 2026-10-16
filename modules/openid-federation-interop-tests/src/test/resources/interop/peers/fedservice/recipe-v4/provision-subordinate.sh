#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "usage: provision-subordinate.sh ENTITY_ID CA_BUNDLE STATE_DIR" >&2
  exit 64
fi
entity_id=$1
ca_bundle=$2
state_dir=$3
case "$ca_bundle" in
  /run/peer-ca/*) ;;
  *) echo "CA bundle must be mounted under /run/peer-ca" >&2; exit 64 ;;
esac
case "$state_dir" in
  /var/lib/fedservice) ;;
  *) echo "state directory must be /var/lib/fedservice" >&2; exit 64 ;;
esac
[ -f "$ca_bundle" ] || { echo "CA bundle does not exist" >&2; exit 66; }
[ -d "$state_dir" ] || { echo "state directory does not exist" >&2; exit 66; }
python - "$entity_id" <<'PY'
import ipaddress
import sys
from urllib.parse import urlsplit

u = urlsplit(sys.argv[1])
if (u.scheme != "https" or not u.hostname or u.username or u.password or
        u.query or u.fragment or u.path not in ("", "/")):
    raise SystemExit("entity ID must be an HTTPS origin without credentials or extra URL parts")
try:
    ipaddress.ip_address(u.hostname)
except ValueError:
    pass
else:
    raise SystemExit("entity ID must use DNS and certificate hostname verification")
if u.hostname == "localhost" or u.hostname.endswith(".invalid"):
    raise SystemExit("entity ID must use the provisioned interop DNS name")
PY

# Upstream get_info defaults to TLS verification and verifies the retrieved
# Entity Configuration signature before emitting subordinate metadata/JWKS.
# REQUESTS_CA_BUNDLE selects the mounted CA through idpy-oidc's requests transport.
# Do not pass upstream's -k/--insecure flag.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
REQUESTS_CA_BUNDLE="$ca_bundle" python /opt/fedservice/setup_federation/get_info.py \
  --subordinate_info "$entity_id" > "$work/subordinate.json"
python /opt/fedservice/setup_federation/add_info.py \
  --source "$work/subordinate.json" \
  --target "$state_dir/subordinates"
