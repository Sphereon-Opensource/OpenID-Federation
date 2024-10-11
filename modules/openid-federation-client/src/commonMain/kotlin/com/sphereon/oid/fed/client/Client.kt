package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.fetch.FetchServiceObject
import com.sphereon.oid.fed.client.trustchain.TrustChain
import com.sphereon.oid.fed.client.fetch.IFetchService

interface ClientPlatformCallback : IFetchService

class FederationClient (platformCallback: ClientPlatformCallback?) {
    private val fetchService = FetchServiceObject.register(platformCallback)
    val trustChain = TrustChain(fetchService)
}
