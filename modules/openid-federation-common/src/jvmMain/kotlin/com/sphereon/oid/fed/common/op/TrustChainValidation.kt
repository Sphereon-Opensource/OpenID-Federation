package com.sphereon.oid.fed.common.op

import com.sphereon.oid.fed.common.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.jwt.verify
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

suspend fun readAuthorityHints(jwt: String, engine: HttpClientEngine) = coroutineScope {
    val entityStatementList = mutableListOf<EntityStatement?>()
    val entityStatement = JsonMapper().mapEntityStatement(jwt)
    launch {
        entityStatement?.authorityHints?.forEach {
            val intermediateJwt = requestEntityConfiguration(it, engine)
            val intermediateES = JsonMapper().mapEntityStatement(intermediateJwt)
            entityStatementList.add(intermediateES)
            //readAuthorityHints(intermediateJwt, engine)
        }
    }
    return@coroutineScope entityStatementList
}

    //TODO fetch entity configuration
    //TODO iterate through intermediates listed in authority_hints, ignore unknown trust anchor, repeat if intermediate

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
            if(element.exp?.compareTo(offsetTime.toEpochSecond().toInt())!! < 0) {
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

    private suspend fun requestEntityConfiguration(url: String, engine: HttpClientEngine) = coroutineScope {
        var result: String? = null
        launch {
            result = OidFederationClient(engine).fetchEntityStatement(url)
        }
        return@coroutineScope result as String
    }

