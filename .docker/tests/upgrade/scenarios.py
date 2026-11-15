"""The upgrade lane scenarios. Each one starts from its own disposable environment and reports FAIL lines, never a stack trace."""
import base64
import json
import time

from environment import Environment
from lane_support import AT_JWT, DB_NAME, DB_USER, Report, Stop, http
from provisioning import ACCOUNTS, identifier, provision_source

PRESERVED_VALUES, DERIVED_VALUES = 37, 7


def public_snapshot(env):
    """The public answers that must survive the upgrade: the stored root and account entity configurations and one subordinate statement."""
    server = env.url("server")
    paths = ["/.well-known/openid-federation", *(f"/{name}/.well-known/openid-federation" for name in ACCOUNTS),
             f"/{ACCOUNTS[0]}/fetch?sub={identifier('subordinate-1')}"]
    return {path: http("GET", server + path) for path in paths}


def claims_without_time(statement):
    """The payload of a compact JWT without its issue, expiry and id claims, or None when the text is not a JWT."""
    parts = statement.strip().split(".")
    if len(parts) != 3:
        return None
    try:
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    except ValueError:
        return None
    return {name: value for name, value in payload.items() if name not in ("iat", "exp", "jti")}


def bring_up_source(env, report):
    env.start_infrastructure()
    env.start_app("source", "admin")
    report.anchor(env.healthy("admin", "source"), "the source admin image reaches readiness and migrates the empty database")
    env.start_app("source", "server")
    report.anchor(env.healthy("server", "source"), "the source public image reaches readiness")
    provision_source(env, report)
    env.public_before = public_snapshot(env)
    for path, (status, body) in env.public_before.items():
        print(f"note  source GET {path}: HTTP {status}, {len(body)} bytes", flush=True)
    for path in env.public_before:
        status, body = env.public_before[path]
        report.check(status == 200 and body.strip().count(".") == 2, f"source: the public image serves the stored statement at {path} (HTTP {status})")
    if env.options.get("idp") != "keycloak":
        answer = http("GET", f"{env.url('admin')}/accounts", env.token(roles=""))
        report.check(answer[0] == 403, f"source: the earlier admin image refuses a token without the admin role ({answer.describe()})")


def retire_source(env):
    env.stop("server")
    env.stop("admin")


def source_manifest(env, report, options):
    before = env.manifest("preserved.sql", "expected-common.sql", f"expected-alg-row-{options['source_class']}.sql")
    report.anchor(len(before) == PRESERVED_VALUES + DERIVED_VALUES, f"the source manifest holds {PRESERVED_VALUES + DERIVED_VALUES} values (found {len(before)})")
    return before


def start_target(env, report, suffix=""):
    env.start_app("target", "admin", suffix)
    admin_ready = env.healthy("admin", "target", suffix)
    if not report.guard(admin_ready, f"the target admin image{suffix} reaches readiness"):
        env.explain_not_ready("admin", suffix)
    env.start_app("target", "server", suffix)
    server_ready = env.healthy("server", "target", suffix)
    if not report.guard(server_ready, f"the target public image{suffix} reaches readiness"):
        env.explain_not_ready("server", suffix)
    return admin_ready and server_ready


def compare(env, report, before, schema_version, label="upgrade"):
    after = env.manifest("preserved.sql", "actual.sql")
    preserved = {name: value for name, value in before.items() if not name.startswith("expected.")}
    changed = sorted(name for name, value in preserved.items() if after.get(name) != value)
    report.check(len(preserved) == PRESERVED_VALUES and not changed, f"{label}: all {len(preserved)} preserved values are equal" + (f", changed: {', '.join(changed)}" if changed else ""))
    derived = {name: value for name, value in before.items() if name.startswith("expected.")}
    wrong = sorted(name for name, value in derived.items() if after.get("actual." + name[len("expected."):]) != value)
    report.check(len(derived) == DERIVED_VALUES and not wrong, f"{label}: all {len(derived)} derived values are what the source rows require" + (f", wrong: {', '.join(wrong)}" if wrong else ""))
    versions = env.psql("SELECT version FROM schema_version ORDER BY version").split()
    report.check(versions == ["17", str(schema_version)], f"{label}: schema_version holds the source row and the target row (found {versions})")
    history = env.psql("SELECT version FROM schema_migration_history ORDER BY version").split()
    report.check(history == [str(n) for n in range(1, schema_version)], f"{label}: every migration file is recorded exactly once ({len(history)} rows)")


def check_contract(env, report):
    check_public_unchanged(env, report, "right after the start")
    admin, server, token = env.url("admin"), env.url("server"), env.token(typ=AT_JWT)
    answer = http("GET", f"{admin}/accounts", env.token())
    report.check(answer[0] == 401, f"the target admin image refuses an access token whose typ is JWT, the issuer must issue at+jwt ({answer.describe()})")
    answer = http("GET", f"{admin}/accounts", token)
    report.check(answer[0] == 200 and all(name in answer[1] for name in ("root", *ACCOUNTS)), f"the legacy account API lists the migrated accounts ({answer.describe()})")
    kid = env.psql("SELECT kid FROM Jwk WHERE account_id = (SELECT id FROM Account WHERE username = 'alpha') LIMIT 1").strip()
    answer = http("GET", f"{admin}/admin/v1/keys", token, {"X-Account-Username": "alpha"})
    report.check(answer[0] == 200 and bool(kid) and kid in answer[1], f"the admin API lists the migrated key {kid} of alpha ({answer.describe()})")
    report.check(http("GET", f"{admin}/admin/v1/keys", None, {"X-Account-Username": "alpha"})[0] == 401, "the admin API refuses a request without a token")
    answer = http("GET", f"{admin}/admin/v1/keys", env.token("someone-else", typ=AT_JWT), {"X-Account-Username": "alpha"})
    report.check(answer[0] == 403, f"a token subject that is not on the allow-list cannot act on another account ({answer.describe()})")
    answer = http("GET", f"{admin}/admin/v1/keys", token, {"X-Account-Username": "nobody"})
    report.check(answer[0] == 404, f"an account that does not exist answers not found ({answer.describe()})")
    answer = http("GET", f"{admin}/accounts", env.token(roles="", typ=AT_JWT))
    report.check(answer[0] == 200, f"the target admin image does not read the roles claim, a token without the admin role is accepted ({answer.describe()})")
    answer = http("GET", f"{server}/.well-known/openid-federation")
    report.check(answer[0] == 200 and answer[1].strip().count(".") == 2, f"the public image serves the stored root entity configuration ({answer.describe()})")
    # The first admin call of an account builds its managed configuration from the stored statement. Class B statements have a JOSE
    # header without alg, so this also pins that they keep being served afterwards.
    check_public_unchanged(env, report, "after the admin calls")


def check_public_unchanged(env, report, when):
    for path, (answer, text) in public_snapshot(env).items():
        before_answer, before_text = env.public_before[path]
        claims = claims_without_time(before_text)
        if path.endswith("openid-federation") or claims is None:
            same = (answer, text) == (before_answer, before_text)
            report.check(same, f"the public image answers {path} exactly as before the upgrade, {when} (HTTP {before_answer}, then {answer})")
        else:
            same = answer == before_answer and claims_without_time(text) == claims
            report.check(same, f"the public image serves the same statement claims at {path}, {when} (HTTP {before_answer}, then {answer})")


def upgrade(env, report, options):
    bring_up_source(env, report)
    retire_source(env)
    before = source_manifest(env, report, options)
    stopped = time.monotonic()
    report.anchor(start_target(env, report), "the target images start on the source database")
    print(f"note  observed downtime, from stopping the source images until both target images answer their health check: {time.monotonic() - stopped:.0f} s", flush=True)
    migration = env.psql("SELECT round(extract(epoch FROM (max(applied_at) - min(applied_at)))::numeric, 3) FROM schema_migration_history WHERE version >= 17").strip()
    print(f"note  the target recorded the migration files 17 to {options['schema_version'] - 1} within {migration} s", flush=True)
    compare(env, report, before, options["schema_version"])
    check_contract(env, report)
    return before


def restart(env, report, options):
    before = upgrade(env, report, options)
    snapshot = env.psql("SELECT id, version, applied_at FROM schema_migration_history ORDER BY id")
    for round_number in (1, 2):
        for role in ("admin", "server"):
            env.docker_restart(role)
            report.check(env.healthy(role, "target"), f"restart {round_number}: the target {role} image is ready again")
    report.check(env.psql("SELECT id, version, applied_at FROM schema_migration_history ORDER BY id") == snapshot, "restarts replay no migration and rewrite no history row")
    compare(env, report, before, options["schema_version"], "restart")


def blocked(env, report, options):
    bring_up_source(env, report)
    retire_source(env)
    before = source_manifest(env, report, options)
    env.psql("CREATE TABLE TrustAnchorHint (not_the_migrated_shape integer)")
    env.start_app("target", "admin")
    report.check(not env.healthy("admin", "target", seconds=60), "the target admin image refuses to start while a migration fails")
    # The message is followed by the stack trace of the failed initialization, so the default tail of 40 lines can start after it.
    logs = env.logs("admin", tail=500)
    report.check("18.sqm" in logs.stdout + logs.stderr, "the failure names the migration file that failed")
    report.check(env.psql("SELECT version FROM schema_version ORDER BY version").split() == ["17"], "the failed start left the source schema version in place")
    report.check(env.psql("SELECT count(*) FROM information_schema.columns WHERE lower(table_name) = 'account' AND column_name = 'tenant_source'").strip() == "0", "the failed start rolled back the migrations it had already applied")
    env.stop("admin")
    env.psql("DROP TABLE TrustAnchorHint")
    report.anchor(start_target(env, report), "after the cause is removed the target images start and finish the upgrade")
    compare(env, report, before, options["schema_version"], "retry")


def simultaneous(env, report, options):
    bring_up_source(env, report)
    retire_source(env)
    before = source_manifest(env, report, options)
    for suffix in ("-1", "-2"):
        env.start_app("target", "admin", suffix)
    env.start_app("target", "server")
    for role, suffix in (("admin", "-1"), ("admin", "-2"), ("server", "")):
        report.check(env.healthy(role, "target", suffix), f"simultaneous start: the target {role}{suffix} image is ready")
    compare(env, report, before, options["schema_version"], "simultaneous")


def restore(env, report, options):
    bring_up_source(env, report)
    retire_source(env)
    before = source_manifest(env, report, options)
    dump = env.docker_exec(["pg_dump", "-U", DB_USER, "-d", DB_NAME, "--no-owner"])
    report.anchor("CREATE TABLE" in dump, "a plain SQL backup of the source database was taken")
    report.anchor(start_target(env, report), "the target images upgrade the source database")
    compare(env, report, before, options["schema_version"], "upgrade before the restore")
    for name in ("restored_for_source", "restored_for_target"):
        env.psql(f'CREATE DATABASE "{name}"')
        env.psql(dump, database=name)
    for name in ("restored_for_source", "restored_for_target"):
        restored = env.psql("SELECT (SELECT count(*) FROM Account) || '|' || (SELECT count(*) FROM Jwk) || '|' || (SELECT count(*) FROM Subordinate)", database=name).strip()
        report.check(restored == "3|3|1", f"restore: {name} holds the 3 accounts, 3 keys and 1 subordinate of the backup (found {restored})")
    env.stop("admin", "")
    env.start_app("source", "admin", "-rollback", database="restored_for_source")
    report.check(env.healthy("admin", "source", "-rollback"), "rollback: the source image runs on the restored backup")
    env.start_app("target", "admin", "-again", database="restored_for_target")
    report.check(env.healthy("admin", "target", "-again"), "restore: the target image upgrades the restored backup again")


def old_on_upgraded(env, report, options):
    before = upgrade(env, report, options)
    env.stop("admin")
    env.start_app("source", "admin", "-old")
    started = env.healthy("admin", "source", "-old", seconds=90)
    print(f"note  the source admin image on the upgraded database {'started' if started else 'did not start'}; that use is unsupported either way")
    report.check(env.psql("SELECT version FROM schema_version ORDER BY version").split() == ["17", str(options["schema_version"])], "an old image on the upgraded database does not rewrite the version rows")
    env.stop("admin", "-old")
    env.psql("INSERT INTO schema_version (version) VALUES (9999)")
    env.start_app("target", "admin", "-newer")
    report.check(not env.healthy("admin", "target", "-newer", seconds=60), "the target image refuses a database whose schema is newer than it")
    logs = env.logs("admin", "-newer")
    report.check("schema version 9999" in logs.stdout + logs.stderr, "the refusal says which schema version it found")


def selftest(env, report, options):
    """Proves the reporting itself: every damage class ends in FAIL lines and a non-zero exit, never a stack trace."""
    report.check(False, "a failing check is recorded and the scenario continues")
    report.check(True, "a passing check after it still runs")
    for member in ("present", None):
        if not report.guard(member is not None, "a failing guard skips the rest of its own iteration"):
            continue
        report.check(member == "present", f"a dependent check runs for {member}")
    report.anchor(None, "a failing anchor ends the scenario with a report")
    report.check(True, "never reached")


SCENARIOS = {"upgrade": upgrade, "restart": restart, "blocked": blocked, "simultaneous": simultaneous, "restore": restore,
             "old-on-upgraded": old_on_upgraded, "selftest": selftest}


def run_scenario(name, images, options):
    report = Report()
    env = Environment(f"vdx355-{name}-{options['run_id']}", images, report, options)
    print(f"== scenario {name}", flush=True)
    try:
        SCENARIOS[name](env, report, options)
    except Stop:
        pass
    except Exception as error:  # a tooling fault is reported as one, never as a defect of the images
        report.failures.append(f"lane fault in {name}: {error}")
        print(f"FAIL  lane fault in {name}: {error}", flush=True)
    finally:
        if not options["keep"]:
            env.cleanup()
    return report
