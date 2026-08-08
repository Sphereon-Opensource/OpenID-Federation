package com.sphereon.openid.fed.common.builder

import com.sphereon.openid.fed.openapi.models.BaseStatementJwks
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.JsonObject

class SubordinateStatementObjectBuilder {
    private var iss: String? = null
    private var sub: String? = null
    private var exp: Double? = null
    private var iat: Double? = null
    private var jwks: MutableList<Jwk> = mutableListOf()
    private var metadata: MutableMap<String, JsonObject> = mutableMapOf()
    private var metadata_policy: MutableMap<String, JsonObject> = mutableMapOf()
    /** Critical non-standard policy operator names (OIDFed 1.1 §3.1.3 — array of strings). */
    private val metadata_policy_crit: MutableList<String> = mutableListOf()
    private val crit: MutableList<String> = mutableListOf()
    private var source_endpoint: String? = null
    private var constraints: Constraints? = null

    fun iss(iss: String) = apply { this.iss = iss }
    fun sub(sub: String) = apply { this.sub = sub }
    fun exp(exp: Double) = apply { this.exp = exp }
    fun iat(iat: Double) = apply { this.iat = iat }

    fun metadata(metadata: Pair<String, JsonObject>) = apply {
        this.metadata[metadata.first] = metadata.second
    }

    fun metadataPolicy(metadataPolicy: Pair<String, JsonObject>) = apply {
        this.metadata_policy[metadataPolicy.first] = metadataPolicy.second
    }

    fun metadataPolicyCrit(operatorName: String) = apply {
        if (operatorName.isNotBlank() && operatorName !in metadata_policy_crit) {
            this.metadata_policy_crit.add(operatorName)
        }
    }

    fun metadataPolicyCrit(operatorNames: Collection<String>) = apply {
        operatorNames.forEach { metadataPolicyCrit(it) }
    }

    fun crit(claim: String) = apply {
        this.crit.add(claim)
    }

    fun jwks(jwk: Jwk) = apply {
        this.jwks.add(jwk)
    }

    fun sourceEndpoint(sourceEndpoint: String) = apply {
        this.source_endpoint = sourceEndpoint
    }

    fun constraints(constraints: Constraints) = apply {
        this.constraints = constraints
    }

    fun build(): SubordinateStatement {
        return SubordinateStatement(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = sub ?: throw IllegalArgumentException("sub must be provided"),
            exp = exp ?: throw IllegalArgumentException("exp must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            jwks = BaseStatementJwks(
                propertyKeys = jwks
            ),
            crit = if (crit.isNotEmpty()) crit else null,
            metadata = if (metadata.isNotEmpty()) JsonObject(metadata) else null,
            metadataPolicy = if (metadata_policy.isNotEmpty()) JsonObject(metadata_policy) else null,
            // Spec: MUST NOT be the empty array when present
            metadataPolicyCrit = if (metadata_policy_crit.isNotEmpty()) metadata_policy_crit.toList() else null,
            sourceEndpoint = source_endpoint,
            constraints = constraints,
        )
    }
}
