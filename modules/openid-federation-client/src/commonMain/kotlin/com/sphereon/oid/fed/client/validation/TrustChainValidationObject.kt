package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.ICallbackService
import com.sphereon.oid.fed.client.OidFederationClientService
import com.sphereon.oid.fed.common.jwt.JwtService
import com.sphereon.oid.fed.common.jwt.JwtVerifyInput
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmStatic

interface ITrustChainValidationCallback {
    suspend fun readAuthorityHints(
        partyBId: String,
    ): List<List<EntityConfigurationStatement>>

    suspend fun fetchSubordinateStatements(
        entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>,
    ): List<List<String>>

    suspend fun validateTrustChains(
        jwts: List<List<String>>,
        knownTrustChainIds: List<String>
    ): List<List<Any>>
}

interface TrustChainValidationCallback : ICallbackService<ITrustChainValidationCallback>,
    ITrustChainValidationCallback

expect fun trustChainValidationService(): TrustChainValidationCallback

//FIXME Extract the actual implementation to a separate class
object TrustChainValidationObject : TrustChainValidationCallback {

    @JvmStatic
    private lateinit var jwtService: JwtService

    @JvmStatic
    private lateinit var httpService: OidFederationClientService

    @JvmStatic
    private lateinit var platformCallback: ITrustChainValidationCallback

    private var disabled = false

    override suspend fun readAuthorityHints(
        partyBId: String,
    ): List<List<EntityConfigurationStatement>> {
        if (!isEnabled()) {
            TrustChainValidationConst.LOG.info("TRUST_CHAIN_VALIDATION readAuthorityHints has been disabled")
            throw IllegalStateException("TRUST_CHAIN_VALIDATION service is disabled; cannot read authority hints")
        } else if (!this::platformCallback.isInitialized) {
            TrustChainValidationConst.LOG.error(
                "TRUST_CHAIN_VALIDATION callback (JS) is not registered"
            )
            throw IllegalStateException("TRUST_CHAIN_VALIDATION have not been initialized. Please register your TrustChainValidationCallbacksServiceJS implementation, or register a default implementation")
        }
        return readAuthorityHintsImpl(partyBId)
    }

    private suspend fun readAuthorityHintsImpl(
        partyBId: String,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableSet<EntityConfigurationStatement> = mutableSetOf()
    ): List<List<EntityConfigurationStatement>> {
        httpService.HTTP.fetchEntityStatement(partyBId).run {
            JsonMapper().mapEntityConfigurationStatement(this).let {
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
        return trustChains
    }

    override suspend fun fetchSubordinateStatements(
        entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>,
    ): List<List<String>> {
        if (!isEnabled()) {
            TrustChainValidationConst.LOG.info("TRUST_CHAIN_VALIDATION readAuthorityHints has been disabled")
            throw IllegalStateException("TRUST_CHAIN_VALIDATION service is disabled; cannot read authority hints")
        } else if (!this::platformCallback.isInitialized) {
            TrustChainValidationConst.LOG.error(
                "TRUST_CHAIN_VALIDATION callback (JS) is not registered"
            )
            throw IllegalStateException("TRUST_CHAIN_VALIDATION have not been initialized. Please register your TrustChainValidationCallbacksServiceJS implementation, or register a default implementation")
        }
        val trustChains: MutableList<List<String>> = mutableListOf()
        val trustChain: MutableList<String> = mutableListOf()
        entityConfigurationStatementsList.forEach { entityConfigurationStatements ->
            entityConfigurationStatements.forEach { it ->
                it.metadata?.jsonObject?.get("federation_entity")?.jsonObject?.get("federation_fetch_endpoint")?.jsonPrimitive?.content.let { url ->
                    httpService.HTTP.fetchEntityStatement(url.toString()).run {
                        trustChain.add(this)
                    }
                }
            }
            trustChains.add(trustChain.map { content -> content.substring(0) })
            trustChain.clear()
        }
        return trustChains
    }

    override suspend fun validateTrustChains(
        jwts: List<List<String>>,
        knownTrustChainIds: List<String>
    ): List<List<Any>> {
        if (!isEnabled()) {
            TrustChainValidationConst.LOG.info("TRUST_CHAIN_VALIDATION readAuthorityHints has been disabled")
            throw IllegalStateException("TRUST_CHAIN_VALIDATION service is disabled; cannot read authority hints")
        } else if (!this::platformCallback.isInitialized) {
            TrustChainValidationConst.LOG.error(
                "TRUST_CHAIN_VALIDATION callback (JS) is not registered"
            )
            throw IllegalStateException("TRUST_CHAIN_VALIDATION have not been initialized. Please register your TrustChainValidationCallbacksServiceJS implementation, or register a default implementation")
        }
        val trustChains: MutableList<List<Any>> = mutableListOf()
        for (it in jwts) {
            try {
                trustChains.add(validateTrustChain(it, knownTrustChainIds))
            } catch (e: Exception) {
                TrustChainValidationConst.LOG.error("Trust Chain Validation Error: ${e.message.toString()}")
            }
        }
        return trustChains
    }

    private suspend fun validateTrustChain(jwts: List<String>, knownTrustChainIds: List<String>): List<Any> {
        val entityStatements = jwts.toMutableList()
        val firstEntityConfiguration =
            entityStatements.removeFirst().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val lastEntityConfiguration =
            entityStatements.removeLast().let { JsonMapper().mapEntityConfigurationStatement(it) }
        val subordinateStatements = entityStatements.map { JsonMapper().mapSubordinateStatement(it) }

        if (firstEntityConfiguration.iss != firstEntityConfiguration.sub) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
        }

        if (firstEntityConfiguration.jwks.jsonObject["keys"]?.jsonArray?.any {
                jwtService.JWT.verify(
                    input = JwtVerifyInput(
                        jwt = jwts[0],
                        key = retrieveJwk(it)
                    )
                )
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
                            jwtService.JWT.verify(
                                input = JwtVerifyInput(
                                    jwt = jwts[index],
                                    key = retrieveJwk(it)
                                )
                            )
                        } == false) {
                        throw IllegalArgumentException("Invalid signature")
                    }

                is SubordinateStatement ->
                    if (current.iss != next.sub) {
                        throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to sub")
                    } else if (next.jwks.jsonObject["keys"]?.jsonArray?.any {
                            jwtService.JWT.verify(
                                input = JwtVerifyInput(
                                    jwt = jwts[index],
                                    key = retrieveJwk(it)
                                )
                            )
                        } == false) {
                        throw IllegalArgumentException("Invalid signature")
                    }
            }
        }

        if (!knownTrustChainIds.contains(lastEntityConfiguration.iss)) {
            throw IllegalArgumentException("Entity Configuration of the Trust Chain subject requires that iss is equal to the Entity Identifier of the Trust Anchor")
        }
        if (lastEntityConfiguration.jwks.jsonObject["keys"]?.jsonArray?.any {
                jwtService.JWT.verify(
                    input = JwtVerifyInput(
                        jwt = jwts[jwts.size - 1],
                        key = retrieveJwk(it)
                    )
                )
            } == false) {
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

    override fun disable(): ITrustChainValidationCallback {
        this.disabled = true
        return this
    }

    override fun enable(): ITrustChainValidationCallback {
        this.disabled = false
        return this
    }

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun register(platformCallback: ITrustChainValidationCallback): ITrustChainValidationCallback {
        this.platformCallback = platformCallback
        return this
    }
}
