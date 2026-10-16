# Pinned Fedservice native startup trace

This is source-only evidence for the private v4 recipe candidate. It derives from exact archive bytes already pinned in the v3 recipe and does not claim a build or runtime pass.

- SUNET/fedservice commit: `0304f72453214c4f85bed7f792a6b5ad39f357d3`
- Tree: `ff13cafa2714eb3680e7c76aca55e080f9b91c78`
- Source archive SHA-256: `2086908175a6e1bcf72fe756daad43bec7075b06419479e1378c19d95589ac1a`

## Failed predecessor

The exact `dc4eu_federation/entity.py` executable branch (lines 44–66) reaches line 60 and reads `app.federation_entity.function.trust_chain_collector.trust_anchors.items()`. In the same archive, `src/fedservice/entity/function/trust_chain_collector.py` defines `TrustChainCollector.__init__(..., trust_anchor=...)`, imports those keys when supplied, but exposes no `trust_anchors` field; its method is `add_trust_anchor`. The pinned `src/fedservice/entity/context.py` lines 79–83 initializes `FederationContext.trust_anchor = {}`. The observed line-60 access is therefore invalid for this source.

The archive's package tests do not exercise either executable main block or reference `edu_federation`; this capsule treats upstream docs and source as static evidence, not test evidence.

## Same-commit native example

`edu_federation/README_identity.rst` lines 20–48 describes the trust-anchor role's `conf.json` and `views.py`, then documents `./entity.py trust_anchor` and its generated private/static/subordinate state. This is an upstream example from the same experimental commit, not a supported release.

`edu_federation/entity.py` lines 20–40 imports the role's real views, loads `<role>/conf.json`, passes the native entity config to `make_federation_combo`, and binds its real federation entity. Lines 43–59 are the example's executable TLS Flask server path; line 55 reads `app.federation_entity.context.trust_anchor.items()`, matching the field initialized in `FederationContext`.

The protocol surface is also native: `edu_federation/trust_anchor/views.py` lines 182–195 registers `/.well-known/openid-federation`, calls `create_entity_configuration(iss, key_jar, metadata, authority_hints, lifetime, include_jwks=True)`, and returns the signed JWT as `application/entity-statement+jwt`. The same view module's `/resolve` is GET-only (lines 165–168); `service_endpoint` parses GET query args (lines 78–100).

The sample role config (`edu_federation/trust_anchor/conf.json`) carries the native nested `entity.entity_type.federation_entity` structure, RSA and P-256 signing key definitions, `fetch`, `list`, `entity_configuration`, and `resolve` endpoints, and a file-backed subordinate store. Its sample `httpc_params.verify=false` must be replaced by the already scoped mounted peer CA; recipe v4 keeps that verification, mounted TLS, persistent key/store paths, restrictive wrapper checks, and UID 10001 execution. The sample's `AbstractFileSystemNoCache` is not copied: the unchanged v3 recipe config uses pinned `idpyoidc.storage.abfile.AbstractFileSystem`, whose exact constructor accepts the configured `fdir`, `key_conv`, and `value_conv` (`idpy-oidc` pinned archive, `src/idpyoidc/storage/abfile.py` lines 16–71). `fedservice/utils.py` lines 110–119 and 180–218 route this native nested config to `make_federation_entity`; lines 138–145 execute the subordinate storage descriptor.

Pinned `idpyoidc/ssl_context.py` lines 13–29 explicitly accepts absolute server cert/key paths, so the mounted `/run/tls/...` paths remain valid. The wrapper's `chdir` to the app root is required because the example's `load_config_file(f"{dir_name}/conf.json")` is cwd-relative.

## Boundary

This is a source-supported alternate application entrypoint with the same native FederationEntity protocol implementation. It does not repair or patch the faulty `dc4eu_federation` example. The adapted app path, mounted config, TLS, keys, native startup, signed EC response, restart persistence, and Sphereon interop remain to be built and verified as a new candidate.
