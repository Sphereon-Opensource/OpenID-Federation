package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.common.jwt.verify
import com.sphereon.oid.fed.common.logging.Logger
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise

@ExperimentalJsExport
@JsExport
actual class TrustChainValidation {

    private val NAME = "TrustChainValidation"

    fun readAuthorityHints(
        partyBId: String,
        engine: HttpClientEngine,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableSet<EntityConfigurationStatement> = mutableSetOf()
    ): Promise<List<List<EntityConfigurationStatement>>> = CoroutineScope(context = CoroutineName(NAME)).promise {
        TrustChainValidationCommon()
            .readAuthorityHints(
                partyBId = partyBId,
                engine = engine,
                trustChains = trustChains,
                trustChain = trustChain
            )
    }

    fun fetchSubordinateStatements(
        entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>,
        engine: HttpClientEngine
    ): Promise<List<List<String>>> = CoroutineScope(context = CoroutineName(NAME)).promise {
        TrustChainValidationCommon()
            .fetchSubordinateStatements(
                entityConfigurationStatementsList = entityConfigurationStatementsList,
                engine = engine
            )
    }

    actual fun validateTrustChains(
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

    @OptIn(ExperimentalJsExport::class)
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
            val now = (Clock.System.now().toEpochMilliseconds() / 1000).toInt()

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
}
