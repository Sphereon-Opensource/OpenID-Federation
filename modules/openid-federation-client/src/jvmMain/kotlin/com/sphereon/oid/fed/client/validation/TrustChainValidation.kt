package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.kms.local.jwt.verify
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

class TrustChainValidation {

    fun readAuthorityHints(
        partyBId: String,
        engine: HttpClientEngine,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableSet<EntityConfigurationStatement> = mutableSetOf()
    ): List<List<EntityConfigurationStatement>> {
        requestEntityStatement(partyBId, engine).run {
            JsonMapper().mapEntityConfigurationStatement(this).let {
                if (it.authorityHints.isNullOrEmpty()) {
                    trustChain.add(it)
                    trustChains.add(trustChain.map { content -> content.copy() })
                    trustChain.last().also { trustChain.remove(it) }
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

    fun fetchSubordinateStatements(
        entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>,
        engine: HttpClientEngine
    ): List<List<String>> {
        val trustChains: MutableList<List<String>> = mutableListOf()
        val trustChain: MutableList<String> = mutableListOf()
        entityConfigurationStatementsList.forEach { entityConfigurationStatements ->
            entityConfigurationStatements.forEach { it ->
                it.metadata?.jsonObject?.get("federation_entity")?.jsonObject?.get("federation_fetch_endpoint")?.jsonPrimitive?.content.let { url ->
                    requestEntityStatement(url.toString(), engine).run {
                        trustChain.add(this)
                    }
                }
            }
            trustChains.add(trustChain.map { content -> content.substring(0) })
            trustChain.clear()
        }
        return trustChains
    }

    fun validateTrustChain(jwts: List<String>): Boolean {
        val entityStatements = jwts.toMutableList()
        val firstEntityConfiguration =
            entityStatements.removeFirst().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val lastEntityConfiguration =
            entityStatements.removeLast().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val subordinateStatements = entityStatements.map { JsonMapper().mapSubordinateStatement(it) }

        if (firstEntityConfiguration.iss != firstEntityConfiguration.sub) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }

        if (!verify(jwts[0], retrieveJwk(firstEntityConfiguration))) {
            throw IllegalArgumentException("Invalid signature")
        }

        subordinateStatements.forEachIndexed { index, current ->
            val next =
                if (index < subordinateStatements.size - 1) subordinateStatements[index + 1] else lastEntityConfiguration
            when (next) {
                is EntityConfigurationStatement ->
                    if (current.iss != next.sub) {
                        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
                    }

                is SubordinateStatement ->
                    if (current.iss != next.sub) {
                        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
                    }
            }

            val offsetTime = OffsetDateTime.now()
            if (current.iat > offsetTime.toEpochSecond().toInt()) {
                throw IllegalArgumentException("Invalid iat")
            }
            if (current.exp < offsetTime.toEpochSecond().toInt()) {
                throw IllegalArgumentException("Invalid exp")
            }

            if (!verify(jwts[index], retrieveJwk(next))) {
                throw IllegalArgumentException("Invalid signature")
            }
        }

        if (lastEntityConfiguration.iss != firstEntityConfiguration.iss) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to the Entity Identifier of the Trust Anchor")
        }
        if (!verify(jwts[jwts.size - 1], retrieveJwk(lastEntityConfiguration))) {
            throw IllegalArgumentException("Invalid signature")
        }
        return true
    }

    fun retrieveJwk(entityStatement: Any?): Jwk {
        return when (entityStatement) {
            is EntityConfigurationStatement -> entityStatement.jwks.let { key ->
                Jwk(
                    kid = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["kid"]?.jsonPrimitive?.content,
                    kty = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["kty"]?.jsonPrimitive?.content ?: "EC",
                    crv = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["crv"]?.jsonPrimitive?.content,
                    x = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["x"]?.jsonPrimitive?.content,
                    y = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["y"]?.jsonPrimitive?.content
                )
            }

            is SubordinateStatement -> entityStatement.jwks.let { key ->
                Jwk(
                    kid = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["kid"]?.jsonPrimitive?.content,
                    kty = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["kty"]?.jsonPrimitive?.content ?: "EC",
                    crv = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["crv"]?.jsonPrimitive?.content,
                    x = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["x"]?.jsonPrimitive?.content,
                    y = key.jsonObject["keys"]?.jsonArray[0]?.jsonObject["y"]?.jsonPrimitive?.content
                )
            }

            else -> throw IllegalArgumentException("Invalid entity statement")
        }
    }

    private fun requestEntityStatement(url: String, engine: HttpClientEngine) = runBlocking {
        return@runBlocking OidFederationClient(engine).fetchEntityStatement(url)
    }
}
