#!/usr/bin/env python3
"""Run pinned ofcli discovery with a live starting-entity anchor control."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import time
from typing import Any
from urllib.parse import urlsplit

MAX_STDOUT = 4 * 1024 * 1024
MAX_STDERR = 64 * 1024
DEADLINE_SECONDS = 120
NO_CHAIN_MARKER = b"No trust chains found."
SOURCE_COMMIT = "289e97f45d1fd225f16ff77976a7ede1ea6390f0"
SOURCE_VERSION = "0.7.2"


class ProbeError(RuntimeError):
    pass


def https_identifier(value: str, label: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise ProbeError(f"{label} must be an absolute HTTPS identifier without credentials or fragment")
    return value


def read_bounded(proc: subprocess.Popen[bytes]) -> tuple[bytes, bytes, int]:
    assert proc.stdout is not None and proc.stderr is not None
    limits = {proc.stdout: MAX_STDOUT, proc.stderr: MAX_STDERR}
    chunks = {proc.stdout: bytearray(), proc.stderr: bytearray()}
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    selector.register(proc.stderr, selectors.EVENT_READ)
    deadline = time.monotonic() + DEADLINE_SECONDS
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ProbeError("ofcli command exceeded the 120-second deadline")
            for key, _ in selector.select(min(remaining, 1.0)):
                stream = key.fileobj
                block = os.read(stream.fileno(), 65536)
                if not block:
                    selector.unregister(stream)
                    continue
                buffer = chunks[stream]
                buffer.extend(block)
                if len(buffer) > limits[stream]:
                    raise ProbeError("ofcli command exceeded its bounded output limit")
        return bytes(chunks[proc.stdout]), bytes(chunks[proc.stderr]), proc.wait(timeout=1)
    finally:
        selector.close()


def invoke(entity: str, anchor: str, ca_file: Path) -> tuple[dict[str, Any] | None, bool]:
    # Curate the environment: in particular, do not inherit proxy variables or credentials.
    env = {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "PYTHONPATH": "/opt/ofcli-source/src",
        "PYTHONUNBUFFERED": "1",
        "SSL_CERT_FILE": str(ca_file),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
    }
    command = [
        sys.executable,
        "-m",
        "ofcli",
        "--log-level",
        "WARNING",
        "trustchains",
        entity,
        "--trust-anchor",
        anchor,
        "--details",
    ]
    proc = subprocess.Popen(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        close_fds=True,
    )
    try:
        stdout, stderr, exit_code = read_bounded(proc)
    except BaseException:
        proc.kill()
        proc.wait()
        raise
    if exit_code != 0:
        # Do not surface stderr: it may contain fetched statements or URLs with sensitive query data.
        return None, NO_CHAIN_MARKER in stderr
    if not stdout.strip():
        return None, NO_CHAIN_MARKER in stderr
    try:
        value = json.loads(stdout)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProbeError("ofcli returned non-JSON details output") from exc
    if not isinstance(value, dict):
        raise ProbeError("ofcli details output was not a chain object")
    return value, NO_CHAIN_MARKER in stderr


def validate_chain(document: dict[str, Any], expected_path: list[str]) -> dict[str, Any]:
    if len(document) != 1:
        raise ProbeError("expected exactly one trust chain for the selected anchor")
    chain_obj = next(iter(document.values()))
    if not isinstance(chain_obj, dict) or not isinstance(chain_obj.get("chain"), list):
        raise ProbeError("ofcli chain details were malformed")
    links = chain_obj["chain"]
    # A Trust Chain (OpenID Federation 1.1 section 4) is the leaf's Entity Configuration, one
    # Subordinate Statement per authority hop, and the Trust Anchor's Entity Configuration.
    # Intermediates' own Entity Configurations are not part of it.
    expected_links = [(expected_path[0], expected_path[0])]
    expected_links += [(expected_path[k], expected_path[k - 1]) for k in range(1, len(expected_path))]
    expected_links.append((expected_path[-1], expected_path[-1]))
    if len(links) != len(expected_links):
        raise ProbeError("chain statement count did not match the expected authority path")
    observed: list[dict[str, str]] = []
    for item, (expected_issuer, expected_subject) in zip(links, expected_links):
        if not isinstance(item, dict):
            raise ProbeError("chain link was malformed")
        issuer, subject = item.get("iss"), item.get("sub")
        if not isinstance(issuer, str) or not isinstance(subject, str):
            raise ProbeError("chain link omitted issuer or subject")
        if issuer != expected_issuer or subject != expected_subject:
            raise ProbeError("chain issuer/subject links differed from the declared path")
        observed.append({"iss": issuer, "sub": subject})
    if observed[-1] != {"iss": expected_path[-1], "sub": expected_path[-1]}:
        raise ProbeError("terminal self-issued statement did not match selected anchor")
    return {
        "chainLength": len(links),
        "path": expected_path,
        "links": observed,
        "terminalAnchor": expected_path[-1],
    }


def write_result(path: Path, result: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ProbeError("output evidence path must not be a symlink")
    encoded = (json.dumps(result, sort_keys=True, indent=2) + "\n").encode("utf-8")
    with tempfile.NamedTemporaryFile(mode="wb", dir=path.parent, prefix=".ofcli-probe-", delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(encoded)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario-id", default="ofcli-structural-probe", help="Non-secret scenario label")
    parser.add_argument("--entity", required=True, help="Starting entity identifier")
    parser.add_argument("--anchor", required=True, help="Selected trust-anchor identifier")
    parser.add_argument(
        "--expected-chain",
        action="append",
        required=True,
        help="Expected entity path in order from starting entity through trust anchor; repeat for every entity",
    )
    parser.add_argument("--ca-file", default="/run/interop-ca/ca.pem")
    parser.add_argument("--negative-anchor", help="Live starting entity requested as anchor for a structural empty-chain control")
    parser.add_argument("--output", default="/evidence/ofcli-structural.json")
    args = parser.parse_args()

    try:
        entity = https_identifier(args.entity, "entity")
        anchor = https_identifier(args.anchor, "anchor")
        expected = [https_identifier(item, "expected-chain item") for item in args.expected_chain]
        if not expected or expected[0] != entity or expected[-1] != anchor:
            raise ProbeError("expected chain must begin at --entity and end at --anchor")
        ca_file = Path(args.ca_file)
        if ca_file.is_symlink() or not ca_file.is_file():
            raise ProbeError("a regular scoped CA file is required")
        negative_anchor = (
            https_identifier(args.negative_anchor, "starting-entity anchor control")
            if args.negative_anchor
            else None
        )
        if negative_anchor and negative_anchor != entity:
            raise ProbeError("structural control anchor must be the live starting entity")

        document, _ = invoke(entity, anchor, ca_file)
        if document is None:
            raise ProbeError("ofcli emitted no chain for the selected trust anchor")
        summary = validate_chain(document, expected)
        result: dict[str, Any] = {
            "scenarioId": args.scenario_id,
            "status": "structural-chain-present",
            "tool": {"name": "ofcli", "version": SOURCE_VERSION, "commit": SOURCE_COMMIT},
            "inputs": {"entityAndAnchorScheme": "https", "scopedCaConfigured": True},
            "chain": summary,
            "evidenceBoundary": {
                "verified": ["exact structural iss/sub path", "terminal anchor identifier"],
                "configured": ["child process receives SSL_CERT_FILE pointing to the supplied scoped CA"],
                "notVerified": [
                    "entity-statement signatures",
                    "JOSE alg/kid",
                    "metadata policy",
                    "federation trustworthiness",
                    "HTTPS-only scheme for metadata-directed fetch endpoints",
                    "redirect scheme restrictions for native ofcli requests",
                    "TLS validation of every native retrieval",
                ],
                "reason": "Pinned ofcli TrustTree.verify_signatures is a TODO that returns true; its aiohttp fetch path consumes federation_fetch_endpoint values without wrapper-enforced scheme or redirect restrictions.",
            },
        }
        if negative_anchor:
            negative_doc, no_chain_marker = invoke(entity, negative_anchor, ca_file)
            if negative_doc is not None or not no_chain_marker or negative_anchor != entity:
                raise ProbeError("live starting-entity anchor control did not return the pinned empty-chain result")
            result["negativeControl"] = {
                "kind": "starting-entity-as-requested-anchor",
                "status": "no-chain-for-live-starting-entity-anchor",
                "anchor": negative_anchor,
                "entityMatchesStartingEntity": True,
                "rawCliOutputRetained": False,
            }
        write_result(Path(args.output), result)
        return 0
    except ProbeError as exc:
        print(f"ofcli structural probe failed: {exc}", file=sys.stderr)
        return 2
    except Exception:
        # Avoid exception strings because third-party errors can include URLs or response material.
        print("ofcli structural probe failed with an unclassified error", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())





