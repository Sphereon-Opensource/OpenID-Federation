package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.crypto.ICryptoCallbackServiceJS
import com.sphereon.oid.fed.client.fetch.IFetchCallbackServiceJS
import com.sphereon.oid.fed.client.trustchain.TrustChain
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

@JsExport
@JsName("FederationClient")
class FederationClientJS
    (
    fetchServiceCallback: IFetchCallbackServiceJS,
    cryptoServiceCallback: ICryptoCallbackServiceJS,
) {
    val trustChainService = TrustChain(fetchServiceCallback, cryptoServiceCallback)

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
