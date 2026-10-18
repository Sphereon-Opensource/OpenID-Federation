package com.sphereon.openid.fed.services.command

import com.sphereon.core.api.http.util.validateHttpsUrlSyntax
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * The structural rules a verifier applies to a received Entity Statement (OpenID Federation 1.1 section 3), applied
 * to a statement this entity is about to sign. A statement the Library itself would reject is never signed.
 */
object StatementSigningValidation {

    /** The reason [payload] cannot be signed with [header], or null when it is a valid Entity Statement. */
    fun refusal(payload: JsonObject, header: JwtHeader): String? {
        val iss = (payload["iss"] as? JsonPrimitive)?.content
        val sub = (payload["sub"] as? JsonPrimitive)?.content
        // Position 0 is an Entity Configuration in a chain; a Subordinate Statement never is.
        val position = if (iss != null && iss == sub) 0 else 1
        val result = runCatching {
            EntityStatementValidation.validateStructure(
                statement = Jwt(header = header, payload = payload, signature = ""),
                currentTimeSeconds = getCurrentEpochTimeSeconds(),
                position = position,
            )
        }.getOrElse { return "Entity Statement has an invalid structure" }
        if (!result.ok) return result.reason ?: "Invalid Entity Statement"
        return producerRefusal(payload, isEntityConfiguration = position == 0)
    }

    /**
     * Producer-side MUST NOTs that a received-statement check cannot see or deliberately tolerates. Every path that
     * signs an Entity Statement applies them after the structure checks.
     */
    fun producerRefusal(payload: JsonObject, isEntityConfiguration: Boolean): String? {
        // Section 3.1.2: trust_anchor_hints MUST NOT be present in the Entity Configuration of a Trust Anchor with no
        // Superiors. An entity that names no Immediate Superior in authority_hints has none.
        if (isEntityConfiguration && "trust_anchor_hints" in payload && "authority_hints" !in payload) {
            return "trust_anchor_hints must not be published by an entity without authority_hints"
        }
        // Section 5.1.1: the value none MUST NOT be used in endpoint_auth_signing_alg_values_supported.
        val federationEntity = (payload["metadata"] as? JsonObject)?.get("federation_entity") as? JsonObject
        val algorithms = federationEntity?.get("endpoint_auth_signing_alg_values_supported") as? JsonArray
        if (algorithms != null && algorithms.any { (it as? JsonPrimitive)?.content == "none" }) {
            return "endpoint_auth_signing_alg_values_supported must not contain none"
        }
        if (federationEntity != null) {
            // Sections 8.1, 8.2 and 8.4: these endpoints MUST be published in the Entity Configuration only.
            if (!isEntityConfiguration) {
                ENTITY_CONFIGURATION_ONLY_ENDPOINTS.firstOrNull { it in federationEntity }?.let {
                    return "$it must be published in the Entity Configuration, not in a Subordinate Statement"
                }
            }
            // Section 5.1.1: each federation endpoint MUST use https and MUST NOT contain a fragment.
            for (name in FEDERATION_ENDPOINTS) {
                val value = federationEntity[name] ?: continue
                val url = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (url == null || '#' in url || validateHttpsUrlSyntax(url, allowQuery = true).isErr) {
                    return "$name must be an https URL without a fragment"
                }
            }
        }
        // Keys in an Entity Type's jwks are told apart by kid; a verifier's keys MUST each have a unique one
        // (Wallet Architectures section 6.3).
        val metadata = payload["metadata"] as? JsonObject
        for ((entityType, typeMetadata) in metadata.orEmpty()) {
            val keys = ((typeMetadata as? JsonObject)?.get("jwks") as? JsonObject)?.get("keys") as? JsonArray ?: continue
            val kids = keys.map { ((it as? JsonObject)?.get("kid") as? JsonPrimitive)?.takeIf { kid -> kid.isString }?.content }
            if (entityType == "openid_credential_verifier" && kids.any { it.isNullOrEmpty() }) {
                return "every key in openid_credential_verifier jwks must have a kid"
            }
            val present = kids.filterNotNull()
            if (present.distinct().size != present.size) return "$entityType jwks must not contain duplicate kid values"
        }
        return null
    }

    private val ENTITY_CONFIGURATION_ONLY_ENDPOINTS = listOf(
        "federation_fetch_endpoint",
        "federation_list_endpoint",
        "federation_trust_mark_status_endpoint",
    )

    private val FEDERATION_ENDPOINTS = listOf(
        "federation_fetch_endpoint",
        "federation_list_endpoint",
        "federation_resolve_endpoint",
        "federation_trust_mark_status_endpoint",
        "federation_trust_mark_list_endpoint",
        "federation_trust_mark_endpoint",
        "federation_historical_keys_endpoint",
    )

    /** Serializes [statement] the way [com.sphereon.openid.fed.services.signPayload] does, then checks it. */
    inline fun <reified T> refusalFor(statement: T, header: JwtHeader): String? =
        refusal(Json.encodeToJsonElement(Json.serializersModule.serializer<T>(), statement).jsonObject, header)
}
