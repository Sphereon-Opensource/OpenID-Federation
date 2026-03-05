package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Validates entities against the DIIP (Digital Identity Interoperability Profile)
 * OpenID Federation Digital Credentials Profile (Appendix B).
 *
 * This contains ONLY DIIP-specific profile validation checks. Generic federation
 * operations (key extraction, fed claim parsing, etc.) live in [FederationEntityMetadata].
 */
object DiipProfileValidator {

    /**
     * Result of a single DIIP profile validation check.
     */
    @Serializable
    data class DiipValidation(
        val check: String,
        val passed: Boolean,
        val detail: String? = null
    )

    /**
     * Validate that an entity's metadata conforms to DIIP profile requirements.
     *
     * DIIP Appendix B requires:
     * - `federation_entity.display_name` for all entities
     * - For issuers: `openid_credential_issuer` present, `credential_issuer` matches entity ID, `vc_issuer.jwks` has signing keys
     * - For verifiers: `openid_credential_verifier` present
     *
     * @param metadata The entity configuration's `metadata` object
     * @param entityIdentifier The entity's identifier (URL)
     * @param entityType The entity type to validate
     * @return List of validation results
     */
    fun validate(
        metadata: JsonObject,
        entityIdentifier: String,
        entityType: String? = null
    ): List<DiipValidation> {
        val validations = mutableListOf<DiipValidation>()

        // 1. federation_entity.display_name is required for all entities
        val displayName = FederationEntityMetadata.getDisplayName(metadata)
        validations.add(DiipValidation(
            check = "federation_entity.display_name",
            passed = displayName != null && displayName.isNotBlank(),
            detail = if (displayName == null) "Missing federation_entity.display_name" else null
        ))

        // Entity type specific validations
        when (entityType) {
            "openid_credential_issuer" -> {
                validations.addAll(validateIssuer(metadata, entityIdentifier))
            }
            "openid_credential_verifier" -> {
                validations.addAll(validateVerifier(metadata))
            }
        }

        return validations
    }

    private fun validateIssuer(metadata: JsonObject, entityIdentifier: String): List<DiipValidation> {
        val validations = mutableListOf<DiipValidation>()

        // openid_credential_issuer must be present
        val issuerMetadata = metadata["openid_credential_issuer"]?.jsonObject
        validations.add(DiipValidation(
            check = "openid_credential_issuer_present",
            passed = issuerMetadata != null,
            detail = if (issuerMetadata == null) "Missing openid_credential_issuer metadata" else null
        ))

        // credential_issuer must match entity identifier
        if (issuerMetadata != null) {
            val credentialIssuer = issuerMetadata["credential_issuer"]?.jsonPrimitive?.contentOrNull
            validations.add(DiipValidation(
                check = "credential_issuer_matches_entity_id",
                passed = credentialIssuer == entityIdentifier,
                detail = if (credentialIssuer != entityIdentifier)
                    "credential_issuer '$credentialIssuer' does not match entity identifier '$entityIdentifier'"
                else null
            ))
        }

        // vc_issuer must be present with signing keys
        val vcIssuerKeys = FederationEntityMetadata.extractVcIssuerKeys(metadata)
        validations.add(DiipValidation(
            check = "vc_issuer_signing_keys",
            passed = vcIssuerKeys.isNotEmpty(),
            detail = if (vcIssuerKeys.isEmpty()) "Missing or empty vc_issuer.jwks signing keys" else null
        ))

        // vc_issuer keys should have use: "sig"
        if (vcIssuerKeys.isNotEmpty()) {
            val hasSigningKey = vcIssuerKeys.any { key ->
                val use = key["use"]?.jsonPrimitive?.contentOrNull
                use == null || use == "sig"
            }
            validations.add(DiipValidation(
                check = "vc_issuer_has_sig_key",
                passed = hasSigningKey,
                detail = if (!hasSigningKey) "No signing keys (use: sig) found in vc_issuer.jwks" else null
            ))
        }

        return validations
    }

    private fun validateVerifier(metadata: JsonObject): List<DiipValidation> {
        val validations = mutableListOf<DiipValidation>()

        val verifierMetadata = metadata["openid_credential_verifier"]?.jsonObject
        validations.add(DiipValidation(
            check = "openid_credential_verifier_present",
            passed = verifierMetadata != null,
            detail = if (verifierMetadata == null) "Missing openid_credential_verifier metadata" else null
        ))

        return validations
    }
}
