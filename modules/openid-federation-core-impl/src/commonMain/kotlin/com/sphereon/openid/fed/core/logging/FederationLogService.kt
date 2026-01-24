package com.sphereon.openid.fed.core.logging

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.LogService
import com.sphereon.core.api.log.SessionLogService
import me.tatarka.inject.annotations.Inject

/**
 * Interface for federation-specific logging.
 *
 * This interface extends the IDK's logging patterns while providing
 * federation-specific logging methods and contextual information.
 */
interface FederationLogService {
    /**
     * Log a trace message
     */
    fun trace(message: String, metadata: Map<String, Any?>? = null)

    /**
     * Log a debug message
     */
    fun debug(message: String, metadata: Map<String, Any?>? = null)

    /**
     * Log an info message
     */
    fun info(message: String, metadata: Map<String, Any?>? = null)

    /**
     * Log a warning message
     */
    fun warn(message: String, metadata: Map<String, Any?>? = null)

    /**
     * Log an error message
     */
    fun error(message: String, exception: Throwable? = null, metadata: Map<String, Any?>? = null)

    /**
     * Create a tagged logger for a specific component
     */
    fun withTag(tag: String): FederationLogService
}

/**
 * Federation-specific log tags for categorizing log messages.
 */
object FederationLogTags {
    const val ENTITY_CONFIG = "federation.entity-config"
    const val TRUST_CHAIN = "federation.trust-chain"
    const val TRUST_MARK = "federation.trust-mark"
    const val SUBORDINATE = "federation.subordinate"
    const val RESOLUTION = "federation.resolution"
    const val JWT = "federation.jwt"
    const val KMS = "federation.kms"
    const val ACCOUNT = "federation.account"
    const val HTTP = "federation.http"
    const val CACHE = "federation.cache"
}

/**
 * Implementation of FederationLogService that wraps IDK's SessionLogService.
 *
 * This implementation is session-scoped and automatically includes
 * tenant/principal context in log messages when available.
 */
@Inject
class FederationLogServiceImpl(
    private val sessionLogService: SessionLogService,
    private val tag: String = "federation"
) : FederationLogService {

    private val logger: LogService by lazy {
        sessionLogService.logManager.withTag(tag)
    }

    override fun trace(message: String, metadata: Map<String, Any?>?) {
        logger.trace(formatMessage(message, metadata))
    }

    override fun debug(message: String, metadata: Map<String, Any?>?) {
        logger.debug(formatMessage(message, metadata))
    }

    override fun info(message: String, metadata: Map<String, Any?>?) {
        logger.info(formatMessage(message, metadata))
    }

    override fun warn(message: String, metadata: Map<String, Any?>?) {
        logger.warn(formatMessage(message, metadata))
    }

    override fun error(message: String, exception: Throwable?, metadata: Map<String, Any?>?) {
        logger.error(formatMessage(message, metadata), exception)
    }

    override fun withTag(tag: String): FederationLogService {
        return FederationLogServiceImpl(sessionLogService, tag)
    }

    private fun formatMessage(message: String, metadata: Map<String, Any?>?): String {
        return if (metadata.isNullOrEmpty()) {
            message
        } else {
            "$message | metadata=${metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        }
    }
}

/**
 * Factory for creating FederationLogService instances.
 *
 * This factory provides a convenient way to create loggers with specific tags
 * for different federation components.
 */
@Inject
class FederationLogServiceFactory(
    private val sessionExecution: SessionExecution
) {
    /**
     * Create a FederationLogService for the given tag
     */
    fun create(tag: String = "federation"): FederationLogService {
        return FederationLogServiceImpl(sessionExecution.log, tag)
    }

    /**
     * Create loggers for common federation components
     */
    fun entityConfig(): FederationLogService = create(FederationLogTags.ENTITY_CONFIG)
    fun trustChain(): FederationLogService = create(FederationLogTags.TRUST_CHAIN)
    fun trustMark(): FederationLogService = create(FederationLogTags.TRUST_MARK)
    fun subordinate(): FederationLogService = create(FederationLogTags.SUBORDINATE)
    fun resolution(): FederationLogService = create(FederationLogTags.RESOLUTION)
    fun jwt(): FederationLogService = create(FederationLogTags.JWT)
    fun kms(): FederationLogService = create(FederationLogTags.KMS)
    fun account(): FederationLogService = create(FederationLogTags.ACCOUNT)
    fun http(): FederationLogService = create(FederationLogTags.HTTP)
    fun cache(): FederationLogService = create(FederationLogTags.CACHE)
}

/**
 * Extension function to create a FederationLogService from SessionExecution.
 */
fun SessionExecution.federationLogger(tag: String = "federation"): FederationLogService {
    return FederationLogServiceImpl(this.log, tag)
}
