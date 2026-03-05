package com.sphereon.openid.fed.common.builder

import com.sphereon.openid.fed.openapi.models.BaseStatementJwks
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.TrustMark
import kotlinx.serialization.json.JsonObject

class EntityConfigurationStatementObjectBuilder {
    private var iss: String? = null
    private var exp: Double? = null
    private var iat: Double? = null
    private lateinit var jwks: List<Jwk>
    private var metadata: MutableMap<String, JsonObject> = mutableMapOf()
    private var metadataPolicy: MutableMap<String, JsonObject> = mutableMapOf()
    private val authorityHints: MutableList<String> = mutableListOf()
    private val trustAnchorHints: MutableList<String> = mutableListOf()
    private val trustMarkIssuers: MutableMap<String, List<String>> = mutableMapOf()
    private val crit: MutableList<String> = mutableListOf()
    private val trustMarks: MutableList<TrustMark> = mutableListOf()

    fun iss(iss: String) = apply { this.iss = iss }
    fun exp(exp: Double) = apply { this.exp = exp }
    fun iat(iat: Double) = apply { this.iat = iat }
    fun jwks(jwks: List<Jwk>) = apply { this.jwks = jwks }

    fun metadata(metadata: Pair<String, JsonObject>) = apply {
        this.metadata[metadata.first] = metadata.second
    }

    fun metadataPolicy(metadataPolicy: Pair<String, JsonObject>) = apply {
        this.metadataPolicy[metadataPolicy.first] = metadataPolicy.second
    }

    fun authorityHint(hint: String) = apply {
        this.authorityHints.add(hint)
    }

    fun trustAnchorHint(hint: String) = apply {
        this.trustAnchorHints.add(hint)
    }

    fun crit(claim: String) = apply {
        this.crit.add(claim)
    }

    fun trustMarkIssuer(trustMark: String, issuers: List<String>) = apply {
        this.trustMarkIssuers[trustMark] = issuers
    }

    fun trustMark(trustMark: TrustMark) = apply {
        this.trustMarks.add(trustMark)
    }

    private fun createJwks(jwks: List<Jwk>): BaseStatementJwks {
        return BaseStatementJwks(jwks)
    }

    fun build(): EntityConfigurationStatement {
        return EntityConfigurationStatement(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = iss!!,
            exp = exp ?: throw IllegalArgumentException("exp must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            jwks = createJwks(jwks),
            metadata = JsonObject(metadata),
            metadataPolicy = if (metadataPolicy.isNotEmpty()) JsonObject(metadataPolicy) else null,
            authorityHints = if (authorityHints.isNotEmpty()) authorityHints else null,
            trustAnchorHints = if (trustAnchorHints.isNotEmpty()) trustAnchorHints else null,
            crit = if (crit.isNotEmpty()) crit else null,
            trustMarkIssuers = this.trustMarkIssuers.map { (k, v) -> k to v }.toMap(),
            trustMarks = trustMarks
        )
    }
}
