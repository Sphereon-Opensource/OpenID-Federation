# OpenID Federation interoperability

This directory describes source and execution evidence for Sphereon’s OpenID Federation interop work, including work relevant to research and education (R&E). Source implementation, peer qualification, mixed-implementation interoperability, Federation for OIDC login, R&E profile evaluation and external acceptance are separate evidence sets.

## Current evidence state

As of 2026-10-16 06:17:01 Europe/Amsterdam, the activated 45-file module source slice has a fresh accepted unit result: coordinator request `Rb828e94321e94371` / job `B9c81d958b63e4594` executed `compileTestKotlin` and all 21 selected tests, with 21 passed and zero failures/errors/skips; `source_changed=false`. This run used the genuine catalog-only bootstrap. The module inventory contained 60 files; `compileTestKotlin` completed successfully. It establishes the unit lane only, not any container or mixed-peer result.

The original two real core directions remain a separate earlier accepted baseline: `B7ef59cdec3524eb7` executed 45 tests, including 43 full-client tests and two core scenarios, with 45 passed. It is not combined with the newer 21-test unit result. The earlier standalone LightHouse peer qualification `B126d69d5e1cb4037` also remains a separate one-test pass; it verified HTTPS, ES512 signatures, stable keys across same-container restart, wrong-CA rejection and trusted-CA success.

The separate `B7ef59cdec3524eb7` core run exercised the actual packaged Sphereon public/admin JVM servers, PostgreSQL-backed signing state, persisted PKCS12 keys, authenticated administration and verified test-network TLS. Its two core evidence files report `outcome=passed`, `resolverVerified=true`, stable public JWKS, and ES256 initial/post-restart Entity Configurations. Both verified Entity Configurations contain one recognized `federation_entity` metadata type and no unknown types; the authority-hint match is true in each direction. These results accept only the original two core scenarios.

Historical failures are retained in [progress.md](progress.md), including the 04:19 run `B95f0b08669324c47` with two failures. The later accepted 05:06 run supersedes that snapshot for current status. The earlier egress-refusal diagnosis was not transferred to that failed run.

Peer qualification is not a mixed-implementation result. Keycloak supplies admin authentication only; it is not an OpenID Provider used for Federation for OIDC login. No complete expanded-suite acceptance, Foundation conformance, R&E profile result, SURF/GÉANT membership, endorsement or certification is claimed. Detailed receipts and failed-run evidence are retained in [progress.md](progress.md).
The expanded lanes have run against SDK generation a233cb41 and the develop Library source. Core: 14 of 15 scenarios pass (both trust directions; policy, path-length, naming and multi-anchor topologies; trust-mark inclusion and filtering; Sphereon ES256, ES384, ES512 and RS256 keys accepted by LightHouse; Sphereon signer rollover). The one failure is the LightHouse overlap rollover: the pinned LightHouse announces a rotated key but keeps signing with the old one. The R&E profile lane passes. The Python lane fails because the pinned fedservice serves its Entity Configuration without the `application/entity-statement+jwt` media type. The OFCLI lane runs but its structural probe reports a chain length that differs from the expected authority path; this is not yet explained. The OFFA browser lane still needs the private OP host assembly.

Upstream counterpart test capabilities and limits are in [upstream-tests.md](upstream-tests.md). The R&E profile status and candidate assertion matrix are in [research-education.md](research-education.md).

## Existing tasks and remaining planned lanes

The JVM module is `modules/openid-federation-interop-tests`:

- `:modules:openid-federation-interop-tests:test -Pinterop.lane=unit` — harness unit tests; this is the only lane in the latest run.
- `:modules:openid-federation-interop-tests:interopPeerQualificationTest` — pinned LightHouse peer qualification. This does not test a Sphereon–LightHouse relationship.
- `:modules:openid-federation-interop-tests:interopCoreTest` — expanded 15-scenario core lane, including the original two trust directions and restart/withdrawal checks. The original two passed on the earlier source in `B7ef59cdec3524eb7`; the expanded lane has not executed from this checkpoint.

The optional AS-host and browser projects remain private and absent. The expanded 15-scenario core lane includes the two scenarios with earlier B7 baseline evidence, but has not executed from this checkpoint. Profile, OFCLI, Python and OIDC lanes remain unrun and unqualified. The OIDC test source compiled; actual OIDC execution still requires the genuine host assembly. No expanded-lane result is combined with the 21-test unit pass or the earlier core baseline.

The core fixture provisions the supported `account` identity mode and `/accounts` routes in source. A legacy-only note in current OpenAPI wording that describes these routes as optional only in legacy mode is stale relative to the implementation; this interop documentation records the source behavior without changing production API documentation.

## Running from an infra-owned workspace

All local Gradle work in an infra-owned workspace goes through the VDX build coordinator. Its supported task is `:modules:openid-federation-interop-tests:test`, with request property `interop.lane` set to `unit` (the default), `peer`, or `core`. Unit and peer requests also set `openidFederation.modules=openid-federation-interop-tests`. The custom standalone task names are not accepted by the coordinator. Submit with `./vdx-build.ps1` (or `sh ./vdx-build` on POSIX) and the session identity, and collect each terminal result before editing shared source.

The core task depends on the public and admin server `installDist` tasks. Run it with default full project inclusion and omit `-PopenidFederation.modules`; a module-only selection excludes the required server projects. The standard source coordinator route does not use a workspace receipt. A receipt is required for the separate owned artifact-mode route through `tooling/workspace-publish/Invoke-WorkspaceDev.ps1`. Read the infra [vdx-build skill](https://github.com/Sphereon-Opensource/VDX-infra/blob/develop/.agents/skills/vdx-build/SKILL.md) before submitting work; do not invoke a local Gradle wrapper around the coordinator guard.

The test classpath selects Docker API 1.44, compatible with the current daemon and docker-java client, without changing host Docker configuration. On a host whose default network address pools are exhausted, optionally set request property `interop.network.subnet` to an unused canonical RFC1918 IPv4 `/24` after checking Docker networks and host routes. This changes only the suite's temporary Testcontainers-owned bridge; Docker rejects overlapping networks and cleanup still removes only owned resources. The successful local peer run used `10.253.140.0/24`. Do not delete other stacks' networks to make the suite run.

For standalone CI, use the repository’s normal wrapper and request the exact task. The interop harness uses public pinned images and locally generated ephemeral credentials; it does not require SURF/GÉANT accounts or credentials. The current workflow does not inject Maven or application secrets. Anonymous resolution of the root’s configured Sphereon snapshot catalogs remains a CI prerequisite to verify. Report source/artifact identity, peer commit and image digest, executed test counts, and skipped or unavailable lanes.

The image lock includes Testcontainers 1.21.3's implicit Ryuk and Alpine startup images. A test-classpath image substitutor pins exactly those tagged references, preserving compatibility metadata and leaving explicit peer/harness images unchanged. Ryuk and startup checks remain enabled. Host image overrides or `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` are applied before this mapping; the unit checks reject an environment that rewrites the audited names. Use an environment without those overrides for this pinned suite.

## CI status

[`.github/workflows/interop.yml`](../../.github/workflows/interop.yml) is manual-only and offers `peer` (the default) or `core`. The peer lane runs only the standalone harness module; the core lane omits module selection so Gradle can include the packaged public/admin servers. Both require Docker and fail for missing JUnit reports, zero tests, failures, errors or skipped tests. The workflow has not run and is not a pull-request gate, scheduled job or required check. Broader private CI/source compositions remain unactivated; no CI, scheduled or release result is claimed.

## Evidence boundaries

A mixed-implementation pass requires both implementations to run as separate participants over verified HTTP/TLS and exchange protocol objects validated by the receiving implementation. In-process objects, mocked HTTP responses, manually supplied statements, a peer’s own test suite, successful compilation or a successful container start do not meet that bar. Record each tested direction, object type, trust anchor, effective policy and result separately.

OIDC registration and federation are not a complete login. Claim a login only when a supported OAuth2/OIDC host is present and the real authorization, callback, code exchange and token validation have run. The standalone federation server is not by itself a complete OP. Signing algorithms must be associated with the specific signed object (for example, entity configuration, subordinate statement, request object, client assertion or ID token).

Keep OpenID Federation core results, Federation for OIDC results, R&E profile results, Foundation conformance and external operator acceptance as distinct evidence sets. A local container result is not an endorsement or certification.

