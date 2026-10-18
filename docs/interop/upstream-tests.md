# Upstream counterpart tests reviewed

This inventory records source-level test capability at pinned commits. It does not report execution by the Sphereon suite, independent interoperability, release acceptance or certification.

## Pinned sources

| Project | Commit reviewed | Scope in this document |
|---|---|---|
| [SUNET/fedservice](https://github.com/SUNET/fedservice/tree/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f) | `d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f` | Python federation entities, trust-chain collection/verification, metadata policy, trust marks, and manual federation scripts |
| [go-oidfed/lighthouse](https://github.com/go-oidfed/lighthouse/tree/20c4a4e4a9b91b489014a2f1b009fcaaa186401b) | `20c4a4e4a9b91b489014a2f1b009fcaaa186401b` | Go authority/admin, key, constraints, subordinate statement and trust-mark tests |
| [go-oidfed/offa](https://github.com/go-oidfed/offa/tree/52e0743c5b667930c2719f776f7dbdccdb4aa929) | `52e0743c5b667930c2719f776f7dbdccdb4aa929` | Go resolver/configuration and external-login link tests |
| [dianagudu/ofcli](https://github.com/dianagudu/ofcli/tree/289e97f45d1fd225f16ff77976a7ede1ea6390f0) | `289e97f45d1fd225f16ff77976a7ede1ea6390f0` | Python CLI/API surface reviewed for API error behavior and metadata-policy limitations |

Commit pins identify the source reviewed; they do not themselves pin runtime images or all transitive dependencies. SUNET’s [`requirements.txt`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/requirements.txt) includes broad minimum versions and Git dependencies without immutable revisions. A reproducible container recipe still needs a reviewed license, pinned dependency lock, build recipe and image digest. No upstream test fixture is copied into the Sphereon suite by this document.

## SUNET fedservice

fedservice is no longer maintained, and the suite has no fedservice lane. The review below is kept as prior art for test design only.

The most reusable semantic scenarios are in [`test_41_federation_entity.py`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/tests/test_41_federation_entity.py), [`test_43_federationB.py`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/tests/test_43_federationB.py), [`test_55_trust_chain_constraints.py`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/tests/test_55_trust_chain_constraints.py) and [`test_70_trust_marks.py`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/tests/test_70_trust_marks.py).

- `test_41` creates a local multi-anchor topology. Its collector finds two candidate paths; the intermediate that trusts only one anchor verifies one path, while a leaf configured to trust both verifies two. This is a useful assertion pattern: count collected paths separately from verified paths. Fetches are supplied with Python `responses` HTTP fixtures, so this is not a live network exchange.
- `test_43` builds a trust-anchor → intermediate → RP hierarchy, collects and verifies one chain, applies policy, then checks the resulting metadata shape and RP contacts. It uses `responses.RequestsMock` for fetches.
- `test_55` sets authority policy to append trust-anchor contacts and constrain RP grant types, then checks inherited contacts and the resulting `authorization_code` and `refresh_token` grant types. These are policy-engine semantics over built entities and mocked fetch responses, not an independent service test.
- `test_70` exercises trust-mark list, retrieval and status service code using directly constructed federation entities and endpoint calls. The retrieval request explicitly selects ES256. The test does not demonstrate a separately deployed trust-mark issuer exchanging traffic with Sphereon.
- [`metadata-policy-test-vectors-2025-02-13.json`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/policy_test/metadata-policy-test-vectors-2025-02-13.json) contains 2,019 vectors (`n` 1–2019) with combination, TA, intermediate, merged-policy, metadata and resolved-result fields. Its SHA-256 at this pinned source is `93b935ef08487811284d52e48a4db84b1aeb828a46818803360f636fbed0a58a`. This is a valuable expected-result corpus, but it has not been imported or executed by the Sphereon suite. Treat its date and hash as vector provenance, not proof that it is the adopted current specification or a complete R&E profile. Before reuse, audit compatibility with the pinned Federation specification, establish provenance and preserve the applicable Apache-2.0 notice.

The repo also contains a manual local federation setup in [`setup_federation/command.sh`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/setup_federation/command.sh) and a DC4EU check script in [`script/checks_dc4eu_test.sh`](https://github.com/SUNET/fedservice/blob/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f/script/checks_dc4eu_test.sh). The latter invokes clients with `-k` / `--insecure`; its shell workflow is not a TLS-safe template for this suite. Its endpoints and local service assumptions also do not establish a reproducible multi-container test.

## LightHouse

At the pinned commit, tests cover implementation components that are useful for an adapter: admin Basic-auth parsing and transition when users exist; entity configuration validation and persistence; subordinate metadata-policy and constraint CRUD; key enrollment, revocation, expiry and rotation; JWKS publication/expiry filtering; subordinate JWKS refresh/cache invalidation; subordinate statement construction and caching; trust-anchor refresh settings; and trust-mark issuance/storage.

For example, [`keys_test.go`](https://github.com/go-oidfed/lighthouse/blob/20c4a4e4a9b91b489014a2f1b009fcaaa186401b/api/adminapi/keys_test.go) checks that a rotated old key remains with an expiry while a new key is stored, and that expired keys are absent from JWKS. [`subordinates_constraints_test.go`](https://github.com/go-oidfed/lighthouse/blob/20c4a4e4a9b91b489014a2f1b009fcaaa186401b/api/adminapi/subordinates_constraints_test.go) includes a regression that subordinate constraints do not freeze a general metadata policy. [`trustmark_issuance_test.go`](https://github.com/go-oidfed/lighthouse/blob/20c4a4e4a9b91b489014a2f1b009fcaaa186401b/trustmark_issuance_test.go) covers issuance claims and `jti`.

These tests are predominantly in-process API/storage tests. They are not a cross-process Sphereon relationship, nor do they prove a shared PostgreSQL deployment or persistence across a container restart. The Sphereon scenario should reproduce the observable key-overlap behavior by fetching and verifying statements under both old and new public keys across the configured overlap, then confirm rejection after expiry.

## OFFA

At the pinned commit, [`login_test.go`](https://github.com/go-oidfed/offa/blob/52e0743c5b667930c2719f776f7dbdccdb4aa929/internal/server/login_test.go) checks construction of external-login links, query preservation and disabled/blank-entity behavior. It does not perform federation enrollment, OIDC dynamic registration, browser login, callback, authorization-code exchange or token validation. [`resolver_test.go`](https://github.com/go-oidfed/offa/blob/52e0743c5b667930c2719f776f7dbdccdb4aa929/internal/resolver_test.go) exercises configured resolver endpoints, request formatting and error propagation. Authority-hint and resolver configuration tests cover supported forms and invalid configuration.

Therefore OFFA is a candidate counterpart for resolver and login-link scenarios, but it is not evidence of a completed OIDC login at this pin. A browser lane must use a real supported OP, registration, callback and tokens.

## ofcli

At the pinned commit, [`tests/test_api.py`](https://github.com/dianagudu/ofcli/blob/289e97f45d1fd225f16ff77976a7ede1ea6390f0/tests/test_api.py) checks API error responses for an invalid entity URL and missing fetch parameters. The inspected `test_core/trustchains` path has no trust-chain tests at this revision. In [`src/ofcli/fedtree.py`](https://github.com/dianagudu/ofcli/blob/289e97f45d1fd225f16ff77976a7ede1ea6390f0/src/ofcli/fedtree.py), metadata-policy application is marked TODO. Thus ofcli may be useful as an additional chain/API probe, but it cannot be treated as an independent metadata-policy oracle until that implementation and its behavior are separately qualified.

## Candidate scenarios to carry into Sphereon tests

| Scenario | Upstream evidence | Sphereon interop assertion still needed |
|---|---|---|
| Multiple candidate paths | SUNET `test_41` collects two, then verifies one or two according to configured anchors | Assert candidate count, verified count, chosen trust anchor and rejected path independently |
| Policy inheritance | SUNET `test_55` derives contacts and grant-type constraints | Verify the signed fetched statements, inherited contacts and effective grant types across two running implementations |
| Trust marks | SUNET `test_70`; LightHouse issuance tests | Verify issuer, subject (`iss`/`sub`/`id` as applicable), status and mark authorization in the consuming implementation; exercise missing/expired/revoked outcomes |
| Key rollover | LightHouse rotation/JWKS expiry tests | Publish old and new keys during a declared overlap; verify signatures against both as appropriate, preserve key identity over restart, then reject expired keys |
| TLS and real transport | Manual SUNET scripts exist, but the DC4EU check uses `-k` | Use valid TLS verification and the test CA trust needed by the container network; never copy the insecure flag |
| Federation for OIDC | OFFA tests construct links only | Add a real OP and demonstrate registration, browser authorization, callback, code exchange and token validation before claiming login |
| Independent policy decision | ofcli marks metadata-policy application TODO | Do not count ofcli policy output as an independent oracle until its behavior is implemented and qualified |

Python `responses` fixtures are semantic protocol fixtures. They are suitable for understanding expected request and policy behavior, but they are not network evidence and must not be counted as a real transport pass.


## Current candidate status and additional pins (2026-10-16)

### GÉANT eduGAIN R&E pilot draft

The selected public source is the GÉANT [`edugain-openidfed` repository](https://github.com/GEANT/edugain-openidfed/tree/589f2aa18a695d2b9a64e04b9146d7f7f2630c8b), commit `589f2aa18a695d2b9a64e04b9146d7f7f2630c8b` (2025-03-03). It is a pilot/research draft, not an adopted normative profile. The source describes a prototype and marks the profile as draft; the linked work-plan and pilot materials leave production-profile decisions for subsequent work. The pinned mapping and scope notes are recorded in the external research artifact `edugain-profile-589f2aa-verified.md` (SHA-256 `C9DCCDA510E257E4D683E472D56940456E02E57B4ACF66BD261343C428B6C38D`). This bounded public-source review does not rule out unpublished decisions.

The draft text maps common Entity Configuration claims (`iss`, `sub`, `iat`, `exp`, `jwks`, and an object-valued `metadata`) and describes optional authority hints, trust marks, constraints and trust-mark issuers. Do not turn optional fields or exploratory mappings into mandatory assertions. The private profile candidate reuses real signed objects and the three-entity policy path, but is source-only and has not been compiled or run. See [research-education.md](research-education.md) for the mapped scope and exclusions.

### ofcli structural probe candidate

The private `ofcli` lane pins `dianagudu/ofcli` commit `289e97f45d1fd225f16ff77976a7ede1ea6390f0` (v0.7.2) and invokes its real CLI against a served fixture entity after the independent Nimbus resolver has verified the positive chain. Its intended comparison is structural: exact expected issuer/subject links and anchor selection, plus a served starting entity requested as its own anchor as a negative control. It is not a cryptographic or metadata-policy oracle: the pinned `TrustTree.verify_signatures()` implementation contains a TODO that returns true, and the unrelated-host control was removed to avoid a DNS-only negative. The candidate is uncompiled/unexecuted. CI candidate v8 has static review clearance for the `compactJwtRecorded=false` field and five path links (19 validator controls); final OFCLI and composed-source review is pending. Gradle, Docker and Actions remain unrun.