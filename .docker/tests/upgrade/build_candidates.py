#!/usr/bin/env python3
"""Builds the two diagnostic candidate images from distributions packaged by the build coordinator and prints their identity.

    python build_candidates.py --server-distribution <public ktor .tar> --admin-distribution <admin ktor .tar> [--tag-prefix vdx355-candidate]

Each candidate is the runtime stage of its product Dockerfile (.docker/openid-federation-server and
.docker/openid-federation-admin-server) with the copy from the builder stage replaced by the unpacked distribution.
The base image, health check, user, environment and entry point all come from that Dockerfile, so a candidate cannot
drift from the image it stands in for. Candidates are diagnostic, never release evidence.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

DOCKER_DIR = pathlib.Path(__file__).resolve().parents[2]
PRODUCT_DOCKERFILES = {"server": DOCKER_DIR / "openid-federation-server" / "Dockerfile",
                       "admin": DOCKER_DIR / "openid-federation-admin-server" / "Dockerfile"}


def candidate_dockerfile(product_dockerfile):
    """The final stage of a product Dockerfile, with its copy from the builder stage replaced by the unpacked distribution."""
    lines = product_dockerfile.splitlines()
    runtime = lines[max(index for index, line in enumerate(lines) if line.startswith("FROM ")):]
    copies = [line for line in runtime if line.startswith("COPY --from=")]
    if len(copies) != 1:
        raise ValueError(f"the runtime stage must copy the installed distribution exactly once, found {len(copies)} copies from the builder")
    unpack = ["COPY distribution.tar /tmp/distribution.tar", "RUN tar -xf /tmp/distribution.tar --strip-components=1 -C /app && rm /tmp/distribution.tar"]
    return "\n".join(line2 for line in runtime for line2 in (unpack if line.startswith("COPY --from=") else [line])) + "\n"


def build(role, distribution, prefix):
    dockerfile = candidate_dockerfile(PRODUCT_DOCKERFILES[role].read_text(encoding="utf-8"))
    digest = hashlib.sha256(distribution.read_bytes())
    digest.update(dockerfile.encode("utf-8"))
    tag = f"{prefix}/{role}:{digest.hexdigest()[:12]}"
    with tempfile.TemporaryDirectory() as context:
        context_dir = pathlib.Path(context)
        (context_dir / "distribution.tar").write_bytes(distribution.read_bytes())
        (context_dir / "Dockerfile").write_text(dockerfile, encoding="utf-8")
        result = subprocess.run(["docker", "build", "-t", tag, context], capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        print(f"FAIL  the {role} candidate image did not build: {result.stderr.strip()[-600:]}")
        return None
    image_id = subprocess.run(["docker", "image", "inspect", "-f", "{{.Id}}", tag], capture_output=True, text=True).stdout.strip()
    return {"image": tag, "id": image_id, "distribution": distribution.name, "distribution_sha256": hashlib.sha256(distribution.read_bytes()).hexdigest(),
            "dockerfile_sha256": hashlib.sha256(dockerfile.encode("utf-8")).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-distribution", required=True, type=pathlib.Path)
    parser.add_argument("--admin-distribution", required=True, type=pathlib.Path)
    parser.add_argument("--tag-prefix", default="vdx355-candidate")
    args = parser.parse_args()
    built = {role: build(role, getattr(args, f"{role}_distribution"), args.tag_prefix) for role in PRODUCT_DOCKERFILES}
    print(json.dumps(built, indent=2))
    return 0 if all(built.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
