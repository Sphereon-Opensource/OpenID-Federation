package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.BaseStatementJwks
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.TrustMark
import kotlinx.serialization.json.JsonObject

class EntityConfigurationStatementObjectBuilder {
    private var iss: String? = null
    private var exp: Int? = null
    private var iat: Int? = null
    private lateinit var jwks: List<Jwk>
    private var metadata: MutableMap<String, JsonObject> = mutableMapOf()
    private val authorityHints: MutableList<String> = mutableListOf()
    private val trustMarkIssuers: MutableMap<String, List<String>> = mutableMapOf()
    private val crit: MutableList<String> = mutableListOf()
    private val trustMarks: MutableList<TrustMark> = mutableListOf()

    fun iss(iss: String) = apply { this.iss = iss }
    fun exp(exp: Int) = apply { this.exp = exp }
    fun iat(iat: Int) = apply { this.iat = iat }
    fun jwks(jwks: List<Jwk>) = apply { this.jwks = jwks }

    fun metadata(metadata: Pair<String, JsonObject>) = apply {
        this.metadata[metadata.first] = metadata.second
    }

    fun authorityHint(hint: String) = apply {
        this.authorityHints.add(hint)
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
        return BaseStatementJwks(jwks.toTypedArray())
    }

    fun build(): EntityConfigurationStatement {
        return EntityConfigurationStatement(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = iss!!,
            exp = exp ?: throw IllegalArgumentException("exp must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            jwks = createJwks(jwks),
            metadata = JsonObject(metadata),
            authorityHints = if (authorityHints.isNotEmpty()) authorityHints.toTypedArray() else null,
            crit = if (crit.isNotEmpty()) crit.toTypedArray() else null,
            trustMarkIssuers = this.trustMarkIssuers.map { (k, v) -> k to v.toTypedArray() }.toMap(),
            trustMarks = trustMarks.toTypedArray()
        )
    }
}
