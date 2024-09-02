package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JwkDTO
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class EntityConfigurationStatementBuilder {
    private var iss: String? = null
    private var exp: Int? = null
    private var iat: Int? = null
    private lateinit var jwks: Array<JwkDTO>
    private var metadata: MutableMap<String, JsonObject> = mutableMapOf()
    private val authorityHints: MutableList<String> = mutableListOf()

    fun iss(iss: String) = apply { this.iss = iss }
    fun exp(exp: Int) = apply { this.exp = exp }
    fun iat(iat: Int) = apply { this.iat = iat }
    fun jwks(jwks: Array<JwkDTO>) = apply { this.jwks = jwks }

    fun metadata(metadata: Pair<String, JsonObject>) = apply {
        this.metadata[metadata.first] = metadata.second
    }

    fun authorityHint(hint: String) = apply {
        this.authorityHints.add(hint)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createJwks(jwks: Array<JwkDTO>): JsonObject {
        val jsonArray: JsonArray =
            Json.encodeToJsonElement(ArraySerializer(JwkDTO.serializer()), jwks) as JsonArray

        return buildJsonObject {
            put("keys", jsonArray)
        }
    }

    fun build(): EntityConfigurationStatement {
        return EntityConfigurationStatement(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = iss!!,
            exp = exp ?: throw IllegalArgumentException("exp must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            jwks = createJwks(jwks),
            metadata = JsonObject(metadata),
            authorityHints = if (authorityHints.isNotEmpty()) authorityHints.toTypedArray() else null
        )
    }
}
