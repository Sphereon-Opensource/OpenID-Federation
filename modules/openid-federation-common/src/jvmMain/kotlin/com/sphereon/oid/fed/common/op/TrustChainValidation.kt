package com.sphereon.oid.fed.common.op

import com.sphereon.oid.fed.common.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.jwt.verify
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime

fun readAuthorityHints(jwt: String? = null, partyBId: String? = null, engine: HttpClientEngine): List<List<String>> {
    val trustChains = mutableListOf<List<EntityConfigurationStatement>>()
    val subordinateStatements = mutableListOf<List<String>>()
    val subordinateStatement = mutableListOf<String>()

    if (jwt != null && partyBId != null) {
        throw IllegalArgumentException("Only one of jwt or partyBId should be provided")
    }

    if (jwt == null && partyBId == null) {
        throw IllegalArgumentException("Either jwt or partyBId should be provided")
    }

    val entityConfigurationStatement = jwt?.let {
        JsonMapper().mapEntityStatement(it)
    } ?: partyBId?.let {
        JsonMapper().mapEntityStatement(requestEntityStatement(it, engine))
    }

    entityConfigurationStatement?.metadata?.federationEntity?.federationFetchEndpoint?.let { url ->
        requestEntityStatement(url, engine).let { jwt -> subordinateStatement.add(jwt) }
    }

    entityConfigurationStatement?.authorityHints?.forEach { authorityHint ->
        buildTrustChain(authorityHint, engine, subordinateStatements, trustChains, subordinateStatement = subordinateStatement)
    }
    return subordinateStatements
}

fun buildTrustChain(
    authorityHint: String,
    engine: HttpClientEngine,
    subordinateStatements: MutableList<List<String>>,
    trustChains: MutableList<List<EntityConfigurationStatement>>,
    trustChain: MutableList<EntityConfigurationStatement> = mutableListOf(),
    subordinateStatement: MutableList<String> = mutableListOf()
)
{
    requestEntityStatement(authorityHint, engine).run {
        JsonMapper().mapEntityStatement(this)?.let {
            if (it.authorityHints.isNullOrEmpty()) {
                it.metadata?.federationEntity?.federationFetchEndpoint?.let { url ->
                    requestEntityStatement(url, engine).let { jwt -> subordinateStatement.add(jwt) }
                }
                trustChain.add(it)
                trustChains.add(trustChain.map { content -> content.copy() })
                subordinateStatements.add(subordinateStatement.map { content -> content.substring(0)})
                it.authorityHints ?: trustChain.clear()
                it.authorityHints ?: subordinateStatement.clear()
            } else {
                it.authorityHints?.forEach { hint ->
                    it.metadata?.federationEntity?.federationFetchEndpoint?.let { url ->
                        requestEntityStatement(url, engine).let { jwt -> subordinateStatement.add(jwt) }
                    }
                    trustChain.add(it)
                    buildTrustChain(hint, engine, subordinateStatements, trustChains, trustChain, subordinateStatement)
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

fun requestEntityStatement(url: String, engine: HttpClientEngine) = runBlocking {
    return@runBlocking OidFederationClient(engine).fetchEntityStatement(url)
}
