package com.sphereon.oid.fed.common.validation

import com.sphereon.oid.fed.common.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking

class TrustChainValidation {

    fun readAuthorityHints(
        partyBId: String,
        engine: HttpClientEngine,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableList<EntityConfigurationStatement> = mutableListOf()
    ): List<List<EntityConfigurationStatement>>{
        requestEntityStatement(partyBId, engine).run {
            JsonMapper().mapEntityConfigurationStatement(this)?.let {
                if (it.authorityHints.isNullOrEmpty()) {
                    trustChain.add(it)
                    trustChains.add(trustChain.map { content -> content.copy() })
                    it.authorityHints ?: trustChain.clear()
                } else {
                    it.authorityHints?.forEach { hint ->
                        trustChain.add(it)
                        readAuthorityHints(
                            hint,
                            engine,
                            trustChains,
                            trustChain
                        )
                    }
                }
            }
        }
        return trustChains
    }

    //
//// TODO must validate subordinate statements too
//fun validateTrustChain(jwts: List<String>): Boolean {
//    val entityStatements = jwts.map { JsonMapper().mapEntityStatement(it) }
//
//    val firstEntityConfigurationStatement = entityStatements[0] as EntityConfigurationStatement
//    val subordinateStatements = entityStatements.map { it as SubordinateStatement }.subList(1, entityStatements.size - 1)
//    val lastEntityConfigurationStatement = entityStatements[entityStatements.size - 1] as EntityConfigurationStatement
//
//    if(firstEntityConfigurationStatement.iss != firstEntityConfigurationStatement.sub) {
//        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
//    }
//
//    if (!verify(jwts[0], retrieveJwk(firstEntityConfigurationStatement))) {
//        throw IllegalArgumentException("Invalid signature")
//    }
//
//    subordinateStatements.forEachIndexed { index, current ->
//        val next = entityStatements[index + 1] as SubordinateStatement
//        if(current.iss != next.sub) {
//            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
//        }
//        val offsetTime = OffsetDateTime.now()
//        if(current.iat > offsetTime.toEpochSecond().toInt()) {
//            throw IllegalArgumentException("Invalid iat")
//        }
//        if(current.exp < offsetTime.toEpochSecond().toInt()) {
//            throw IllegalArgumentException("Invalid exp")
//        }
//
//        if(!verify(jwts[index], retrieveJwk(next))) {
//            throw IllegalArgumentException("Invalid signature")
//        }
//    }
//    if(lastEntityConfigurationStatement.iss != "entity_identifier") {
//        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
//    }
//    if (!verify(jwts[jwts.size - 1], retrieveJwk(lastEntityConfigurationStatement))) {
//        throw IllegalArgumentException("Invalid signature")
//    }
//    return true
//}
//
//fun retrieveJwk(entityStatement: Any?): Jwk {
//    return when(entityStatement) {
//        is EntityConfigurationStatement -> entityStatement.jwks.let { key ->
//            Jwk(
//                kid = key.jsonObject["kid"]?.jsonPrimitive?.content,
//                kty = key.jsonObject["kty"]?.jsonPrimitive?.content ?: "",
//                crv = key.jsonObject["crv"]?.jsonPrimitive?.content,
//                x = key.jsonObject["x"]?.jsonPrimitive?.content
//            )
//        }
//        is SubordinateStatement -> entityStatement.jwks.let { key ->
//            Jwk(
//                kid = key.jsonObject["kid"]?.jsonPrimitive?.content,
//                kty = key.jsonObject["kty"]?.jsonPrimitive?.content ?: "",
//                crv = key.jsonObject["crv"]?.jsonPrimitive?.content,
//                x = key.jsonObject["x"]?.jsonPrimitive?.content
//            )
//        }
//        else -> throw IllegalArgumentException("Invalid entity statement")
//    }
//}
//
    private fun requestEntityStatement(url: String, engine: HttpClientEngine) = runBlocking {
        return@runBlocking OidFederationClient(engine).fetchEntityStatement(url)
    }
}
