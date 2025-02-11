package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.BaseJwk
import com.sphereon.oid.fed.openapi.models.BaseStatementJwks
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.JsonObject

class SubordinateStatementObjectBuilder {
    private var iss: String? = null
    private var sub: String? = null
    private var exp: Int? = null
    private var iat: Int? = null
    private var jwks: MutableList<BaseJwk> = mutableListOf()
    private var metadata: MutableMap<String, JsonObject> = mutableMapOf()
    private var metadata_policy: MutableMap<String, JsonObject> = mutableMapOf()
    private var metadata_policy_crit: MutableMap<String, JsonObject> = mutableMapOf()
    private val crit: MutableList<String> = mutableListOf()
    private var source_endpoint: String? = null

    fun iss(iss: String) = apply { this.iss = iss }
    fun sub(sub: String) = apply { this.sub = sub }
    fun exp(exp: Int) = apply { this.exp = exp }
    fun iat(iat: Int) = apply { this.iat = iat }

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

    fun jwks(jwk: BaseJwk) = apply {
        this.jwks.add(jwk)
    }

    fun sourceEndpoint(sourceEndpoint: String) = apply {
        this.source_endpoint = sourceEndpoint
    }

    fun build(): SubordinateStatement {
        return SubordinateStatement(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = sub ?: throw IllegalArgumentException("sub must be provided"),
            exp = exp ?: throw IllegalArgumentException("exp must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            jwks = BaseStatementJwks(
                propertyKeys = jwks.toTypedArray()
            ),
            crit = if (crit.isNotEmpty()) crit.toTypedArray() else null,
            metadata = JsonObject(metadata),
            metadataPolicy = JsonObject(metadata_policy),
            metadataPolicyCrit = JsonObject(metadata_policy_crit),
            sourceEndpoint = source_endpoint,
        )
    }
}
