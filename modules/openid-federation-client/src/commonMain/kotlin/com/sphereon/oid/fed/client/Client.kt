package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.crypto.CryptoServiceObject
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.FetchServiceObject
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain

class FederationClient(fetchServiceCallback: IFetchService?, cryptoServiceCallback: ICryptoService) {
    private val fetchService = FetchServiceObject.register(fetchServiceCallback)
    private val cryptoService = CryptoServiceObject.register(cryptoServiceCallback)
    private val trustChainService = TrustChain(fetchService, cryptoService)

    suspend fun resolveTrustChain(entityIdentifier: String, trustAnchors: Array<String>): MutableList<String>? {
        return trustChainService.resolve(entityIdentifier, trustAnchors)
    }
}
