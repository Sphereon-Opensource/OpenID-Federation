package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*

/**
 * Generic utilities for working with OpenID Federation entity metadata.
 *
 * Provides extraction and validation methods that are not profile-specific
 * and can be reused across different federation profiles and commands.
 */
object FederationEntityMetadata {

    /**
     * Result of extracting an issuer identifier from a credential.
     */
    data class IssuerIdentifierResult(
        val identifier: String?,
        val source: String,
        val error: String? = null
    )

    // =========================================================================
    // Credential issuer identifier extraction
    // =========================================================================

    /**
     * Extract the federation entity identifier from an SD-JWT VC credential payload.
     *
     * Per OpenID Federation Wallet Architecture: the `fed` claim contains the Entity Identifier.
     * Falls back to `iss` claim if `fed` is not present.
     */
    fun extractIssuerIdentifier(payload: JsonObject): IssuerIdentifierResult {
        // Primary: use `fed` claim
        val fedClaim = payload["fed"]?.jsonPrimitive?.contentOrNull
        if (fedClaim != null) {
            return IssuerIdentifierResult(identifier = fedClaim, source = "fed")
        }

        // Fallback: use `iss` claim
        val issClaim = payload["iss"]?.jsonPrimitive?.contentOrNull
        if (issClaim != null) {
            return IssuerIdentifierResult(identifier = issClaim, source = "iss")
        }

        return IssuerIdentifierResult(
            identifier = null,
            source = "none",
            error = "No 'fed' or 'iss' claim found in credential"
        )
    }

    /**
     * Validates that the `fed` claim in the credential matches the expected entity identifier.
     */
    fun validateFedClaim(credentialPayload: JsonObject, entityIdentifier: String): Boolean {
        val fedClaim = credentialPayload["fed"]?.jsonPrimitive?.contentOrNull
        return fedClaim != null && fedClaim == entityIdentifier
    }

    // =========================================================================
    // vc_issuer key extraction
    // =========================================================================

    /**
     * Extract signing keys from `vc_issuer.jwks.keys` in entity configuration metadata.
     *
     * @param metadata The entity configuration's `metadata` object
     * @return List of key objects from vc_issuer, or empty if not present
     */
    fun extractVcIssuerKeys(metadata: JsonObject): List<JsonObject> {
        val vcIssuer = metadata["vc_issuer"]?.jsonObject ?: return emptyList()
        val jwks = vcIssuer["jwks"]?.jsonObject ?: return emptyList()
        val keys = jwks["keys"]?.jsonArray ?: return emptyList()
        return keys.mapNotNull { it as? JsonObject }
    }

    /**
     * Find a key matching the given kid in the vc_issuer JWKS.
     *
     * @param metadata The entity configuration's `metadata` object
     * @param kid The key ID to match
     * @return The matching key as JsonObject, or null
     */
    fun findVcIssuerKey(metadata: JsonObject, kid: String): JsonObject? {
        return extractVcIssuerKeys(metadata).firstOrNull { key ->
            key["kid"]?.jsonPrimitive?.contentOrNull?.trim() == kid.trim()
        }
    }

    // =========================================================================
    // Metadata helpers
    // =========================================================================

    /**
     * Get the display_name from federation_entity metadata.
     */
    fun getDisplayName(metadata: JsonObject): String? {
        return metadata["federation_entity"]?.jsonObject
            ?.get("display_name")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Check if federation metadata should take precedence over well-known OID4VCI metadata.
     *
     * Per DIIP/federation spec: if `openid_credential_issuer` is in the entity configuration,
     * wallets MUST use only this metadata and ignore regular issuer metadata.
     */
    fun hasFederationIssuerMetadata(metadata: JsonObject): Boolean {
        return metadata.containsKey("openid_credential_issuer")
    }

    /**
     * Extract metadata for a specific entity type from the entity configuration metadata.
     */
    fun getEntityTypeMetadata(metadata: JsonObject, entityType: String): JsonObject? {
        return metadata[entityType]?.jsonObject
    }
}
