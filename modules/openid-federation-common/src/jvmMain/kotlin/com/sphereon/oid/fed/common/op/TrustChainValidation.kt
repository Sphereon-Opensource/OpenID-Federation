package com.sphereon.oid.fed.common.op

import com.sphereon.oid.fed.common.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.jwt.verify
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime

fun readAuthorityHints(jwt: String, engine: HttpClientEngine): List<List<EntityConfigurationStatement>> {
    val trustChains = mutableListOf<List<EntityConfigurationStatement>>()
    val entityStatement = JsonMapper().mapEntityStatement(jwt)

    entityStatement?.authorityHints?.forEach { authorityHint ->
        buildTrustChain(authorityHint, engine, trustChains)
    }
    return trustChains
}

fun buildTrustChain(
    authorityHint: String,
    engine: HttpClientEngine,
    trustChains: MutableList<List<EntityConfigurationStatement>>,
    trustChain: MutableList<EntityConfigurationStatement> = mutableListOf()
)
{
    requestEntityConfiguration(authorityHint, engine).run {
        JsonMapper().mapEntityStatement(this)?.let {
            if (it.authorityHints.isNullOrEmpty()) {
                trustChain.add(it)
                trustChains.add(trustChain.map { content -> content.copy() })
                it.authorityHints ?: trustChain.clear()
            } else {
                it.authorityHints?.forEach { hint ->
                    trustChain.add(it)
                    buildTrustChain(hint, engine, trustChains, trustChain)
                }
            }
        }
    }
}

    fun validateEntityStatement(jwts: List<String>) {
        val entityStatements = jwts.map { JsonMapper().mapEntityStatement(it) }
        if(entityStatements[0]?.iss != entityStatements[0]?.sub) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }
        if (!verify(jwts[0], entityStatements[0]?.jwks?.let { it.propertyKeys?.first()} as Any , emptyMap())) {
            throw IllegalArgumentException("Invalid signature")
        }
        entityStatements.forEachIndexed { index, element ->
            if(element?.iss != entityStatements[index + 1]?.sub) {
                throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
            }
            val offsetTime = OffsetDateTime.now()
            if(element?.iat?.compareTo(offsetTime.toEpochSecond().toInt())!! > 0) {
                throw IllegalArgumentException("Invalid iat")
            }
            if(element.exp < offsetTime.toEpochSecond().toInt()) {
                throw IllegalArgumentException("Invalid exp")
            }

            if(!verify(jwts[index], entityStatements[index +1]?.jwks?.let { it.propertyKeys?.first()} as Any, emptyMap())) {
                throw IllegalArgumentException("Invalid signature")
            }
        }
        if(entityStatements[entityStatements.size -1]?.iss != "entity_identifier") {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }
        if (!verify(jwts[jwts.size - 1], entityStatements[entityStatements.size - 1]?.jwks?.let { it.propertyKeys?.first()} as Any , emptyMap())) {
            throw IllegalArgumentException("Invalid signature")
        }
    }

    private fun requestEntityConfiguration(url: String, engine: HttpClientEngine) = runBlocking {
        return@runBlocking OidFederationClient(engine).fetchEntityStatement(url)
    }
