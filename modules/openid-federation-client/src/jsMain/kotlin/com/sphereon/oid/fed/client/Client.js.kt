package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.client.trustchain.ITrustChainCallbackServiceJS
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlin.js.Promise

@JsExport
@JsName("FederationClient")
class FederationClientJS(val trustChainServiceCallback: ITrustChainCallbackServiceJS? = DefaultCallbacks.trustChainService()) {

    @OptIn(DelicateCoroutinesApi::class)
    @JsName("resolveTrustChain")
    fun resolveTrustChainJS(entityIdentifier: String, trustAnchors: Array<String>): Promise<Array<String>?> {
        return CoroutineScope(context = CoroutineName("")).async {
            return@async trustChainServiceCallback?.resolve(entityIdentifier, trustAnchors)?.await()?.toTypedArray()
        }.asPromise()
    }
}
