package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.crypto.ICryptoCallbackMarkerType
import com.sphereon.oid.fed.client.fetch.IFetchCallbackMarkerType
import com.sphereon.oid.fed.client.trustchain.TrustChain

class FederationClient(fetchServiceCallback: IFetchCallbackMarkerType?, cryptoServiceCallback: ICryptoCallbackMarkerType) {
    private val trustChainService = TrustChain(fetchServiceCallback, cryptoServiceCallback)

    suspend fun resolveTrustChain(entityIdentifier: String, trustAnchors: Array<String>): MutableList<String>? {
        return trustChainService.resolve(entityIdentifier, trustAnchors)
    }
}
