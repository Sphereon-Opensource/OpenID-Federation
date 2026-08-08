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
    // Credential issuer / vc_issuer key extraction (wallet + DIIP dual-profile)
    // =========================================================================

    /**
     * Extract signing keys for a Credential Issuer.
     *
     * Preference order (wallet architecture / OpenID4VCI first):
     * 1. `openid_credential_issuer.jwks.keys`
     * 2. `vc_issuer.jwks.keys` (DIIP dual-profile fallback)
     */
    fun extractCredentialIssuerKeys(metadata: JsonObject): List<JsonObject> {
        val fromOpenid = extractJwksKeys(metadata[WalletEntityTypes.OPENID_CREDENTIAL_ISSUER]?.jsonObject)
        if (fromOpenid.isNotEmpty()) return fromOpenid
        return extractVcIssuerKeys(metadata)
    }

    /**
     * Which key source [extractCredentialIssuerKeys] would use, for validation messaging.
     *
     * @return `"openid_credential_issuer.jwks"`, `"vc_issuer.jwks"`, or `null` if none
     */
    fun credentialIssuerKeySource(metadata: JsonObject): String? {
        if (extractJwksKeys(metadata[WalletEntityTypes.OPENID_CREDENTIAL_ISSUER]?.jsonObject).isNotEmpty()) {
            return "openid_credential_issuer.jwks"
        }
        if (extractVcIssuerKeys(metadata).isNotEmpty()) {
            return "vc_issuer.jwks"
        }
        return null
    }

    /**
     * Find a key matching [kid] in credential issuer JWKS (wallet then DIIP fallback).
     */
    fun findCredentialIssuerKey(metadata: JsonObject, kid: String): JsonObject? {
        return extractCredentialIssuerKeys(metadata).firstOrNull { key ->
            key["kid"]?.jsonPrimitive?.contentOrNull?.trim() == kid.trim()
        }
    }

    /**
     * Extract signing keys from `vc_issuer.jwks.keys` in entity configuration metadata (DIIP).
     *
     * @param metadata The entity configuration's `metadata` object
     * @return List of key objects from vc_issuer, or empty if not present
     */
    fun extractVcIssuerKeys(metadata: JsonObject): List<JsonObject> {
        return extractJwksKeys(metadata["vc_issuer"]?.jsonObject)
    }

    /**
     * Find a key matching the given kid in the vc_issuer JWKS.
     */
    fun findVcIssuerKey(metadata: JsonObject, kid: String): JsonObject? {
        return extractVcIssuerKeys(metadata).firstOrNull { key ->
            key["kid"]?.jsonPrimitive?.contentOrNull?.trim() == kid.trim()
        }
    }

    private fun extractJwksKeys(container: JsonObject?): List<JsonObject> {
        if (container == null) return emptyList()
        val jwks = container["jwks"]?.jsonObject ?: return emptyList()
        val keys = jwks["keys"]?.jsonArray ?: return emptyList()
        return keys.mapNotNull { it as? JsonObject }
    }

    // =========================================================================
    // Metadata helpers
    // =========================================================================

    /**
     * Get `organization_name` from `federation_entity` metadata (OIDFed 1.1 recommended).
     */
    fun getOrganizationName(metadata: JsonObject): String? {
        return metadata[WalletEntityTypes.FEDERATION_ENTITY]?.jsonObject
            ?.get("organization_name")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Get the display_name from federation_entity metadata (DIIP / legacy).
     */
    fun getDisplayName(metadata: JsonObject): String? {
        return metadata[WalletEntityTypes.FEDERATION_ENTITY]?.jsonObject
            ?.get("display_name")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Prefer `organization_name`, fall back to `display_name` for dual-profile acceptance.
     */
    fun getOrganizationOrDisplayName(metadata: JsonObject): String? {
        return getOrganizationName(metadata)?.takeIf { it.isNotBlank() }
            ?: getDisplayName(metadata)?.takeIf { it.isNotBlank() }
    }

    /**
     * Check if federation metadata should take precedence over well-known OID4VCI metadata.
     *
     * Per DIIP/federation spec: if `openid_credential_issuer` is in the entity configuration,
     * wallets MUST use only this metadata and ignore regular issuer metadata.
     */
    fun hasFederationIssuerMetadata(metadata: JsonObject): Boolean {
        return metadata.containsKey(WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
    }

    /**
     * Extract metadata for a specific entity type from the entity configuration metadata.
     */
    fun getEntityTypeMetadata(metadata: JsonObject, entityType: String): JsonObject? {
        return metadata[entityType]?.jsonObject
    }
}
