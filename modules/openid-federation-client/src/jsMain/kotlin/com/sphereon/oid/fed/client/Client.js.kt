package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.fetch.FetchServiceObject
import com.sphereon.oid.fed.client.trustchain.resolve
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

@JsExport
@JsName("FederationClient")
class FederationClient1 {
    private val fetchService = FetchServiceObject.register(null)

    @OptIn(DelicateCoroutinesApi::class)
    @JsName("validateTrustChain")
    fun validateTrustChain1(entityIdentifier: String, trustAnchors: Array<String>): Promise<Array<String>?> {
        return GlobalScope.promise{ resolve(entityIdentifier, trustAnchors, fetchService)?.toTypedArray() }
    }
}
