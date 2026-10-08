# Client registration

These commands implement OpenID Federation for OpenID Connect 1.1, Section 12. They establish trust between a
client and a provider through Trust Chains, for OpenID Connect (`openid_relying_party` with `openid_provider`) or
another OAuth 2.0 profile (`oauth_client` with `oauth_authorization_server`), selected with `RegistrationProfile`.

The commands own the protocol: Trust Chain resolution and verification, metadata policy, signatures, audiences,
media types and lifetimes. The host owns the rest: which Trust Anchors it accepts, how client identifiers and
credentials are assigned, and where registrations are stored.

## Trust Anchors

Every command takes the accepted Trust Anchors as `RegistrationTrustAnchor` values. A Trust Anchor is referenced by
its HTTPS Entity Identifier: its keys come from the self-signed Entity Configuration it publishes at its own
`/.well-known/openid-federation`, never from statements a counterparty supplies. A Trust Chain is accepted only when it
ends at one of these anchors and verifies against that anchor's keys.

`pinnedKeys` replaces the lookup with keys distributed another way. It is an option for special deployments and is
not normally set.

## Automatic Registration

The client uses its Entity Identifier as `client_id` and registers implicitly with its first request.

| Command | Id | Use |
| --- | --- | --- |
| `ResolveRegistrationClientCommand` | `fed.registration.resolve-client` | Resolve an unknown `client_id`: verified Trust Chain, Resolved Metadata and the client's published signing keys. |
| `VerifyAutomaticRegistrationCommand` | `fed.registration.verify-automatic` | Verify the request proof: a Request Object, a `private_key_jwt` client assertion at the PAR endpoint, or a self-signed TLS client certificate. |

A Request Object must carry `iss` and `client_id` equal to the client, `aud` equal to the provider and nothing else,
no `sub`, a `jti` and an `exp`. A `trust_chain` header is verified instead of discovering the chain; a
`peer_trust_chain` header must end at the same Trust Anchor. Proof keys come from the client's metadata for the
client Entity Type (`jwks`, `signed_jwks_uri` signed with a Federation Entity Key, or `jwks_uri`). Each `jti` is
accepted once through `RegistrationProofJtiStore`; the bundled in-memory store suits a single node only, so hosts
with more than one node bind a shared store.

The registration must not be used after `validUntilEpochSeconds`, the earliest expiry in the Trust Chain.

## Explicit Registration

The client sends its Entity Configuration, addressed to the provider, to the provider's
`federation_registration_endpoint` and receives a signed registration.

| Command | Id | Side |
| --- | --- | --- |
| `CreateExplicitRegistrationRequestCommand` | `fed.registration.create-explicit-request` | Client: resolve the provider, check it supports `explicit`, and sign the request with the account's selected Federation Entity Key. |
| `VerifyExplicitRegistrationRequestCommand` | `fed.registration.verify-explicit-request` | Provider: verify an `application/entity-statement+jwt` or `application/trust-chain+json` request. |
| `SignExplicitRegistrationResponseCommand` | `fed.registration.sign-explicit-response` | Provider: sign the `explicit-registration-response+jwt` response for the registration the host created. |
| `VerifyExplicitRegistrationResponseCommand` | `fed.registration.verify-explicit-response` | Client: verify the response against the provider Trust Chain resolved for the request. |

The provider verifies that the Immediate Superior in the selected Trust Chain lists the key that signed the request,
and resolves the request metadata under that chain's policies. Between verifying the request and signing the
response, the host invalidates any earlier registration for the client, assigns the `client_id` and any
credentials, and stores the registration. The response expiration cannot exceed the verified Trust Chain, and a
provisioned `client_secret` must not expire before the registration.

The client accepts a response only when it is signed with a key that the provider's Immediate Superior lists, is
addressed to the client, names one of the client's Trust Anchors, continues at one of the requested
`authority_hints`, covers the same Entity Types as the request, and satisfies the policies of the client's Trust
Chain to that Trust Anchor.

## Errors

Registration failures return `InvalidRegistrationError` (`invalid_request`), `InvalidMetadataError`
(`invalid_metadata`), `InvalidTrustAnchorError` (`invalid_trust_anchor`) or `TrustChainValidationFailedError`
(`invalid_trust_chain`). Per Section 12.1.3, a provider does not redirect trust failures to the client's
redirection URI.
