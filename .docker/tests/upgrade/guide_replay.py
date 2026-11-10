"""The operator guide as a scenario. The fenced commands of the upgrade procedure and of the restore section in docs/UPGRADE.md are
executed with the placeholders filled in from a lane environment, and the outcomes the guide states are checked. A placeholder the
lane cannot fill is a failure, so the guide cannot name a thing that does not exist."""
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import time
import uuid

from lane_support import DB_NAME, DB_USER, LANE, docker, http, wait_for
from scenarios import bring_up_source

GUIDE = LANE.parents[2] / "docs" / "UPGRADE.md"
TAG = f"vdx355-replay-{uuid.uuid4().hex[:8]}"
COUNTS = "SELECT (SELECT count(*) FROM Account), (SELECT count(*) FROM Jwk), (SELECT count(*) FROM Subordinate), (SELECT count(*) FROM EntityConfigurationStatement), (SELECT count(*) FROM TrustMark)"


def section_lines(heading):
    """The non-empty lines of the fenced bash blocks under one heading of the guide, up to the next heading."""
    text = GUIDE.read_text(encoding="utf-8")
    start = text.index(heading)
    following = re.search(r"^#{2,3} ", text[start + len(heading):], re.M)
    section = text[start:start + len(heading) + (following.start() if following else len(text))]
    return [line for block in re.findall(r"```bash\n(.*?)```", section, re.S) for line in block.splitlines() if line.strip()]


def find_bash():
    """A POSIX bash. On Windows the bash on the path is often the WSL launcher, so Git for Windows is looked up first."""
    git = shutil.which("git")
    candidates = [pathlib.Path(git).resolve().parents[1] / "bin" / "bash.exe"] if git else []
    candidates.append(pathlib.Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git" / "bin" / "bash.exe")
    return next((str(c) for c in candidates if c.exists()), shutil.which("bash"))


class Replay:
    def __init__(self, env, report, workdir):
        self.env, self.report, self.workdir = env, report, workdir
        self.bash = find_bash()
        report.anchor(self.bash, "bash is available to run the fenced commands")

    def values(self, role_names):
        env = self.env
        return {"deployed public image": env.images["source_server"], "deployed admin image": env.images["source_admin"],
                "database container": env.name("db"), "user": DB_USER, "database": DB_NAME,
                "source public container": env.name("server"), "source admin container": env.name("admin"),
                "target public container": env.name("server-target"), "target admin container": env.name("admin-target"),
                "public container": env.name("server-target"), "admin container": env.name("admin-target"),
                "tag": TAG, **role_names}

    def installation_flags(self, role):
        """What `docker run` needs to place a container in the lane network with the environment of the installation."""
        env = self.env
        variables = [part for key, value in env.app_env("target", role).items() for part in ("-e", f"{key}={value}")]
        return " ".join(["--network", f"{env.p}-net", "--label", env.label, "-p", "127.0.0.1::8080", *variables])

    def fill(self, line):
        values = self.values({"the environment of the installation": self.installation_flags("admin" if "admin-server" in line else "server")})
        for key, value in values.items():
            line = line.replace(f"<{key}>", value)
        for role, role_name in (("admin", "admin"), ("public", "server")):
            if f"<{role} host>" in line:
                port = self.env.url(role_name, suffix="-target").rsplit(":", 1)[1]
                line = line.replace(f"<{role} host>:8080", f"127.0.0.1:{port}")
        return line

    def run(self, line, expect_success=True):
        command = self.fill(line)
        unresolved = re.findall(r"<[A-Za-z][^<>]*>", command)
        if not self.report.guard(not unresolved, f"the guide command has no placeholder the lane cannot fill: {line[:90]}"):
            return None
        result = subprocess.run([self.bash, "-c", command], cwd=self.workdir, capture_output=True, text=True, encoding="utf-8", timeout=300, env={**os.environ, "MSYS_NO_PATHCONV": "1"})
        if expect_success:
            self.report.check(result.returncode == 0, f"guide: {command[:110]} (exit {result.returncode}{': ' + result.stderr.strip()[:120] if result.returncode else ''})")
        return result.stdout.strip()


def tag_candidates(env):
    docker("tag", env.images["target_server"], f"sphereon/openid-federation-server:{TAG}")
    docker("tag", env.images["target_admin"], f"sphereon/openid-federation-admin-server:{TAG}")


def untag_candidates():
    docker("rmi", f"sphereon/openid-federation-server:{TAG}", f"sphereon/openid-federation-admin-server:{TAG}", check=False)


def wait_ready(replay, comment_line):
    url = re.search(r"curl -fsS (\S+)", replay.fill(comment_line)).group(1)
    return wait_for(lambda: http("GET", url, timeout=5)[0] == 200, 180)


def execute_procedure(replay, report, env, schema_version):
    """Steps 1, 2 and 5 to 7 of the guide. Step 3 pulls or builds the images, the lane tagged its candidates instead."""
    step1 = [replay.run(line) for line in section_lines("### 1. Identify what you run")]
    report.check(step1[0] and step1[0].endswith(env.images["source_server"].split("@")[1]), "guide step 1: the deployed public image digest is the one that runs")
    report.check(step1[2] == "17", f"guide step 1: the highest schema version is 17 (found {step1[2]})")
    report.check(step1[3].startswith(env.images["postgres"].split(":")[1].split("-")[0]), f"guide step 1: the PostgreSQL major version is reported (found {step1[3]})")
    counts_line = next(line for line in section_lines("### 7. Validate") if COUNTS in line)
    before = replay.run(counts_line)
    replay.run(section_lines("### 2. Back up")[0])
    report.check((replay.workdir / "backup-before-0.26.sql").stat().st_size > 1000, "guide step 2: the backup file holds the dump")
    stopped = time.monotonic()
    replay.run(section_lines("### 5. Stop the source")[0])
    for line in section_lines("### 6. Start the target, admin first"):
        if line.startswith("#"):
            report.check(wait_ready(replay, line[1:].split(":", 1)[1]), "guide step 6: the admin image answers before the public image starts")
        else:
            replay.run(line)
    report.check(wait_ready(replay, "curl -fsS http://<public host>:8080/health"), "guide step 6: the public image answers")
    after_lines = [replay.run(line) for line in section_lines("### 7. Validate")]
    print(f"note  observed downtime of the guide, from step 5 until the step 6 images answer: {time.monotonic() - stopped:.0f} s", flush=True)
    validate = after_lines[0:2]
    report.check(validate[0] == f"17|\n{schema_version}|17" or validate[0].replace("\r", "") == f"17|\n{schema_version}|17", f"guide step 7: the version rows are 17 and {schema_version} migrated from 17 (found {validate[0]!r})")
    report.check(validate[1] == str(schema_version - 1), f"guide step 7: the history holds {schema_version - 1} rows (found {validate[1]!r})")
    report.check(replay.run(counts_line) == before, "guide step 7: the record counts equal the counts taken before step 5")
    return before


def execute_restore(replay, report, env, counts_before):
    for line in section_lines("### Restore"):
        replay.run(line)
    for role in ("admin", "server"):
        env.start_app("source", role, "-restored")
        report.check(env.healthy(role, "source", "-restored"), f"guide restore: the source {role} image runs on the restored database")
    report.check(env.psql("SELECT max(version) FROM schema_version").strip() == "17", "guide restore: the restored database is at schema version 17")
    report.check(env.psql(COUNTS).strip() == counts_before, "guide restore: the restored database holds the counts of before the upgrade")


def guide(env, report, options):
    bring_up_source(env, report)
    tag_candidates(env)
    try:
        with tempfile.TemporaryDirectory() as workdir:
            replay = Replay(env, report, pathlib.Path(workdir))
            counts_before = execute_procedure(replay, report, env, options["schema_version"])
            execute_restore(replay, report, env, counts_before)
    finally:
        untag_candidates()
