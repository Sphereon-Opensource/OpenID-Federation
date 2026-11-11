"""One disposable environment of the upgrade lane: network, PostgreSQL volume, stand-in issuer and application containers."""
import json
import re
import urllib.parse

import keycloak
from lane_support import (APP_KEY, AUDIENCE, DB_NAME, DB_PASSWORD, DB_USER, LANE, MANIFESTS, OPERATOR, ROOT_IDENTIFIER, docker, http, wait_for)


class Environment:
    def __init__(self, project, images, report, options=None):
        self.p, self.images, self.report, self.options = project, images, report, options or {}
        self.label = f"vdx355.lane={project}"
        self.containers = []
        self.public_before = {}
        self.allowed_principal = OPERATOR

    def name(self, role):
        return f"{self.p}-{role}"

    @property
    def issuer_url(self):
        return keycloak.ISSUER if self.options.get("idp") == "keycloak" else f"http://{self.name('issuer')}:8090"

    def start_infrastructure(self):
        docker("network", "create", "--label", self.label, f"{self.p}-net")
        docker("volume", "create", "--label", self.label, f"{self.p}-pgdata")
        self.run("db", self.images["postgres"], ["-v", f"{self.p}-pgdata:/var/lib/postgresql/data"],
                 {"POSTGRES_DB": DB_NAME, "POSTGRES_USER": DB_USER, "POSTGRES_PASSWORD": DB_PASSWORD}, publish=False)
        ready = wait_for(lambda: docker("exec", self.name("db"), "pg_isready", "-U", DB_USER, "-d", DB_NAME, check=False).returncode == 0, 90)
        self.report.anchor(ready, "PostgreSQL accepts connections")
        self.run("issuer", self.images["node"], ["-v", f"{LANE / 'issuer.js'}:/issuer.js:ro"],
                 {"ISSUER_URL": self.issuer_url, "AUDIENCE": AUDIENCE}, command=["node", "/issuer.js"], port=8090)
        self.report.anchor(wait_for(lambda: http("GET", f"{self.url('issuer', 8090)}/jwks")[0] == 200, 60), "the stand-in issuer serves its keys")
        if self.options.get("idp") == "keycloak":
            keycloak.start(self)

    def run(self, role, image, extra, env, command=None, publish=True, port=8080, suffix=""):
        name = self.name(role + suffix)
        args = ["run", "-d", "--name", name, "--network", f"{self.p}-net", "--label", self.label]
        if publish:
            args += ["-p", f"127.0.0.1::{port}"]
        for key, value in env.items():
            args += ["-e", f"{key}={value}"]
        docker(*args, *extra, image, *(command or []))
        self.containers.append(name)
        return name

    def url(self, role, port=8080, suffix=""):
        listing = docker("port", self.name(role + suffix), f"{port}/tcp").stdout.strip().splitlines()
        if not listing:
            raise RuntimeError(f"{self.name(role + suffix)} publishes no port {port} yet")
        mapping = listing[0]
        return "http://127.0.0.1:" + mapping.rsplit(":", 1)[1]

    def common_env(self):
        return {"DATASOURCE_URL": f"jdbc:postgresql://{self.name('db')}:5432/{DB_NAME}", "DATASOURCE_USER": DB_USER,
                "DATASOURCE_PASSWORD": DB_PASSWORD, "APP_KEY": APP_KEY, "KMS_PROVIDER": "memory", "ROOT_IDENTIFIER": ROOT_IDENTIFIER}

    def app_env(self, side, role, database=None, extra_env=None):
        """The environment of one application container. side is source or target, role is server or admin, extra_env is added last."""
        env = self.common_env()
        if database:
            env["DATASOURCE_URL"] = env["DATASOURCE_URL"].replace(DB_NAME, database)
        if role == "admin" and side == "source":
            env["OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI"] = self.issuer_url
        if side == "target":
            env["OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS"] = "3600"
        if role == "admin" and side == "target":
            # The stand-in issuer is a Docker-internal http host. The IDK refuses an http issuer unless it is loopback or this switch is set.
            env.update({"OIDF_OAUTH2_ISSUER_URI": self.issuer_url, "OIDF_OAUTH2_AUDIENCE": "" if self.options.get("idp") == "keycloak" else AUDIENCE,
                        "OAUTH2_CLIENT_ALLOW_INSECURE_HTTP": "true", "OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM": "sub",
                        "OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS": self.allowed_principal})
            if self.options.get("admin_port_env"):
                env["OIDF_SERVER_ADMIN_PORT"] = "8080"
        env.update(extra_env or {})
        return env

    def start_app(self, side, role, suffix="", database=None, extra_env=None):
        """Starts one application container and returns its name."""
        return self.run(role, self.images[f"{side}_{role}"], [], self.app_env(side, role, database, extra_env), suffix=suffix)

    def healthy(self, role, side, suffix="", seconds=120):
        """True once the application answers its health path. False when it exits first or never answers."""
        path = "/status" if side == "source" else "/health"
        name = self.name(role + suffix)

        def probe():
            if docker("inspect", "-f", "{{.State.Status}}", name, check=False).stdout.strip() in ("exited", "dead", ""):
                return "exited"
            try:
                return http("GET", f"{self.url(role, suffix=suffix)}{path}", timeout=5)[0] == 200
            except RuntimeError:  # a restarting container has no published port for a moment
                return False

        outcome = wait_for(probe, seconds, interval=2.0)
        return outcome is True

    def logs(self, role, suffix="", tail=40):
        return docker("logs", "--tail", str(tail), self.name(role + suffix), check=False)

    def explain_not_ready(self, role, suffix=""):
        """Prints why an application never became ready: it exited, or it runs and answers on another port than its health check uses."""
        name = self.name(role + suffix)
        state = docker("inspect", "-f", "{{.State.Status}} exit={{.State.ExitCode}}", name, check=False).stdout.strip()
        print(f"note  {role}{suffix} container: {state}", flush=True)
        if state.startswith("running"):
            for port in (8080, 8081):
                answer = docker("exec", name, "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "3", f"http://localhost:{port}/health", check=False).stdout.strip()
                print(f"note  {role}{suffix} inside the container GET /health on port {port}: {answer if answer not in ('', '000') else 'no answer'}", flush=True)
        logs = self.logs(role, suffix, tail=12)
        for line in (logs.stdout + logs.stderr).strip().splitlines()[-12:]:
            print(f"note    {line[:240]}", flush=True)

    def docker_restart(self, role, suffix=""):
        docker("restart", self.name(role + suffix))

    def docker_exec(self, command):
        """Runs a command in the database container and returns its standard output."""
        return docker("exec", self.name("db"), *command).stdout

    def stop(self, role, suffix=""):
        docker("rm", "-f", self.name(role + suffix), check=False)

    def token(self, subject=OPERATOR, roles=None, typ=None, tenant_id=None):
        """roles=None mints the default admin role, roles='' mints a token without any role. typ=None mints typ JWT,
        which the earlier admin image accepts. The 0.26 admin image accepts only typ at+jwt. tenant_id adds the tenant
        claim that the explicit external identity mode reads. With Keycloak as the provider the token is the realm client's own."""
        if self.options.get("idp") == "keycloak":
            return keycloak.access_token(self)
        query = urllib.parse.urlencode({"sub": subject, **({} if roles is None else {"roles": roles}), **({} if typ is None else {"typ": typ}),
                                        **({} if tenant_id is None else {"tenant_id": tenant_id})})
        status, body = http("GET", f"{self.url('issuer', 8090)}/token?{query}")
        self.report.anchor(status == 200, "the stand-in issuer mints a token")
        return json.loads(body)["access_token"]

    def psql(self, sql, database=DB_NAME):
        result = docker("exec", "-i", self.name("db"), "psql", "-U", DB_USER, "-d", database, "-v", "ON_ERROR_STOP=1", "-At", "-F", "|", "-f", "-",
                        input_text=sql, check=False)
        self.report.anchor(result.returncode == 0, f"psql ran: {result.stderr.strip()[:200]}" if result.returncode else "psql ran")
        return result.stdout

    def manifest(self, *files):
        values = {}
        for file in files:
            for statement in (MANIFESTS / file).read_text(encoding="utf-8").splitlines():
                if statement.strip():
                    row = self.psql(statement).strip().split("|", 1)
                    self.report.anchor(len(row) == 2, f"the manifest query returned a name|value row: {statement[:80]}")
                    values[row[0]] = row[1]
        return values

    def cleanup(self):
        """Removes only what this environment labelled, so another stack on the same Docker host is never touched."""
        for container in docker("ps", "-aq", "--filter", f"label={self.label}", check=False).stdout.split():
            docker("rm", "-f", container, check=False)
        for volume in docker("volume", "ls", "-q", "--filter", f"label={self.label}", check=False).stdout.split():
            docker("volume", "rm", volume, check=False)
        for network in docker("network", "ls", "-q", "--filter", f"label={self.label}", check=False).stdout.split():
            docker("network", "rm", network, check=False)
