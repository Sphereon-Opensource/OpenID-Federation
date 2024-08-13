package com.sphereon.oid.fed.common.op

import com.sphereon.oid.fed.common.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.jwt.verify
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime

fun readAuthorityHints(jwt: String, engine: HttpClientEngine): List<EntityConfigurationStatement> {
    val entityStatementList = mutableListOf<EntityConfigurationStatement>()
    val entityStatement = JsonMapper().mapEntityStatement(jwt)
    entityStatement?.authorityHints?.forEach {
       requestEntityConfiguration(it, engine).run {
           JsonMapper().mapEntityStatement(this)?.run {
               entityStatementList.add(this)
           }
           entityStatementList.addAll(readAuthorityHints(this, engine))
       }
    }
    return entityStatementList
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
