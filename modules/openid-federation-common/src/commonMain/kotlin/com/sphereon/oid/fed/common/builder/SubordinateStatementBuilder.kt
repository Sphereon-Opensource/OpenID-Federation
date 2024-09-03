package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.JwkDTO
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class SubordinateStatementBuilder {
    private var iss: String? = null
    private var sub: String? = null
    private var exp: Int? = null
    private var iat: Int? = null
    private lateinit var jwks: Array<JwkDTO>
    private var metadata: MutableMap<String, JsonObject> = mutableMapOf()
    private var metadata_policy: MutableMap<String, JsonObject> = mutableMapOf()
    private var metadata_policy_crit: MutableMap<String, JsonObject> = mutableMapOf()
    private var constraints: MutableMap<String, JsonObject> = mutableMapOf()
    private val crit: MutableList<String> = mutableListOf()
    private var source_endpoint: String? = null

    fun iss(iss: String) = apply { this.iss = iss }
    fun sub(sub: String) = apply { this.sub = sub }
    fun exp(exp: Int) = apply { this.exp = exp }
    fun iat(iat: Int) = apply { this.iat = iat }
    fun jwks(jwks: JwkDTO) = apply { this.jwks = arrayOf(jwks) }

    fun metadata(metadata: Pair<String, JsonObject>) = apply {
        this.metadata[metadata.first] = metadata.second
    }

    fun metadataPolicy(metadataPolicy: Pair<String, JsonObject>) = apply {
        this.metadata_policy[metadataPolicy.first] = metadataPolicy.second
    }

    fun metadataPolicyCrit(metadataPolicyCrit: Pair<String, JsonObject>) = apply {
        this.metadata_policy_crit[metadataPolicyCrit.first] = metadataPolicyCrit.second
    }

    fun crit(claim: String) = apply {
        this.crit.add(claim)
    }

    fun sourceEndpoint(sourceEndpoint: String) = apply {
        this.source_endpoint = sourceEndpoint
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createJwks(jwks: Array<JwkDTO>): JsonObject {
        val jsonArray: JsonArray =
            Json.encodeToJsonElement(ArraySerializer(JwkDTO.serializer()), jwks) as JsonArray

        return buildJsonObject {
            put("keys", jsonArray)
        }
    }

    fun build(): SubordinateStatement {
        return SubordinateStatement(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = sub ?: throw IllegalArgumentException("sub must be provided"),
            exp = exp ?: throw IllegalArgumentException("exp must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            jwks = createJwks(jwks),
            crit = if (crit.isNotEmpty()) crit.toTypedArray() else null,
            metadata = JsonObject(metadata),
            metadataPolicy = JsonObject(metadata_policy),
            metadataPolicyCrit = JsonObject(metadata_policy_crit),
            constraints = JsonObject(constraints),
            sourceEndpoint = source_endpoint,
        )
    }
}
