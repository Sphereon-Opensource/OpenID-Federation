#!/usr/bin/env python3
"""Image level upgrade lane: real source images create the data, real target images take it over.

Runs the pinned source public and admin images against a disposable PostgreSQL volume with a stand-in token issuer,
provisions accounts, keys, a subordinate and published statements through the source admin API, then starts the target
images on the same database and compares independent before and after manifests (the SQL manifests of the persistence
module). Each scenario owns a labelled environment that is removed afterwards, nothing else on the Docker host is touched.

    python upgrade_lane.py --source-server <image> --source-admin <image> --target-server <image> --target-admin <image> [scenario ...]

Scenarios: upgrade, restart, blocked, simultaneous, restore, old-on-upgraded, signing, fresh, external, keycloak, guide. `selftest` proves the
reporting and needs no images.
Exit status 0 only when every assertion held.
"""
import argparse
import json
import sys
import time
import uuid

from scenarios import SCENARIOS, run_scenario
from guide_replay import guide
from scenarios_modes import MODE_SCENARIOS

SCENARIOS.update(MODE_SCENARIOS, guide=guide)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenarios", nargs="*", choices=sorted(SCENARIOS), help="default: every scenario except selftest")
    for side in ("source", "target"):
        for role in ("server", "admin"):
            parser.add_argument(f"--{side}-{role}", required=False, help=f"{side} {role} image, pinned by digest for the source")
    parser.add_argument("--postgres-image", default="postgres:16")
    parser.add_argument("--node-image", default="node:20-alpine")
    parser.add_argument("--schema-version", type=int, default=30, help="schema version the target images migrate to")
    parser.add_argument("--source-class", choices=["a", "b"], default="a", help="schema class of the source images, b has no Jwk.alg column")
    parser.add_argument("--admin-port-env", action="store_true", help="diagnostic: set OIDF_SERVER_ADMIN_PORT=8080 on the target admin image instead of relying on its own default")
    parser.add_argument("--keep", action="store_true", help="leave the environments running for inspection")
    parser.add_argument("--report", help="write a JSON summary to this path")
    args = parser.parse_args()

    names = args.scenarios or [name for name in SCENARIOS if name != "selftest"]
    missing = [] if names == ["selftest"] else [f"--{side}-{role}" for side in ("source", "target") for role in ("server", "admin") if not getattr(args, f"{side}_{role}")]
    if missing:
        parser.error("missing " + ", ".join(missing))
    images = {f"{side}_{role}": getattr(args, f"{side}_{role}") for side in ("source", "target") for role in ("server", "admin")}
    images.update(postgres=args.postgres_image, node=args.node_image)
    options = {"schema_version": args.schema_version, "source_class": args.source_class, "admin_port_env": args.admin_port_env, "keep": args.keep,
               "run_id": uuid.uuid4().hex[:8]}

    summary, started = {}, time.time()
    for name in names:
        report = run_scenario(name, images, options)
        summary[name] = {"assertions": report.assertions, "failures": report.failures}
    failed = {name: result for name, result in summary.items() if result["failures"]}
    print(f"\n{sum(r['assertions'] for r in summary.values())} assertions in {len(summary)} scenarios, {sum(len(r['failures']) for r in summary.values())} failures, {time.time() - started:.0f}s")
    for name, result in failed.items():
        print(f"FAILED {name}: " + "; ".join(result["failures"]))
    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump({"images": images, "scenarios": summary}, handle, indent=2)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
