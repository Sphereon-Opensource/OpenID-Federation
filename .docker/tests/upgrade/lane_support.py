"""Reporting, Docker and HTTP helpers for the upgrade lane.

The reporting follows one rule: a broken artifact must produce FAIL lines and a non-zero exit, never a stack trace.
`check` records and continues, `anchor` records and stops the scenario because everything after it needs the value,
`guard` records and tells the caller to skip the dependent checks of one iteration.
"""
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request

LANE = pathlib.Path(__file__).resolve().parent
MANIFESTS = LANE.parents[2] / "modules/openid-federation-persistence/src/jvmTest/resources/upgrade/manifest"
DB_NAME, DB_USER, DB_PASSWORD = "openid-federation-db", "oidf", "lane-password-7Qx2"
APP_KEY, AUDIENCE, OPERATOR = "Zp4Vn8Rt2Lw6Qx9Cb3Hs7Df1Gk5Jm0Ya", "openid-federation", "upgrade-operator"
ROOT_IDENTIFIER = "https://lane.example"
AT_JWT = "at+jwt"


class Stop(Exception):
    """A structural check failed; the rest of the scenario cannot run."""


class Report:
    def __init__(self):
        self.assertions = 0
        self.failures = []

    def check(self, condition, message):
        self.assertions += 1
        print(("ok    " if condition else "FAIL  ") + message, flush=True)
        if not condition:
            self.failures.append(message)

    def guard(self, condition, message):
        self.check(condition, message)
        return bool(condition)

    def anchor(self, value, message):
        self.assertions += 1
        if value is None or value is False or value == "" or value == []:
            self.failures.append(message)
            print("FAIL  " + message, flush=True)
            raise Stop(message)
        print("ok    " + message, flush=True)
        return value


def docker(*args, input_text=None, check=True):
    result = subprocess.run(["docker", *args], input=input_text, capture_output=True, text=True, encoding="utf-8")
    if check and result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args[:3])} failed ({result.returncode}): {result.stderr.strip()[:400]}")
    return result


class Answer(tuple):
    """The (status, text) of an HTTP answer. A refusal keeps its WWW-Authenticate challenge as an attribute."""

    def __new__(cls, status, text, challenge=""):
        answer = super().__new__(cls, (status, text))
        answer.challenge = challenge
        return answer

    def describe(self):
        """The status, and for a refusal the challenge or the start of the body, so a failure line says why."""
        status, text = self
        detail = self.challenge or text.strip()[:120]
        return f"HTTP {status}" + (f": {detail}" if status >= 400 and detail else "")


def http(method, url, token=None, headers=None, body=None, timeout=30):
    """Returns an Answer, which unpacks as (status, text). An HTTP error status is an answer, a refused connection is not."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return Answer(response.status, response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        return Answer(error.code, error.read().decode("utf-8", "replace"), error.headers.get("WWW-Authenticate", ""))
    except (urllib.error.URLError, OSError):
        return Answer(0, "")


def wait_for(probe, seconds, interval=1.0):
    deadline = time.time() + seconds
    while time.time() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(interval)
    return None
