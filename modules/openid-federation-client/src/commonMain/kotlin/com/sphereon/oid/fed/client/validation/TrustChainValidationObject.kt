package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.ICallbackService
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
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

object TrustChainValidationObject : TrustChainValidationCallback {

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
        return platformCallback.readAuthorityHints(partyBId)
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
       return platformCallback.fetchSubordinateStatements(entityConfigurationStatementsList)
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
       return platformCallback.validateTrustChains(jwts, knownTrustChainIds)
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
