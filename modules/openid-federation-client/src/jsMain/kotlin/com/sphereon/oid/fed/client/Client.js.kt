package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.crypto.CryptoServiceObject
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.FetchServiceObject
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

@JsExport
@JsName("FederationClient")
class FederationClientJS
    (
    fetchServiceCallbackk: IFetchService?,
    cryptoServiceCallback: ICryptoService?,
) {
    val fetchService = FetchServiceObject.register(fetchServiceCallbackk)
    val cryptoService = CryptoServiceObject.register(cryptoServiceCallback)
    val trustChainService = TrustChain(fetchService, cryptoService)

    @OptIn(DelicateCoroutinesApi::class)
    @JsName("resolveTrustChain")
    fun resolveTrustChainJS(entityIdentifier: String, trustAnchors: Array<String>): Promise<Array<String>?> {
        return GlobalScope.promise {
            trustChainService.resolve(
                entityIdentifier,
                trustAnchors
            )?.toTypedArray()
        }
    }
}
