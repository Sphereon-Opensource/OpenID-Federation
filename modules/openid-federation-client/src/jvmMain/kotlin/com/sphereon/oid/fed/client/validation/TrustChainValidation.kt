package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.logging.Logger
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.kms.local.jwt.verify
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

    fun validateTrustChains(
        jwts: List<List<String>>,
        knownTrustChainIds: List<String>
    ): List<List<Any>> {
        val trustChains: MutableList<List<Any>> = mutableListOf()
            for(it in jwts) {
                try {
                    trustChains.add(validateTrustChain(it, knownTrustChainIds))
                } catch (e: Exception) {
                    Logger.debug("TrustChainValidation", e.message.toString())
                }
            }
        return trustChains
    }

    private fun validateTrustChain(jwts: List<String>, knownTrustChainIds: List<String>): List<Any> {
        val entityStatements = jwts.toMutableList()
        val firstEntityConfiguration =
            entityStatements.removeFirst().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val lastEntityConfiguration =
            entityStatements.removeLast().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val subordinateStatements = entityStatements.map { JsonMapper().mapSubordinateStatement(it) }

        if (firstEntityConfiguration.iss != firstEntityConfiguration.sub) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }

        if (firstEntityConfiguration.jwks.jsonObject["keys"]?.jsonArray?.any { verify(jwts[0], retrieveJwk(it)) } == false) {
            throw IllegalArgumentException("Invalid signature")
        }

        subordinateStatements.forEachIndexed { index, current ->
            val next =
                if (index < subordinateStatements.size - 1) subordinateStatements[index + 1] else lastEntityConfiguration
            val now = OffsetDateTime.now().toEpochSecond().toInt()

            if (current.iat > now) {
                throw IllegalArgumentException("Invalid iat")
            }

            if (current.exp < now) {
                throw IllegalArgumentException("Invalid exp")
            }

            when (next) {
                is EntityConfigurationStatement ->
                    if (current.iss != next.sub) {
                        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
                    } else if (next.jwks.jsonObject["keys"]?.jsonArray?.any { verify(jwts[0], retrieveJwk(it)) } == false) {
                        throw IllegalArgumentException("Invalid signature")
                    }
                is SubordinateStatement ->
                    if (current.iss != next.sub) {
                        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
                    } else if (next.jwks.jsonObject["keys"]?.jsonArray?.any { verify(jwts[0], retrieveJwk(it)) } == false) {
                        throw IllegalArgumentException("Invalid signature")
                    }
            }
        }

        if (!knownTrustChainIds.contains(lastEntityConfiguration.iss)) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to the Entity Identifier of the Trust Anchor")
        }
        if (lastEntityConfiguration.jwks.jsonObject["keys"]?.jsonArray?.any { verify(jwts[jwts.size - 1], retrieveJwk(it)) } == false) {
            throw IllegalArgumentException("Invalid signature")
        }

        val validTrustChain = mutableListOf<Any>()
        validTrustChain.add(firstEntityConfiguration)
        validTrustChain.addAll(subordinateStatements)
        validTrustChain.add(lastEntityConfiguration)

        return validTrustChain
    }

    private fun retrieveJwk(key: JsonElement): Jwk {
        return when (key) {
            is JsonObject -> Jwk(
                kid = key["kid"]?.jsonPrimitive?.content,
                kty = key["kty"]?.jsonPrimitive?.content ?: "EC",
                crv = key["crv"]?.jsonPrimitive?.content,
                x = key["x"]?.jsonPrimitive?.content,
                y = key["y"]?.jsonPrimitive?.content
            )
            else -> throw IllegalArgumentException("Invalid key")
        }
    }

    private fun requestEntityStatement(url: String, engine: HttpClientEngine) = runBlocking {
        return@runBlocking OidFederationClient(engine).fetchEntityStatement(url)
    }
}
