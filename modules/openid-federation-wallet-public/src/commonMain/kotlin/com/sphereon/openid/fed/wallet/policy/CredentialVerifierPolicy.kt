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
        /** The request query after a federation `dcql_queries` policy was applied to it, when one applied. */
        val effectiveDcqlQueries: JsonArray? = null,
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

        if (federatedVerifierMetadata != null) {
            return JwksResolution(jwks = null, fromFederation = true,
                detail = "Federated verifier metadata is available; client_metadata keys must be ignored")
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
     * - If federation does not publish `dcql_queries` but [metadataPolicy] has a `dcql_queries` policy for
     *   `openid_credential_verifier`, that policy is applied to the request's `dcql_query` (Wallet Architectures
     *   section 7.1.1); a policy violation rejects the request.
     * - Otherwise, without published `dcql_queries`, any request query is accepted (no constraint).
     * - If federation publishes a non-empty list, the request query MUST be **equal to** or a
     *   **constrained refinement of** one registered query ([DcqlRefinement]).
     * - Missing request query is invalid when constraints exist.
     */
    fun validateDcqlQuery(
        requestDcqlQuery: JsonElement?,
        federatedVerifierMetadata: JsonObject?,
        metadataPolicy: JsonObject? = null,
    ): DcqlValidation {
        val registered = federatedVerifierMetadata?.get("dcql_queries") as? JsonArray
        if (federatedVerifierMetadata?.containsKey("dcql_queries") == true &&
            (registered == null || registered.isEmpty() || registered.any { it !is JsonObject })) {
            return DcqlValidation(valid = false, reason = "Federated dcql_queries must be a nonempty array of query objects")
        }
        if (registered == null) {
            return applyDcqlQueriesPolicy(requestDcqlQuery, metadataPolicy)
                ?: DcqlValidation(valid = true, reason = "No federation dcql_queries constraint")
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

    /**
     * Applies the `openid_credential_verifier.dcql_queries` operators of [metadataPolicy] to the request's query, or
     * returns null when the policy has none.
     */
    private fun applyDcqlQueriesPolicy(requestDcqlQuery: JsonElement?, metadataPolicy: JsonObject?): DcqlValidation? {
        val typePolicy = metadataPolicy?.get(ENTITY_TYPE) as? JsonObject ?: return null
        val queriesPolicy = typePolicy["dcql_queries"] ?: return null
        val candidate = JsonObject(if (requestDcqlQuery == null) emptyMap() else mapOf("dcql_queries" to JsonArray(listOf(requestDcqlQuery))))
        val applied = MetadataPolicyOperators.applyPolicy(
            metadata = JsonObject(mapOf(ENTITY_TYPE to candidate)),
            policy = JsonObject(mapOf(ENTITY_TYPE to JsonObject(mapOf("dcql_queries" to queriesPolicy)))),
            entityType = ENTITY_TYPE,
        )
        if (!applied.isValid) {
            return DcqlValidation(
                valid = false,
                reason = "Request dcql_query does not satisfy the federation dcql_queries policy: ${applied.errors.joinToString("; ")}",
            )
        }
        val effective = applied.metadata["dcql_queries"] as? JsonArray
        // subset_of and one_of remove what the federation does not permit, and value replaces it: the request is only
        // honoured when the policy leaves the request's own query in place.
        if (requestDcqlQuery != null && (effective == null || requestDcqlQuery !in effective)) {
            return DcqlValidation(
                valid = false,
                reason = "Request dcql_query is not permitted by the federation dcql_queries policy",
                effectiveDcqlQueries = effective,
            )
        }
        return DcqlValidation(
            valid = true,
            reason = "Request dcql_query satisfies the federation dcql_queries policy",
            effectiveDcqlQueries = effective,
        )
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
