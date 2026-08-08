package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Validates entities against the DIIP (Digital Identity Interoperability Profile)
 * OpenID Federation Digital Credentials Profile (Appendix B).
 *
 * Dual-profile acceptance (item 20): pure wallet-architecture entities that publish
 * `organization_name` and/or `openid_credential_issuer.jwks` are accepted as alternatives
 * to DIIP-only `display_name` and `vc_issuer.jwks`. Failures still report the DIIP
 * check names for compatibility; details note dual-profile fallbacks when used.
 *
 * Generic federation operations live in [FederationEntityMetadata]; wallet-native
 * checks live in [WalletProfileValidator].
 */
object DiipProfileValidator {

    /**
     * Result of a single DIIP profile validation check.
     *
     * Kept as a nested type for binary/source compatibility with existing callers;
     * structurally identical to [MetadataValidationCheck].
     */
    @Serializable
    data class DiipValidation(
        val check: String,
        val passed: Boolean,
        val detail: String? = null
    ) {
        fun toMetadataCheck(): MetadataValidationCheck = MetadataValidationCheck(
            check = check,
            passed = passed,
            detail = detail,
            profile = MetadataValidationCheck.PROFILE_DIIP,
        )
    }

    /**
     * Validate that an entity's metadata conforms to DIIP profile requirements
     * with dual-profile wallet field acceptance.
     *
     * DIIP Appendix B (with dual acceptance):
     * - `federation_entity.display_name` **or** `organization_name`
     * - For issuers: `openid_credential_issuer` present, `credential_issuer` matches entity ID,
     *   signing keys in `vc_issuer.jwks` **or** `openid_credential_issuer.jwks`
     * - For verifiers: `openid_credential_verifier` present
     */
    fun validate(
        metadata: JsonObject,
        entityIdentifier: String,
        entityType: String? = null
    ): List<DiipValidation> {
        val validations = mutableListOf<DiipValidation>()

        // 1. display_name OR organization_name (dual-profile)
        val displayName = FederationEntityMetadata.getDisplayName(metadata)
        val orgName = FederationEntityMetadata.getOrganizationName(metadata)
        val hasName = !displayName.isNullOrBlank() || !orgName.isNullOrBlank()
        validations.add(
            DiipValidation(
                check = "federation_entity.display_name",
                passed = hasName,
                detail = when {
                    !hasName -> "Missing federation_entity.display_name (and organization_name dual-profile fallback)"
                    displayName.isNullOrBlank() && !orgName.isNullOrBlank() ->
                        "Using federation_entity.organization_name (dual-profile wallet field)"
                    else -> null
                },
            ),
        )

        when (entityType) {
            WalletEntityTypes.OPENID_CREDENTIAL_ISSUER,
            "openid_credential_issuer" -> {
                validations.addAll(validateIssuer(metadata, entityIdentifier))
            }
            WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER,
            "openid_credential_verifier" -> {
                validations.addAll(validateVerifier(metadata))
            }
        }

        return validations
    }

    private fun validateIssuer(metadata: JsonObject, entityIdentifier: String): List<DiipValidation> {
        val validations = mutableListOf<DiipValidation>()

        val issuerMetadata = metadata[WalletEntityTypes.OPENID_CREDENTIAL_ISSUER]?.jsonObject
        validations.add(
            DiipValidation(
                check = "openid_credential_issuer_present",
                passed = issuerMetadata != null,
                detail = if (issuerMetadata == null) "Missing openid_credential_issuer metadata" else null,
            ),
        )

        if (issuerMetadata != null) {
            val credentialIssuer = issuerMetadata["credential_issuer"]?.jsonPrimitive?.contentOrNull
            validations.add(
                DiipValidation(
                    check = "credential_issuer_matches_entity_id",
                    passed = credentialIssuer == entityIdentifier,
                    detail = if (credentialIssuer != entityIdentifier)
                        "credential_issuer '$credentialIssuer' does not match entity identifier '$entityIdentifier'"
                    else null,
                ),
            )
        }

        // Dual-profile: openid_credential_issuer.jwks preferred, vc_issuer.jwks accepted
        val keys = FederationEntityMetadata.extractCredentialIssuerKeys(metadata)
        val source = FederationEntityMetadata.credentialIssuerKeySource(metadata)
        validations.add(
            DiipValidation(
                check = "vc_issuer_signing_keys",
                passed = keys.isNotEmpty(),
                detail = when {
                    keys.isEmpty() ->
                        "Missing or empty vc_issuer.jwks / openid_credential_issuer.jwks signing keys"
                    source == "openid_credential_issuer.jwks" ->
                        "Using openid_credential_issuer.jwks (dual-profile wallet field)"
                    else -> null
                },
            ),
        )

        if (keys.isNotEmpty()) {
            val hasSigningKey = keys.any { key ->
                val use = key["use"]?.jsonPrimitive?.contentOrNull
                use == null || use == "sig"
            }
            validations.add(
                DiipValidation(
                    check = "vc_issuer_has_sig_key",
                    passed = hasSigningKey,
                    detail = if (!hasSigningKey)
                        "No signing keys (use: sig) found in issuer jwks"
                    else null,
                ),
            )
        }

        return validations
    }

    private fun validateVerifier(metadata: JsonObject): List<DiipValidation> {
        val validations = mutableListOf<DiipValidation>()

        val verifierMetadata = metadata[WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER]?.jsonObject
        validations.add(
            DiipValidation(
                check = "openid_credential_verifier_present",
                passed = verifierMetadata != null,
                detail = if (verifierMetadata == null) "Missing openid_credential_verifier metadata" else null,
            ),
        )

        return validations
    }
}
