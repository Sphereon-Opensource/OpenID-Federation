package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import kotlin.js.JsExport

@JsExport.Ignore
interface IFederationClient {
    val fetchServiceCallback: IFetchService?
    val cryptoServiceCallback: ICryptoService?
}

@JsExport.Ignore
class FederationClient(
    override val fetchServiceCallback: IFetchService? = null,
    override val cryptoServiceCallback: ICryptoService? = null
) : IFederationClient {
    private val fetchService: IFetchService =
        fetchServiceCallback ?: fetchService()
    private val cryptoService: ICryptoService = cryptoServiceCallback ?: cryptoService()

    private val trustChainService: TrustChain = TrustChain(fetchService, cryptoService)

    suspend fun resolveTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): MutableList<String>? {
        return trustChainService.resolve(entityIdentifier, trustAnchors, maxDepth)
    }
}
