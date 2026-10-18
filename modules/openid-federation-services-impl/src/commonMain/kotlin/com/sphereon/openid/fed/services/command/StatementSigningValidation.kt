package com.sphereon.openid.fed.services.command

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

    /** Producer-side MUST NOTs that a received-statement check cannot see or deliberately tolerates. */
    private fun producerRefusal(payload: JsonObject, isEntityConfiguration: Boolean): String? {
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
        return null
    }

    /** Serializes [statement] the way [com.sphereon.openid.fed.services.signPayload] does, then checks it. */
    inline fun <reified T> refusalFor(statement: T, header: JwtHeader): String? =
        refusal(Json.encodeToJsonElement(Json.serializersModule.serializer<T>(), statement).jsonObject, header)
}
