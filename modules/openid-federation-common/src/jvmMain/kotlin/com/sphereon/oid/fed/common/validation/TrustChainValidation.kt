package com.sphereon.oid.fed.common.validation

import com.sphereon.oid.fed.common.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.kms.local.jwt.verify
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        JsonMapper().mapEntityConfigurationStatement(it)
    } ?: partyBId?.let {
        JsonMapper().mapEntityConfigurationStatement(requestEntityStatement(it, engine))
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
        JsonMapper().mapEntityConfigurationStatement(this)?.let {
            if (it.authorityHints.isNullOrEmpty()) {
                it.metadata?.get("federation_entity")?.jsonObject?.get("federation_fetch_endpoint")?.jsonPrimitive?.content.let { url ->
                    requestEntityStatement(url.toString(), engine).let { jwt -> subordinateStatement.add(jwt) }
                }
                trustChain.add(it)
                trustChains.add(trustChain.map { content -> content.copy() })
                subordinateStatements.add(subordinateStatement.map { content -> content.substring(0)})
                it.authorityHints ?: trustChain.clear()
                it.authorityHints ?: subordinateStatement.clear()
            } else {
                it.authorityHints?.forEach { hint ->
                    it.metadata?.get("federation_entity")?.jsonObject?.get("federation_fetch_endpoint")?.jsonPrimitive?.content.let { url ->
                        requestEntityStatement(url.toString(), engine).let { jwt -> subordinateStatement.add(jwt) }
                    }
                    trustChain.add(it)
                    buildTrustChain(hint, engine, subordinateStatements, trustChains, trustChain, subordinateStatement)
                }
            }
        }
    }
}

// TODO must validate subordinate statements too
fun validateTrustChain(jwts: List<String>): Boolean {
    val entityStatements = jwts.map { JsonMapper().mapEntityStatement(it) }

    val firstEntityConfigurationStatement = entityStatements[0] as EntityConfigurationStatement
    val subordinateStatements = entityStatements.map { it as SubordinateStatement }.subList(1, entityStatements.size - 1)
    val lastEntityConfigurationStatement = entityStatements[entityStatements.size - 1] as EntityConfigurationStatement

    if(firstEntityConfigurationStatement.iss != firstEntityConfigurationStatement.sub) {
        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
    }

    if (!verify(jwts[0], retrieveJwk(firstEntityConfigurationStatement))) {
        throw IllegalArgumentException("Invalid signature")
    }

    subordinateStatements.forEachIndexed { index, current ->
        val next = entityStatements[index + 1] as SubordinateStatement
        if(current.iss != next.sub) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }
        val offsetTime = OffsetDateTime.now()
        if(current.iat > offsetTime.toEpochSecond().toInt()) {
            throw IllegalArgumentException("Invalid iat")
        }
        if(current.exp < offsetTime.toEpochSecond().toInt()) {
            throw IllegalArgumentException("Invalid exp")
        }

        if(!verify(jwts[index], retrieveJwk(next))) {
            throw IllegalArgumentException("Invalid signature")
        }
    }
    if(lastEntityConfigurationStatement.iss != "entity_identifier") {
        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
    }
    if (!verify(jwts[jwts.size - 1], retrieveJwk(lastEntityConfigurationStatement))) {
        throw IllegalArgumentException("Invalid signature")
    }
    return true
}

fun retrieveJwk(entityStatement: Any?): Jwk {
    return when(entityStatement) {
        is EntityConfigurationStatement -> entityStatement.jwks.let { key ->
            Jwk(
                kid = key.jsonObject["kid"]?.jsonPrimitive?.content,
                kty = key.jsonObject["kty"]?.jsonPrimitive?.content ?: "",
                crv = key.jsonObject["crv"]?.jsonPrimitive?.content,
                x = key.jsonObject["x"]?.jsonPrimitive?.content
            )
        }
        is SubordinateStatement -> entityStatement.jwks.let { key ->
            Jwk(
                kid = key.jsonObject["kid"]?.jsonPrimitive?.content,
                kty = key.jsonObject["kty"]?.jsonPrimitive?.content ?: "",
                crv = key.jsonObject["crv"]?.jsonPrimitive?.content,
                x = key.jsonObject["x"]?.jsonPrimitive?.content
            )
        }
        else -> throw IllegalArgumentException("Invalid entity statement")
    }
}

fun requestEntityStatement(url: String, engine: HttpClientEngine) = runBlocking {
    return@runBlocking OidFederationClient(engine).fetchEntityStatement(url)
}
