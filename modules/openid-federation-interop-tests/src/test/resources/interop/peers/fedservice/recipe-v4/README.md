# Fedservice experimental trust-anchor producer recipe v4

## Scope and status

This is a sealed private recipe candidate, not a supported upstream release and not a runtime result. It packages the documented `edu_federation` trust-anchor role from the same pinned, unmerged and untagged SUNET/fedservice commit already used by recipe v3. It leaves every upstream source archive byte unchanged. The previous v3 image/state remain preserved as failure evidence: its import-only gate passed, but the `dc4eu_federation/entity.py` process exited at startup because that entrypoint reads a nonexistent collector `trust_anchors` attribute.

Recipe v4 instead invokes the source-documented `edu_federation/entity.py trust_anchor` path. The pinned example's `README_identity.rst` lines 20–48 documents the role and that exact invocation. Its native `entity.py` executable path reads `federation_entity.context.trust_anchor`, a real field initialized by pinned `FederationContext`; its upstream `/\.well-known/openid-federation` view calls `create_entity_configuration` with the native keyjar/metadata and returns the signed JWT with the entity-statement media type. This is an actual upstream Flask application and protocol handler, not a substitute route or a patch to the failed entrypoint.

The pinned archive is SUNET/fedservice commit `0304f72453214c4f85bed7f792a6b5ad39f357d3`, tree `ff13cafa2714eb3680e7c76aca55e080f9b91c78`, archive SHA-256 `2086908175a6e1bcf72fe756daad43bec7075b06419479e1378c19d95589ac1a`. See `native-entrypoint-alternative.md` for the source trace and exact boundaries.

## Runtime recipe

The image copies the immutable upstream `edu_federation` tree and points the owned fail-closed entrypoint wrapper to that application directory. The wrapper sets that directory as cwd before executing the upstream `entity.py trust_anchor`, as required by the example's relative `trust_anchor/conf.json` load. The mounted `trust-anchor-config.rs256.json` is byte-identical to v3: it uses the native nested `entity_type.federation_entity` config, RSA and P-256 signing key definitions, normal federation endpoints, persistent key and subordinate paths, mounted peer CA verification, and mounted server TLS paths. The pinned `idpyoidc.ssl_context.create_context` accepts absolute cert and key paths. No TLS verification relaxation is present.

The wrapper initializes restricted TLS-key ownership, drops to UID 10001, disables redirects and proxies, requires WARNING-or-higher root logging, and limits config paths to the approved CA, TLS and state mount roots. File-backed `idpyoidc.storage.abfile.AbstractFileSystem` accepts the existing `fdir`, `key_conv`, and `value_conv` settings; the example's `make_federation_combo` passes the nested federation-entity config to `make_federation_entity`, and `args_eval` constructs the declared subordinate store. A new empty dedicated state volume is required; do not reuse v3's state.

The subordinate provisioning helper incorporates the separately reviewed one-byte LF repair: only the CR byte on the final continued `--target "$state_dir/subordinates"` line was removed. Its parent candidate is `fedservice-provision-subordinate-lf-repair-v1`; the v3 helper and v3 recipe remain unchanged. The helper is not executed by this capsule.

## Qualification gates

No image build, container startup, peer request, signature verification, persistence/restart test, or interop test was performed while preparing this recipe. Its native startup path and adapted mounted configuration still require a fresh source-build/import/start gate. The published experimental status, exact pinned dependency archives, hashes and license copies remain recorded in `recipe-manifest.json` and `closure-manifest.json`. Trust-mark issuer behavior is not claimed or provisioned by this recipe.

