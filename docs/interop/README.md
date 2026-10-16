# OpenID Federation interoperability

This directory describes source and execution evidence for Sphereon’s OpenID Federation interop work, including work relevant to research and education (R&E). Source implementation, peer qualification, mixed-implementation interoperability, Federation for OIDC login, R&E profile evaluation and external acceptance are separate evidence sets.

## Current evidence state

As of 2026-10-16 05:06:46 Europe/Amsterdam, the original two real core directions passed in fresh coordinator run `B7ef59cdec3524eb7`: 45 tests executed, 45 passed, zero failures/errors/skips, with `source_changed=false`. This supersedes the historical 04:19 failure snapshot for current baseline status; its failed-run record remains below in [progress.md](progress.md). The complete planned expansion remains unfinished.

The latest reported unit result is 18 fresh tests passed, zero failures/errors/skips (`B0c2921fbadc649ec`, 2026-10-16 01:10:37 Europe/Amsterdam). Real LightHouse peer qualification has one fresh pass, zero failures/errors/skips (`B126d69d5e1cb4037`). The earlier peer result verified HTTPS, ES512 signatures, stable keys across same-container restart, wrong-CA rejection and trusted-CA success.

The accepted run exercised the actual packaged Sphereon public/admin JVM servers, PostgreSQL-backed signing state, persisted PKCS12 keys, authenticated administration and verified test-network TLS. Its two core evidence files report `outcome=passed`, `resolverVerified=true`, stable public JWKS, and ES256 initial/post-restart Entity Configurations. Both verified Entity Configurations contain one recognized `federation_entity` metadata type and no unknown types; the authority-hint match is true in each direction. These results accept only the original two core scenarios.

Historical failures are retained in [progress.md](progress.md), including the 04:19 run `B95f0b08669324c47` with two failures. The later accepted 05:06 run supersedes that snapshot for current status. The earlier egress-refusal diagnosis was not transferred to that failed run.

Peer qualification is not a mixed-implementation result. Keycloak supplies admin authentication only; it is not an OpenID Provider used for Federation for OIDC login. No complete expanded-suite acceptance, Foundation conformance, R&E profile result, SURF/GÉANT membership, endorsement or certification is claimed. Detailed receipts and failed-run evidence are retained in [progress.md](progress.md).
The complete planned suite remains unfinished. Private source candidates cover additional core topology/policy/trust-mark/rollover cases, an R&E draft-mapped lane, and structural ofcli probing; they are not compiled or executed. The OFFA browser lane and experimental fedservice producer remain unqualified. The latest strict fedservice entity-configuration media-type check failed; the exact response header was not retained.

Upstream counterpart test capabilities and limits are in [upstream-tests.md](upstream-tests.md). The R&E profile status and candidate assertion matrix are in [research-education.md](research-education.md).

## Existing tasks and remaining planned lanes

The JVM module is `modules/openid-federation-interop-tests`:

- `:modules:openid-federation-interop-tests:test` — harness unit tests.
- `:modules:openid-federation-interop-tests:interopPeerQualificationTest` — pinned LightHouse peer qualification. This does not test a Sphereon–LightHouse relationship.
- `:modules:openid-federation-interop-tests:interopCoreTest` — two real trust directions and restart/withdrawal checks. Latest accepted run: both scenarios passed in `B7ef59cdec3524eb7`; the preceding `B95f0b08669324c47` failures remain historical evidence, not the current result.

The active source and workflow have not yet activated the private OIDC, profile, OFCLI, Python or aggregate candidates. Private source and CI overlays exist for these lanes, but remain uncompiled/unexecuted and are not currently runnable entry points. The private CI candidate v8 has passed static review: manifest prefix `dad1f591`, tree prefix `ab1e900`, 19 validator controls, and the OFCLI `compactJwtRecorded=false` field plus five-path-link validation are included. Final OFCLI and composed-source review is pending; Gradle, Docker and Actions have not run. No CI result is claimed.

The core fixture provisions the supported `account` identity mode and `/accounts` routes in source. A legacy-only note in current OpenAPI wording that describes these routes as optional only in legacy mode is stale relative to the implementation; this interop documentation records the source behavior without changing production API documentation.

## Running from an infra-owned workspace

All local Gradle work in an infra-owned workspace goes through the VDX build coordinator. Its supported task is `:modules:openid-federation-interop-tests:test`, with request property `interop.lane` set to `unit` (the default), `peer`, or `core`. Unit and peer requests also set `openidFederation.modules=openid-federation-interop-tests`. The custom standalone task names are not accepted by the coordinator. Submit with `./vdx-build.ps1` (or `sh ./vdx-build` on POSIX) and the session identity, and collect each terminal result before editing shared source.

The core task depends on the public and admin server `installDist` tasks. Run it with default full project inclusion and omit `-PopenidFederation.modules`; a module-only selection excludes the required server projects. The standard source coordinator route does not use a workspace receipt. A receipt is required for the separate owned artifact-mode route through `tooling/workspace-publish/Invoke-WorkspaceDev.ps1`. Read the infra [vdx-build skill](https://github.com/Sphereon-Opensource/VDX-infra/blob/develop/.agents/skills/vdx-build/SKILL.md) before submitting work; do not invoke a local Gradle wrapper around the coordinator guard.

The test classpath selects Docker API 1.44, compatible with the current daemon and docker-java client, without changing host Docker configuration. On a host whose default network address pools are exhausted, optionally set request property `interop.network.subnet` to an unused canonical RFC1918 IPv4 `/24` after checking Docker networks and host routes. This changes only the suite's temporary Testcontainers-owned bridge; Docker rejects overlapping networks and cleanup still removes only owned resources. The successful local peer run used `10.253.140.0/24`. Do not delete other stacks' networks to make the suite run.

For standalone CI, use the repository’s normal wrapper and request the exact task. The interop harness uses public pinned images and locally generated ephemeral credentials; it does not require SURF/GÉANT accounts or credentials. The current workflow does not inject Maven or application secrets. Anonymous resolution of the root’s configured Sphereon snapshot catalogs remains a CI prerequisite to verify. Report source/artifact identity, peer commit and image digest, executed test counts, and skipped or unavailable lanes.

The image lock includes Testcontainers 1.21.3's implicit Ryuk and Alpine startup images. A test-classpath image substitutor pins exactly those tagged references, preserving compatibility metadata and leaving explicit peer/harness images unchanged. Ryuk and startup checks remain enabled. Host image overrides or `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` are applied before this mapping; the unit checks reject an environment that rewrites the audited names. Use an environment without those overrides for this pinned suite.

## CI status

[`.github/workflows/interop.yml`](../../.github/workflows/interop.yml) is manual-only and offers `peer` (the default) or `core`. The peer lane runs only the standalone harness module; the core lane omits module selection so Gradle can include the packaged public/admin servers. Both require Docker and fail for missing JUnit reports, zero tests, failures, errors or skipped tests. The workflow has not run and is not a pull-request gate, scheduled job or required check. A private expanded CI overlay v8 has static review clearance (manifest prefix `dad1f591`, tree prefix `ab1e900`, 19 validator controls), including validation of the emitted `compactJwtRecorded=false` field and five path links. Final OFCLI and composition review remains pending. Gradle, Docker and Actions have not run; the overlay is not activated. Broader CI and release evidence remain open.

## Evidence boundaries

A mixed-implementation pass requires both implementations to run as separate participants over verified HTTP/TLS and exchange protocol objects validated by the receiving implementation. In-process objects, mocked HTTP responses, manually supplied statements, a peer’s own test suite, successful compilation or a successful container start do not meet that bar. Record each tested direction, object type, trust anchor, effective policy and result separately.

OIDC registration and federation are not a complete login. Claim a login only when a supported OAuth2/OIDC host is present and the real authorization, callback, code exchange and token validation have run. The standalone federation server is not by itself a complete OP. Signing algorithms must be associated with the specific signed object (for example, entity configuration, subordinate statement, request object, client assertion or ID token).

Keep OpenID Federation core results, Federation for OIDC results, R&E profile results, Foundation conformance and external operator acceptance as distinct evidence sets. A local container result is not an endorsement or certification.

