package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.httpclient.IHttpClientServiceJS
import com.sphereon.oid.fed.common.jwt.IJwtServiceJS
import com.sphereon.oid.fed.common.jwt.JwtVerifyInput
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise

class MockTrustChainValidationService(
    private val httpService: IHttpClientServiceJS,
    private val jwtService: IJwtServiceJS
): ITrustChainValidationCallbackJS {

    override fun readAuthorityHints(partyBId: String): Promise<Array<Array<dynamic>>> {
        return readAuthorityHintsImpl(partyBId)
    }

    private fun readAuthorityHintsImpl(
        partyBId: String,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableSet<EntityConfigurationStatement> = mutableSetOf()
    ): Promise<Array<Array<EntityConfigurationStatement>>> {
        httpService.fetchEntityStatement(partyBId, HttpMethod.Get, Parameters.Empty).then { result ->
            JsonMapper().mapEntityConfigurationStatement(result).let {
                if (it.authorityHints.isNullOrEmpty()) {
                    trustChain.add(it)
                    trustChains.add(trustChain.map { content -> content.copy() })
                    trustChain.last().also { trustChain.remove(it) }
                } else {
                    it.authorityHints?.forEach { hint ->
                        trustChain.add(it)
                        readAuthorityHintsImpl(
                            hint,
                            trustChains,
                            trustChain
                        )
                    }
                }
            }
        }
        return Promise.resolve(trustChains.map { it.toTypedArray() }.toTypedArray())
    }

    override fun fetchSubordinateStatements(entityConfigurationStatementsList: Array<Array<EntityConfigurationStatement?>>): Promise<Array<Array<String>>> {
        val trustChains: MutableList<List<String>> = mutableListOf()
        val trustChain: MutableList<String> = mutableListOf()
        entityConfigurationStatementsList.forEach { entityConfigurationStatements ->
            entityConfigurationStatements.forEach { it ->
                it?.metadata?.jsonObject?.get("federation_entity")?.jsonObject?.get("federation_fetch_endpoint")?.jsonPrimitive?.content.let { url ->
                    httpService.fetchEntityStatement(url.toString(), HttpMethod.Get, Parameters.Empty).then {
                        trustChain.add(it)
                    }
                }
            }
            trustChains.add(trustChain.map { content -> content.substring(0) })
            trustChain.clear()
        }
        return Promise.resolve(trustChains.map { it.toTypedArray() }.toTypedArray())
    }

    override fun validateTrustChains(
        jwts: Array<Array<String>>,
        knownTrustChainIds: Array<String>
    ): Promise<Array<Array<dynamic>>> {
        val trustChains: MutableList<Array<dynamic>> = mutableListOf()
        for (it in jwts) {
            try {
                validateTrustChain(it.toList(), knownTrustChainIds.toList()).then {
                    trustChains.add(it)
                }
            } catch (e: Exception) {
                TrustChainValidationConst.LOG.error("Trust Chain Validation Error: ${e.message.toString()}")
            }
        }
        return Promise.resolve(trustChains.toTypedArray())
    }

    private fun validateTrustChain(jwts: List<String>, knownTrustChainIds: List<String>): Promise<Array<Any>> =
        CoroutineScope(context = CoroutineName("TEST")).promise {

        val entityStatements = jwts.toMutableList()
        val firstEntityConfiguration =
            entityStatements.removeFirst().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val lastEntityConfiguration =
            entityStatements.removeLast().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val subordinateStatements = entityStatements.map { JsonMapper().mapSubordinateStatement(it) }

        if (firstEntityConfiguration.iss != firstEntityConfiguration.sub) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }

        if (firstEntityConfiguration.jwks.jsonObject["keys"]?.jsonArray?.all {
                jwtService.verify(
                    input = JwtVerifyInput(
                        jwt = jwts[0],
                        key = retrieveJwk(it)
                    )
                ).await()
            } == false) {
            throw IllegalArgumentException("Invalid signature")
        }

        subordinateStatements.forEachIndexed { index, current ->
            val next =
                if (index < subordinateStatements.size - 1) subordinateStatements[index + 1] else lastEntityConfiguration
            val now = Clock.System.now().epochSeconds.toInt()

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
                    } else if (next.jwks.jsonObject["keys"]?.jsonArray?.any {
                            jwtService.verify(
                                input = JwtVerifyInput(
                                    jwt = jwts[index],
                                    key = retrieveJwk(it)
                                )
                            ).await()
                        } == false) {
                        throw IllegalArgumentException("Invalid signature")
                    }

                is SubordinateStatement ->
                    if (current.iss != next.sub) {
                        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
                    } else if (next.jwks.jsonObject["keys"]?.jsonArray?.any {
                            jwtService.verify(
                                input = JwtVerifyInput(
                                    jwt = jwts[index],
                                    key = retrieveJwk(it)
                                )
                            ).await()
                        } == false) {
                        throw IllegalArgumentException("Invalid signature")
                    }
            }
        }

        if (!knownTrustChainIds.contains(lastEntityConfiguration.iss)) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to the Entity Identifier of the Trust Anchor")
        }
        if (lastEntityConfiguration.jwks.jsonObject["keys"]?.jsonArray?.any {
                jwtService.verify(
                    input = JwtVerifyInput(
                        jwt = jwts[jwts.size - 1],
                        key = retrieveJwk(it)
                    )
                ).await()
            } == false) {
            throw IllegalArgumentException("Invalid signature")
        }

        val validTrustChain = mutableListOf<Any>()
        validTrustChain.add(firstEntityConfiguration)
        validTrustChain.addAll(subordinateStatements)
        validTrustChain.add(lastEntityConfiguration)

        validTrustChain.toTypedArray()
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
}
