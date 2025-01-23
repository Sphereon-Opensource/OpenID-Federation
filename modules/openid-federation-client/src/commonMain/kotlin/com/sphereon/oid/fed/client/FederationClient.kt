package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementService
import com.sphereon.oid.fed.client.services.trustChainService.TrustChainService
import com.sphereon.oid.fed.client.types.*
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import com.sphereon.oid.fed.openapi.models.JWT
import kotlin.js.JsExport

/**
 * Federation client for reading and validating statements and trust chains.
 */
@JsExport.Ignore
class FederationClient(
    override val fetchServiceCallback: IFetchService? = null,
    override val cryptoServiceCallback: ICryptoService? = null
) : IFederationClient {
    private val fetchService: IFetchService =
        fetchServiceCallback ?: fetchService()
    private val cryptoService: ICryptoService = cryptoServiceCallback ?: cryptoService()

    private val trustChainService: TrustChainService = TrustChainService(fetchService, cryptoService)
    private val entity: EntityConfigurationStatementService =
        EntityConfigurationStatementService(fetchService, cryptoService)

    /**
     * Builds a trust chain for the given entity identifier using the provided trust anchors.
     * It returns the first trust chain that is successfully resolved.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use for building the trust chain.
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return A [TrustChainResolveResponse] object containing the resolved trust chain.
     */
    suspend fun trustChainResolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): TrustChainResolveResponse {
        return trustChainService.resolve(entityIdentifier, trustAnchors, maxDepth)
    }

    /**
     * Verifies the trust chain.
     *
     * @param trustChain The trust chain to verify.
     * @param trustAnchor The trust anchor to use for verification. Optional.
     * @param currentTime The current time to use for verification. Defaults to the current epoch time in seconds.
     *
     * @return A [VerifyTrustChainResponse] object containing the verification result.
     */
    suspend fun trustChainVerify(
        trustChain: List<String>,
        trustAnchor: String?,
        currentTime: Long?
    ): VerifyTrustChainResponse {
        return trustChainService.verify(trustChain, trustAnchor, currentTime)
    }

    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return [JWT] ]A JWT object containing the entity configuration statement.
     */
    suspend fun entityConfigurationStatementGet(entityIdentifier: String): EntityConfigurationStatementDTO {
        return entity.getEntityConfigurationStatement(entityIdentifier)
    }
}
