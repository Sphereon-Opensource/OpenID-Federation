# Research-and-education interoperability profile

## Status

The pinned source for this lane is the GÉANT [`edugain-openidfed` repository at commit `589f2aa18a695d2b9a64e04b9146d7f7f2630c8b`](https://github.com/GEANT/edugain-openidfed/tree/589f2aa18a695d2b9a64e04b9146d7f7f2630c8b), dated 2025-03-03. This is a pilot/research draft, not an adopted normative eduGAIN OpenID Federation profile. The repository’s prototype material labels the profile draft; the published work-plan and pilot context leave production-profile decisions to later work. This source review cannot rule out unpublished decisions. See the pinned-source verification record at `edugain-profile-589f2aa-verified.md` (SHA-256 `C9DCCDA510E257E4D683E472D56940456E02E57B4ACF66BD261343C428B6C38D`) for provenance and scope.

The draft’s common Entity Configuration mapping requires the standard claims `iss`, `sub`, `iat`, `exp`, `jwks`, and an object-valued `metadata`. It describes trust-mark and authority-hint information as optional in the relevant places. This mapping does not assert a mandatory mark type, mandatory R&E policy value, fixed path length, or a universal required-mark rejection rule. The local candidate must keep draft-mapped checks distinct from generic Federation behavior.

The private profile candidate reuses actual signed statements and the three-entity policy scenario. It remains source-only, uncompiled and unexecuted; no profile result is claimed. The latest actual core run remains 2 failures out of 2 scenarios, so the baseline interop gate is not green.

- [GÉANT OIDC Federation Testbed](https://wiki.geant.org/spaces/GWP5/pages/621248732/OIDCfed%2BTestbed)
- [GÉANT eduGAIN OpenID Federation proof of concept](https://wiki.geant.org/spaces/GWP5/pages/755204454/eduGAIN%2BPoC)
- [Pinned draft source](https://github.com/GEANT/edugain-openidfed/tree/589f2aa18a695d2b9a64e04b9146d7f7f2630c8b)
## Candidate topology and checks

This candidate matrix maps behavior against the pinned pilot draft above. It is a research mapping, not an adopted-profile acceptance matrix; only the stated common claims are treated as draft requirements.

| Candidate area | Candidate test shape | Evidence required before calling it profile coverage |
|---|---|---|
| Organization hierarchy | Separate institutional entities below a national or other intermediate authority | Profile clause and revision; two independently running implementations; verified statements and chain endpoints in both supported directions |
| Alternative trust paths | Leaf with two collected paths where one or both anchors are locally trusted | Distinguish candidate paths from signature/constraint-verified paths; assert selected anchor and rejection of unrelated anchors |
| Metadata-policy inheritance | Authority constrains RP metadata and appends institutional contact metadata | Signed source statements, policy version/revision, resulting metadata and rejected conflict cases |
| Trust marks | Profile-required mark on an entity, with authorized issuer and checked status | Profile clause; exact mark type; issuer/subject semantics; valid, missing, expired, revoked and unauthorized outcomes |
| Key rollover and restart | Replace a signing key with an explicit old/new overlap while retaining identity and relationships across restart | Public keys and signed statements before/after restart; expiry boundary; persisted relationship evidence; no reseeding that masks loss |
| OIDC relying-party behavior | Profile-relevant RP metadata followed by a real login against a supported OP | Identified OP; actual registration and browser authorization; callback, code exchange and token validation; signing algorithm recorded per object |

The SUNET `fedservice` tests at commit [`d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f`](https://github.com/SUNET/fedservice/tree/d64d300c68540e0cc3e33aa9eedb9d1e7480bc2f) provide semantic prior art for multi-path collection, policy inheritance and trust-mark endpoints. Their HTTP fetches are mocked and do not establish cross-process profile interop. See [upstream-tests.md](upstream-tests.md) for the exact test files and limits.

## Acceptance record to add after profile selection

The private candidate records the profile name, exact immutable draft revision, status, mapped claim checks, exclusions and a scenario-to-evidence mapping. Before any future activation, each result must also include Sphereon source/artifact identity, peer commit/image digest, direction, entity identifiers, trust anchor, signed object and algorithm, effective metadata, timestamps, and executed outcome. Keep unsupported or unavailable capabilities explicit and keep the Foundation conformance report separate.

A local passing profile scenario demonstrates only the mapped technical behavior for the recorded versions and configuration. It does not prove live SURF/GÉANT participation, operator validation, institutional eligibility, endorsement or certification. Those require separate external coordination and evidence.
