package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.client.trustchain.ITrustChainCallbackService

class FederationClient(val trustChainService: ITrustChainCallbackService? = DefaultCallbacks.trustChainService()) {

    suspend fun resolveTrustChain(entityIdentifier: String, trustAnchors: Array<String>): MutableList<String>? {
        return trustChainService?.resolve(entityIdentifier, trustAnchors)
    }
}
