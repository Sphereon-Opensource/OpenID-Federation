# Client onboarding: Automatic and Explicit Registration

This page explains how a client (a Relying Party, RP) and a provider (an OpenID Provider or OAuth 2.0 authorization
server, OP) establish a client registration through OpenID Federation, using the commands in
`com.sphereon.openid.fed.services.command.registration`. They implement OpenID Federation for OpenID Connect 1.1 Final,
Section 12: Automatic Registration (§12.1), which some deployments call federation dynamic client registration, and
Explicit Registration (§12.2). Section numbers on this page refer to that specification unless they say OpenID
Federation 1.1.

The commands own the protocol: Trust Chain resolution and verification, metadata policy, signatures, audiences, media
types and lifetimes. The host owns the rest: the HTTP endpoints, which Trust Anchors it accepts, how client
identifiers and credentials are assigned, and where registrations are stored. For how a host invokes commands, see the
[library guide](OPENID-FEDERATION-1.1.md#using-the-library-in-a-host-application).

## Registration profiles

Every command takes a `RegistrationProfile` that fixes the pair of Entity Types the registration is made for.
`OPENID_CONNECT` registers an `openid_relying_party` with an `openid_provider`, and `OAUTH2` registers an
`oauth_client` with an `oauth_authorization_server`. Below, "client Entity Type" and "provider Entity Type" mean the
two types of the chosen profile.

## Trust Anchors

Every command takes the Trust Anchors the caller accepts as a list of `RegistrationTrustAnchor` values. A Trust
Anchor is referenced by its https Entity Identifier. Its keys come from the Entity Configuration it publishes at its
own `/.well-known/openid-federation`, which must be issued by the anchor about itself, unexpired and contain keys.
Keys from statements a counterparty supplies are never used as anchor keys. A Trust Chain is accepted only when it
ends at one of the listed anchors and verifies against that anchor's keys. An empty list is refused.

`pinnedKeys` replaces the lookup with keys distributed another way. It is optional and not normally set.

The same `RegistrationTrustAnchor` value is used by `fed.trust.verify-entity`, which verifies an entity's Trust Chain
and required Trust Marks outside a registration
([library guide](OPENID-FEDERATION-1.1.md#verifying-an-entity-under-a-trust-anchor)).

## Before onboarding a client

Both parties are federation entities. Each needs an Entity Identifier (an https URL), a published Entity
Configuration and a Subordinate Statement from an Immediate Superior that leads to a Trust Anchor the other party
accepts. [Entity onboarding](OPENID-FEDERATION-1.1.md#entity-onboarding) in the library guide describes how an entity
gets admitted.

The RP publishes metadata for the client Entity Type in its Entity Configuration, including its client signing keys
through `jwks`, `signed_jwks_uri` or `jwks_uri`. These are the keys the OP checks request proofs against; they are
separate from the Federation Entity Keys.

The OP publishes metadata for the provider Entity Type that lists the registration types it accepts in
`client_registration_types_supported` (`automatic`, `explicit` or both) and, for Explicit Registration, its
`federation_registration_endpoint` as an https URL. The host adds these parameters to the provider's metadata; the
library does not add them by itself. Explicit Registration also signs with the account's selected Federation Entity
Key, so both accounts need one ([library guide](OPENID-FEDERATION-1.1.md#federation-entity-keys-and-the-signing-key)).

## Automatic Registration (§12.1)

With Automatic Registration the RP does not register in advance. It uses its Entity Identifier as `client_id` and
proves control of one of its client signing keys in its first request. The OP resolves the RP through the federation
and registers it for as long as the Trust Chain is valid.

### RP side

The library has no RP-side command for Automatic Registration, because the RP only sends an ordinary authorization
request with one of these proofs (§12.1.1.1, §12.1.1.2):

- a Request Object at the authorization endpoint or as a Pushed Authorization Request parameter,
- `private_key_jwt` client authentication at the Pushed Authorization Request endpoint, or
- `self_signed_tls_client_auth` at the Pushed Authorization Request endpoint.

A Request Object carries `iss` and `client_id` equal to the RP's Entity Identifier, `aud` equal to the OP's Entity
Identifier and nothing else, no `sub`, a `jti`, an `exp` and a `kid` header naming one of the RP's published client
signing keys. It may carry a `trust_chain` header with the RP's Trust Chain and, together with it, a
`peer_trust_chain` header with the OP's Trust Chain. A client assertion carries `iss` and `sub` equal to the RP, the
same `aud` rule, a `jti`, an `exp` and a `kid` header.

### OP side

1. **Recognize the client.** When a request arrives with a `client_id` the OP does not know and that is an https URL,
   the host treats it as a candidate for Automatic Registration.
2. **Verify the request with `fed.registration.verify-automatic`.** The host passes its own Entity Identifier
   (`providerEntityIdentifier`), the `clientId`, the profile, the proof (`RequestObject`, `PrivateKeyJwt` or
   `SelfSignedTlsClientCertificate` with the certificate its TLS layer already verified), its accepted Trust Anchors
   and the JWS algorithms it accepts (`allowedSigningAlgs`). The command checks, in this order:
   - the proof's structure: a compact JWS with an `alg` from the accepted list that is not `none` or an HMAC
     algorithm, the claim rules listed under the RP side, a `kid`, a `jti`, an `exp` in the future and, when present,
     an `iat` no more than 300 seconds in the future;
   - the RP's Trust Chain: when the Request Object has a `trust_chain` header that chain is verified as given,
     otherwise the chain is resolved from the RP's `authority_hints`; either way it must end at an accepted Trust
     Anchor and verify against that anchor's keys;
   - the RP's Resolved Metadata: the metadata policies of the chain are applied, and the result must contain the
     client Entity Type;
   - the proof key: the client signing keys are collected from the resolved client metadata (`jwks`,
     `signed_jwks_uri` verified with one of the RP's Federation Entity Keys, `jwks_uri`, all https), a `kid` that
     names different keys in two sources is refused, and the proof must verify with the key named by its `kid`; for a
     TLS certificate, exactly one published key must carry that certificate as the first entry of its `x5c`;
   - the Peer Trust Chain, when sent: it must verify as the OP's own chain and end at the same Trust Anchor as the
     RP's chain;
   - replay: the `jti` is recorded through `RegistrationProofJtiStore` and a `jti` seen before for the same client is
     refused.
3. **Register the client.** The result holds the verified client (`trustAnchor`, `trustChain`, `resolvedMetadata`,
   `clientMetadata`, `signingKeys`, `validUntilEpochSeconds`), the key that verified the proof, the verified Request
   Object claims and the Peer Trust Chain. The host registers the client from `clientMetadata` and must not use the
   registration after `validUntilEpochSeconds`, the earliest expiry in the Trust Chain. After that it resolves the
   client again.

`fed.registration.resolve-client` performs the resolution part of step 2 on its own: it takes a `clientId`, the
profile and the accepted Trust Anchors and returns the same verified client without checking any proof (§12.1.1.1.2).
Its result is not evidence that a request came from the client; a proof must still verify with one of its
`signingKeys`.

The bundled `RegistrationProofJtiStore` keeps used `jti` values in memory and treats a full store as a replay. A host
with more than one node, or that needs replay protection across requests handled in different sessions, binds its
own shared store.

## Explicit Registration (§12.2)

With Explicit Registration the RP sends a registration request to the OP's `federation_registration_endpoint` and
receives a signed registration response that contains its `client_id`. Each side signs with the selected Federation
Entity Key of its own account.

### Step 1, RP: create the request with `fed.registration.create-explicit-request`

The host passes the RP account (`accountId`), the OP's Entity Identifier, the profile, the RP's accepted Trust
Anchors, the Immediate Superiors to put in `authority_hints`, the metadata to register (keyed by Entity Type and
containing the client Entity Type), and whether to include Trust Chains in the request header
(`includeTrustChains`). The command:

- resolves and verifies the OP's Trust Chain to one of the accepted anchors, applies its policies, and checks that the
  OP's provider metadata lists `explicit` in `client_registration_types_supported` and has an https
  `federation_registration_endpoint`;
- checks that `authority_hints` is not empty, has no duplicates, contains only Immediate Superiors listed in the RP's
  own Entity Configuration, and that each of them leads to the Trust Anchor selected for the OP;
- builds the request from the RP's prepared Entity Configuration with `iat` set to now, `aud` set to the OP, and the
  requested `authority_hints` and `metadata`, so the request expires when that Entity Configuration would
  (`iat` plus `oidf.federation.statement.lifetime.seconds`);
- when `includeTrustChains` is set, adds the RP's Trust Chain as the `trust_chain` header and the OP's chain as the
  `peer_trust_chain` header;
- signs it with the RP account's selected key (`typ` `entity-statement+jwt`).

The result holds the request `body`, its `contentType` (`application/entity-statement+jwt`), the
`registrationEndpoint` to POST it to, and the OP Trust Chain and Trust Anchor the request was built against. The host
keeps this result: step 5 needs it.

### Step 2, RP: send the request

The host POSTs `body` to `registrationEndpoint` with `contentType`. An RP may instead send a Trust Chain as a JSON
array with `application/trust-chain+json`, whose first element is the request Entity Configuration.

### Step 3, OP: verify the request with `fed.registration.verify-explicit-request`

The host passes its Entity Identifier, the profile, the request `contentType` and `body` as received, and its
accepted Trust Anchors. The command accepts both media types. For `application/trust-chain+json` it takes the first
element as the request and verifies the array as the RP's Trust Chain; such a request must not also carry
`trust_chain` or `peer_trust_chain` headers. It then checks:

- that the request is a valid Entity Configuration: `iss` and `sub` equal and an https URL, a valid Entity Statement
  header and structure (OpenID Federation 1.1 §3), `aud` exactly the OP, non-empty `authority_hints`, `metadata` that
  contains the client Entity Type, a `jwks`, and a signature by a key from that `jwks`;
- the RP's Trust Chain: the body chain, the `trust_chain` header, or otherwise a chain resolved starting from the
  request `authority_hints`; it must end at an accepted Trust Anchor and verify against its keys;
- that the chain continues at one of the request `authority_hints`, and that this Immediate Superior's Subordinate
  Statement lists the key that signed the request and that key verifies the request (§12.2.2);
- that the request metadata survives the chain's metadata policies and still contains the client Entity Type;
- the Peer Trust Chain, when sent: it must verify as the OP's own chain and end at the same Trust Anchor.

The result holds the client Entity Identifier, the selected `trustAnchor`, `trustChain` and `immediateSuperior`, the
request `jwks`, the requested Entity Types, the `resolvedMetadata` and `clientMetadata`, and
`validUntilEpochSeconds`, the earlier of the chain's earliest expiry and the request's `exp`.

### Step 4, OP: create the registration

Between verifying the request and signing the response the host invalidates any earlier registration for the client,
assigns the `client_id` and any credentials, decides the registered client metadata from `clientMetadata`, and stores
the registration.

### Step 5, OP: sign the response with `fed.registration.sign-explicit-response`

The host passes the OP account, the verified request from step 3, the registered client metadata (which must contain
`client_id`), the registration expiry `expiresAtEpochSeconds`, and whether to copy the request `jwks` into the
response (`includeClientJwks`). The command refuses an account that is not the OP the request was addressed to, an
expiry in the past or later than the request's `validUntilEpochSeconds`, and a `client_secret` without
`client_secret_expires_at` or that expires before the registration (0 means it does not expire). It signs a response
with `iss` the OP, `sub` and `aud` the RP, `iat`, `exp` set to the registration expiry, `trust_anchor`,
`authority_hints` holding the Immediate Superior of the verified chain, and `metadata` with the registered client
metadata plus the Resolved Metadata of the other requested Entity Types. The header `typ` is
`explicit-registration-response+jwt` and the result's content type is
`application/explicit-registration-response+jwt` (§12.2.3).

### Step 6, RP: verify the response with `fed.registration.verify-explicit-response`

The host passes the request result from step 1, the response JWT and the RP's accepted Trust Anchors. The command
checks that:

- the header `typ` is `explicit-registration-response+jwt` with an asymmetric `alg` and a `kid`;
- `iss` is the OP, `sub` is the RP, `aud` is exactly the RP, `iat` is present and `exp` has not passed;
- the OP Trust Chain resolved in step 1 has not expired and its Immediate Superior's statement lists the key that
  signed the response, and that key verifies it;
- `trust_anchor` is one of the RP's accepted anchors, and when step 1 included Trust Chains it is the anchor of those
  chains;
- `authority_hints` holds exactly one entry that was requested, and the RP's Trust Chain through that superior
  resolves to the Trust Anchor;
- the registered metadata covers the same Entity Types as the request, contains `client_id` for the client Entity
  Type, and satisfies the metadata policies of the RP's Trust Chain (§12.2.5).

The result holds the `clientId`, the registered metadata, the RP's Trust Chain and `validUntilEpochSeconds`.

## Lifetimes

An automatic registration lasts until the earliest expiry in the RP's Trust Chain. An Explicit Registration request
expires with the RP's prepared Entity Configuration, `oidf.federation.statement.lifetime.seconds` after it is created.
The OP's verified request is valid until the earlier of the RP's chain expiry and the request `exp`, and the
registration the OP signs cannot outlive that. The RP treats the registration as valid until the earliest of the
response `exp`, its own Trust Chain and the OP Trust Chain (§12.3). After that the parties register again.

## Errors

The commands return these errors. The code is what a host puts in an error response.

| Error | Code | Raised for |
| --- | --- | --- |
| `InvalidRegistrationError` | `invalid_request` | A malformed or unacceptable request, proof or response: wrong claims, audiences, headers, algorithms, signatures, replayed `jti`, expiry rules |
| `InvalidMetadataError` | `invalid_metadata` | Missing client or provider metadata, metadata that fails the chain's policies, missing or ambiguous client signing keys, Entity Types that differ between request and response |
| `InvalidTrustAnchorError` | `invalid_trust_anchor` | A chain that ends at an anchor the caller does not accept, an anchor whose Entity Configuration cannot be used, a Peer Trust Chain that ends elsewhere |
| `TrustChainValidationFailedError` | `invalid_trust_chain` | A Trust Chain that cannot be resolved, does not verify or has expired |

A missing signing key selection on the signing side fails with an `invalid_request` error from the key resolution.
Per §12.1.3, an OP does not redirect trust failures to the RP's redirection URI.

## Command reference

| Command | Id | Side |
| --- | --- | --- |
| `ResolveRegistrationClientCommand` | `fed.registration.resolve-client` | OP: resolve an RP by Entity Identifier to its verified Trust Chain, Resolved Metadata and client signing keys |
| `VerifyAutomaticRegistrationCommand` | `fed.registration.verify-automatic` | OP: verify an Automatic Registration proof and resolve the RP |
| `CreateExplicitRegistrationRequestCommand` | `fed.registration.create-explicit-request` | RP: build and sign an Explicit Registration request |
| `VerifyExplicitRegistrationRequestCommand` | `fed.registration.verify-explicit-request` | OP: verify an `application/entity-statement+jwt` or `application/trust-chain+json` request |
| `SignExplicitRegistrationResponseCommand` | `fed.registration.sign-explicit-response` | OP: sign the registration response |
| `VerifyExplicitRegistrationResponseCommand` | `fed.registration.verify-explicit-response` | RP: verify the registration response |
