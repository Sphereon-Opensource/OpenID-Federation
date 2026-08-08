package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Credential Verifier federation policy helpers (Wallet Architecture draft §6.3 / §7.1.1).
 *
 * - Prefer federated `openid_credential_verifier.jwks` over Authorization Request `client_metadata.jwks`
 * - Enforce federation-managed `dcql_queries` against request `dcql_query` when present
 *   (equality **or** constrained refinement — see [DcqlRefinement])
 */
object CredentialVerifierPolicy {

    const val ENTITY_TYPE = "openid_credential_verifier"

    data class JwksResolution(
        /** Effective JWKS object (`{"keys":[...]}`) or null if none available. */
        val jwks: JsonObject?,
        /** Whether the value came from federation metadata (true) or client_metadata (false). */
        val fromFederation: Boolean,
        val detail: String? = null
    )

    data class DcqlValidation(
        val valid: Boolean,
        val reason: String? = null,
        /** How the request matched a registered query, when [valid] is true and constraints applied. */
        val matchKind: DcqlRefinement.MatchKind? = null,
        val registeredIndex: Int? = null,
    )

    /**
     * Resolve JWKS for a Credential Verifier.
     *
     * When federated metadata contains `jwks`, it MUST be used and request `client_metadata.jwks`
     * MUST be ignored (wallet profile §6.3.1).
     */
    fun resolveJwks(
        federatedVerifierMetadata: JsonObject?,
        clientMetadata: JsonObject? = null
    ): JwksResolution {
        val fedJwks = federatedVerifierMetadata?.get("jwks")?.asJwksObject()
        if (fedJwks != null && fedJwks.hasKeys()) {
            return JwksResolution(
                jwks = fedJwks,
                fromFederation = true,
                detail = "Using openid_credential_verifier.jwks from federation metadata"
            )
        }

        val clientJwks = clientMetadata?.get("jwks")?.asJwksObject()
            ?: clientMetadata // client_metadata itself may be a jwks in some profiles
        if (clientJwks != null && clientJwks.hasKeys()) {
            return JwksResolution(
                jwks = clientJwks,
                fromFederation = false,
                detail = "No federated jwks; falling back to client_metadata.jwks"
            )
        }

        return JwksResolution(jwks = null, fromFederation = false, detail = "No jwks available")
    }

    /**
     * Extract `openid_credential_verifier` object from full entity metadata, if present.
     */
    fun verifierMetadata(entityMetadata: JsonObject): JsonObject? =
        entityMetadata[ENTITY_TYPE]?.jsonObject

    /**
     * Validate a request DCQL query against federation-managed `dcql_queries`.
     *
     * - If federation does not publish `dcql_queries`, any request query is accepted (no constraint).
     * - If federation publishes a non-empty list, the request query MUST be **equal to** or a
     *   **constrained refinement of** one registered query ([DcqlRefinement]).
     * - Missing request query is invalid when constraints exist.
     */
    fun validateDcqlQuery(
        requestDcqlQuery: JsonElement?,
        federatedVerifierMetadata: JsonObject?
    ): DcqlValidation {
        val registered = federatedVerifierMetadata?.get("dcql_queries") as? JsonArray
        if (registered == null || registered.isEmpty()) {
            return DcqlValidation(valid = true, reason = "No federation dcql_queries constraint")
        }

        if (requestDcqlQuery == null) {
            return DcqlValidation(
                valid = false,
                reason = "Federation requires dcql_query to match (or refine) a registered dcql_queries entry"
            )
        }

        val match = DcqlRefinement.match(requestDcqlQuery, registered)
        return if (match.matches) {
            DcqlValidation(
                valid = true,
                reason = match.detail,
                matchKind = match.kind,
                registeredIndex = match.registeredIndex,
            )
        } else {
            DcqlValidation(
                valid = false,
                reason = match.detail
                    ?: "Request dcql_query is not equal to or a refinement of any federation-registered dcql_queries entry",
                matchKind = DcqlRefinement.MatchKind.NONE,
            )
        }
    }

    private fun JsonElement.asJwksObject(): JsonObject? {
        val obj = this as? JsonObject ?: return null
        return if (obj.containsKey("keys")) obj else null
    }

    private fun JsonObject.hasKeys(): Boolean {
        val keys = this["keys"] as? JsonArray ?: return false
        return keys.isNotEmpty()
    }
}
