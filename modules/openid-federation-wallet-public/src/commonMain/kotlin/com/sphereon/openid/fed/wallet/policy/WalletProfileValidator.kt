package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates entity metadata against the OpenID Federation Wallet Architecture profile
 * (draft 05 Table 1 / §6), not DIIP-specific fields.
 *
 * - Prefers `federation_entity.organization_name` (OIDFed 1.1 recommended) with
 *   `display_name` accepted as a dual-profile fallback.
 * - Credential Issuer keys from `openid_credential_issuer.jwks` (OpenID4VCI / wallet),
 *   with `vc_issuer.jwks` accepted as a dual-profile fallback for DIIP deployments.
 * - Dedicated checks for `oauth_authorization_server` and `federation_entity`.
 */
object WalletProfileValidator {

    private fun check(
        name: String,
        passed: Boolean,
        detail: String? = null,
    ) = MetadataValidationCheck(
        check = name,
        passed = passed,
        detail = detail,
        profile = MetadataValidationCheck.PROFILE_WALLET,
    )

    /**
     * Validate metadata for the given entity type (wallet architecture).
     *
     * @param metadata Entity Configuration / Resolved Metadata object
     * @param entityIdentifier Entity Identifier (URL)
     * @param entityType Target entity type; when null, only federation_entity name checks run
     */
    fun validate(
        metadata: JsonObject,
        entityIdentifier: String,
        entityType: String? = null,
    ): List<MetadataValidationCheck> {
        val validations = mutableListOf<MetadataValidationCheck>()

        // organization_name (preferred) or display_name (dual-profile) for all entities
        val orgName = FederationEntityMetadata.getOrganizationName(metadata)
        val displayName = FederationEntityMetadata.getDisplayName(metadata)
        val hasName = !orgName.isNullOrBlank() || !displayName.isNullOrBlank()
        validations.add(
            check(
                name = "federation_entity.organization_name",
                passed = hasName,
                detail = when {
                    hasName && !orgName.isNullOrBlank() -> null
                    hasName -> "Using federation_entity.display_name (dual-profile); prefer organization_name"
                    else -> "Missing federation_entity.organization_name (and display_name fallback)"
                },
            ),
        )

        when (entityType) {
            WalletEntityTypes.OPENID_CREDENTIAL_ISSUER ->
                validations.addAll(validateCredentialIssuer(metadata, entityIdentifier))
            WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER ->
                validations.addAll(validateCredentialVerifier(metadata))
            WalletEntityTypes.OPENID_WALLET_PROVIDER ->
                validations.addAll(validateWalletProvider(metadata))
            WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER ->
                validations.addAll(validateAuthorizationServer(metadata, entityIdentifier))
            WalletEntityTypes.FEDERATION_ENTITY ->
                validations.addAll(validateFederationEntity(metadata))
            null -> { /* name check only */ }
            else -> {
                // Unknown type: only require the type key when it looks like a leaf role
                if (entityType.isNotBlank()) {
                    val present = metadata[entityType]?.jsonObject != null
                    validations.add(
                        check(
                            name = "entity_type.$entityType.present",
                            passed = present,
                            detail = if (!present) "Missing metadata.$entityType" else null,
                        ),
                    )
                }
            }
        }

        return validations
    }

    private fun validateFederationEntity(metadata: JsonObject): List<MetadataValidationCheck> {
        val fed = metadata[WalletEntityTypes.FEDERATION_ENTITY]?.jsonObject
        return listOf(
            check(
                name = "federation_entity_present",
                passed = fed != null,
                detail = if (fed == null) "Missing federation_entity metadata" else null,
            ),
        )
    }

    private fun validateWalletProvider(metadata: JsonObject): List<MetadataValidationCheck> {
        val wp = metadata[WalletEntityTypes.OPENID_WALLET_PROVIDER]?.jsonObject
        return listOf(
            check(
                name = "openid_wallet_provider_present",
                passed = wp != null,
                detail = if (wp == null) "Missing openid_wallet_provider metadata" else null,
            ),
        )
    }

    private fun validateCredentialVerifier(metadata: JsonObject): List<MetadataValidationCheck> {
        val cv = metadata[WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER]?.jsonObject
        return listOf(
            check(
                name = "openid_credential_verifier_present",
                passed = cv != null,
                detail = if (cv == null) "Missing openid_credential_verifier metadata" else null,
            ),
        )
    }

    private fun validateAuthorizationServer(
        metadata: JsonObject,
        entityIdentifier: String,
    ): List<MetadataValidationCheck> {
        val validations = mutableListOf<MetadataValidationCheck>()
        val asMeta = metadata[WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER]?.jsonObject
        validations.add(
            check(
                name = "oauth_authorization_server_present",
                passed = asMeta != null,
                detail = if (asMeta == null) "Missing oauth_authorization_server metadata" else null,
            ),
        )
        if (asMeta != null) {
            // RFC8414 issuer is commonly published; when present it SHOULD match the Entity Identifier
            val issuer = asMeta["issuer"]?.jsonPrimitive?.contentOrNull
            if (issuer != null) {
                validations.add(
                    check(
                        name = "oauth_authorization_server.issuer_matches_entity_id",
                        passed = issuer == entityIdentifier,
                        detail = if (issuer != entityIdentifier)
                            "issuer '$issuer' does not match entity identifier '$entityIdentifier'"
                        else null,
                    ),
                )
            }
            // Prefer at least one protocol endpoint so AS metadata is not an empty shell
            val hasEndpoint = listOf(
                "authorization_endpoint",
                "token_endpoint",
                "jwks_uri",
            ).any { key -> !asMeta[key]?.jsonPrimitive?.contentOrNull.isNullOrBlank() } ||
                asMeta["jwks"]?.jsonObject != null
            validations.add(
                check(
                    name = "oauth_authorization_server.has_protocol_surface",
                    passed = hasEndpoint,
                    detail = if (!hasEndpoint)
                        "oauth_authorization_server should publish authorization_endpoint, token_endpoint, jwks_uri, or jwks"
                    else null,
                ),
            )
        }
        return validations
    }

    private fun validateCredentialIssuer(
        metadata: JsonObject,
        entityIdentifier: String,
    ): List<MetadataValidationCheck> {
        val validations = mutableListOf<MetadataValidationCheck>()

        val issuerMetadata = metadata[WalletEntityTypes.OPENID_CREDENTIAL_ISSUER]?.jsonObject
        validations.add(
            check(
                name = "openid_credential_issuer_present",
                passed = issuerMetadata != null,
                detail = if (issuerMetadata == null) "Missing openid_credential_issuer metadata" else null,
            ),
        )

        if (issuerMetadata != null) {
            val credentialIssuer = issuerMetadata["credential_issuer"]?.jsonPrimitive?.contentOrNull
            validations.add(
                check(
                    name = "credential_issuer_matches_entity_id",
                    passed = credentialIssuer == entityIdentifier,
                    detail = if (credentialIssuer != entityIdentifier)
                        "credential_issuer '$credentialIssuer' does not match entity identifier '$entityIdentifier'"
                    else null,
                ),
            )
        }

        val keys = FederationEntityMetadata.extractCredentialIssuerKeys(metadata)
        val source = FederationEntityMetadata.credentialIssuerKeySource(metadata)
        validations.add(
            check(
                name = "credential_issuer_signing_keys",
                passed = keys.isNotEmpty(),
                detail = when {
                    keys.isNotEmpty() && source == "openid_credential_issuer.jwks" -> null
                    keys.isNotEmpty() -> "Using $source (dual-profile); prefer openid_credential_issuer.jwks"
                    else -> "Missing openid_credential_issuer.jwks (and vc_issuer.jwks fallback)"
                },
            ),
        )

        if (keys.isNotEmpty()) {
            val hasSigningKey = keys.any { key ->
                val use = key["use"]?.jsonPrimitive?.contentOrNull
                use == null || use == "sig"
            }
            validations.add(
                check(
                    name = "credential_issuer_has_sig_key",
                    passed = hasSigningKey,
                    detail = if (!hasSigningKey) "No signing keys (use: sig) found in credential issuer jwks" else null,
                ),
            )
        }

        // CI may also host an AS (Table 1); optional presence is informational only
        if (metadata[WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER]?.jsonObject != null) {
            validations.add(
                check(
                    name = "oauth_authorization_server_co_located",
                    passed = true,
                    detail = "Credential Issuer also publishes oauth_authorization_server (optional per Table 1)",
                ),
            )
        }

        return validations
    }
}
