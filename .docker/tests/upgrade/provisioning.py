"""Representative data created through the source release's own admin API, so the rows are exactly what that release writes."""
import json

from lane_support import http

ACCOUNTS = ("alpha", "beta")


def identifier(username):
    """The earlier account API accepts an explicit identifier only when it starts with https://."""
    return f"https://lane.example/{username}"


def _id(body):
    try:
        return json.loads(body).get("id")
    except (ValueError, AttributeError):
        return None


def key_request(env, label):
    """Releases of schema class B (0.20.7) sign an entity configuration by the kid of the key, so their keys keep the default
    key reference. The releases after them accept a chosen one, which makes the stored reference differ from the kid."""
    request = {"signatureAlgorithm": "ES256"}
    if env.options.get("source_class") != "b":
        request["kmsKeyRef"] = f"lane-{label}-key"
    return request


def provision_source(env, report):
    """Creates two accounts next to the seeded root, a key for each of the three, one subordinate and the published statements."""
    admin, token = env.url("admin"), env.token()
    subordinate = None
    for username in (None, *ACCOUNTS):
        headers = {"X-Account-Username": username} if username else {}
        label = username or "root"
        if username:
            answer = http("POST", f"{admin}/accounts", token, body={"username": username, "identifier": identifier(username)})
            report.anchor(answer[0] in (200, 201), f"source: account {label} created ({answer.describe()})")
        answer = http("POST", f"{admin}/keys", token, headers, key_request(env, label))
        report.anchor(answer[0] in (200, 201), f"source: key for {label} created ({answer.describe()})")
        if username == ACCOUNTS[0]:
            answer = http("POST", f"{admin}/subordinates", token, headers, {"identifier": identifier("subordinate-1")})
            subordinate = _id(answer[1])
            report.anchor(answer[0] in (200, 201) and subordinate, f"source: subordinate of {label} created ({answer.describe()})")
        answer = http("POST", f"{admin}/entity-statement", token, headers, {})
        report.anchor(answer[0] in (200, 201), f"source: entity configuration of {label} published ({answer.describe()})")
        if subordinate and username == ACCOUNTS[0]:
            answer = http("POST", f"{admin}/subordinates/{subordinate}/statement", token, headers, {})
            report.anchor(answer[0] in (200, 201), f"source: subordinate statement of {label} published ({answer.describe()})")
    counts = env.psql("SELECT (SELECT count(*) FROM Account) || '|' || (SELECT count(*) FROM Jwk) || '|' || (SELECT count(*) FROM Subordinate)").strip()
    report.check(counts == "3|3|1", f"source database holds 3 accounts, 3 keys and 1 subordinate (found {counts})")
    return {"subordinate": subordinate}
