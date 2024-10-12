package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.fetch.FetchServiceObject
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.trustchain.resolve

class FederationClient(platformCallback: IFetchService?) {
    private val fetchService = FetchServiceObject.register(platformCallback)

    suspend fun resolveTrustChain(entityIdentifier: String, trustAnchors: Array<String>): MutableList<String>? {
        return resolve(entityIdentifier, trustAnchors, fetchService)
    }
}
