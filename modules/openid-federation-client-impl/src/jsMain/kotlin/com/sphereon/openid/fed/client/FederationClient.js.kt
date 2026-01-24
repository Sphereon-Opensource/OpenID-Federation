package com.sphereon.openid.fed.client

import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * JavaScript-exported Federation Client.
 *
 * Wraps [FederationClient] with Promise-based API for JavaScript consumers.
 * The delegate should be obtained from DI (session-scoped).
 *
 * ## Lifecycle Management
 *
 * This client manages an internal CoroutineScope for executing async operations.
 * **You must call [close] when you're done using the client** to release resources
 * and prevent memory leaks, especially in long-running Node.js applications.
 *
 * Example usage in JavaScript:
 * ```javascript
 * const client = new FederationClient(delegate);
 * try {
 *     const result = await client.resolveTrustChain(entityId, anchors, 10);
 *     // ... use result
 * } finally {
 *     client.close();
 * }
 * ```
 *
 * @param delegate The FederationClient instance from DI
 */
@JsExport
@JsName("FederationClient")
class FederationClientJS(
    private val delegate: FederationClient
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    @JsName("resolveTrustChain")
    fun resolveTrustChainJS(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 10
    ): Promise<TrustChainResolveResponse> {
        return scope.promise {
            delegate.trustChainResolve(entityIdentifier, trustAnchors, maxDepth)
        }
    }

    @JsName("verifyTrustChain")
    fun verifyTrustChainJS(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Int? = null
    ): Promise<VerifyTrustChainResponse> {
        return scope.promise {
            delegate.trustChainVerify(
                trustChain,
                trustAnchor,
                currentTime?.toLong()
            )
        }
    }

    @JsName("entityConfigurationStatementGet")
    fun entityConfigurationStatementGet(
        entityIdentifier: String
    ): Promise<EntityConfigurationStatement> {
        return scope.promise {
            delegate.entityConfigurationStatementGet(entityIdentifier)
        }
    }

    @JsName("verifyTrustMark")
    fun verifyTrustMarkJS(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Int? = null
    ): Promise<TrustMarkValidationResponse> {
        return scope.promise {
            delegate.trustMarksVerify(
                trustMark,
                trustAnchorConfig,
                currentTime?.toLong()
            )
        }
    }

    /**
     * Closes the client and releases all resources.
     *
     * This cancels all pending coroutines and prevents memory leaks.
     * After calling close, the client should not be used anymore.
     *
     * **Important:** Always call this method when you're done using the client,
     * especially in long-running Node.js applications.
     */
    @JsName("close")
    fun close() {
        job.cancel("FederationClientJS closed")
    }

    /**
     * Returns whether the client has been closed.
     *
     * @return true if the client has been closed, false otherwise
     */
    @JsName("isClosed")
    fun isClosed(): Boolean {
        return job.isCancelled
    }
}
