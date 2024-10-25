package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.service.DefaultCallbacks
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlin.js.Promise

@JsExport
interface ITrustChainCallbackServiceJS: ITrustChainCallbackMarkerType {
    fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int = 5
    ): Promise<MutableList<String>?>
}

@JsExport.Ignore
interface ITrustChainServiceJS: ITrustChainMarkerType {
    fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int = 5
    ): Promise<MutableList<String>?>
}

private const val TRUST_CHAIN_SERVICE_JS_SCOPE = "TrustChainServiceJS"

@JsExport
class TrustChainServiceJS(override val platformCallback: ITrustChainCallbackServiceJS = DefaultCallbacks.trustChainService()): AbstractTrustChainService<ITrustChainCallbackServiceJS>(platformCallback), ITrustChainServiceJS {

    override fun platform(): ITrustChainCallbackServiceJS {
        return this.platformCallback
    }

    override fun resolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): Promise<MutableList<String>?> {
        return CoroutineScope(context = CoroutineName(TRUST_CHAIN_SERVICE_JS_SCOPE)).async {
            return@async platformCallback.resolve(entityIdentifier, trustAnchors, maxDepth).await()
        }.asPromise()
    }
}

class TrustChainServiceJSAdapter(val trustChainCallbackJS: TrustChainServiceJS = TrustChainServiceJS()): AbstractTrustChainService<ITrustChainCallbackServiceJS>(trustChainCallbackJS.platformCallback), ITrustChainService {

    override fun platform(): ITrustChainCallbackServiceJS = trustChainCallbackJS.platformCallback

    override suspend fun resolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): MutableList<String>? = this.trustChainCallbackJS.resolve(entityIdentifier, trustAnchors, maxDepth).await()
}

@JsExport.Ignore
actual fun trustChainService(platformCallback: ITrustChainCallbackMarkerType): ITrustChainService {
    val jsPlatformCallback = platformCallback.unsafeCast<ITrustChainCallbackServiceJS>()
    if (jsPlatformCallback === undefined) {
        throw IllegalStateException("Invalid platform callback supplied: Needs to be of type ITrustChainCallbackServiceJS, but is of type ${platformCallback::class::simpleName} instead")
    }
    return TrustChainServiceJSAdapter(TrustChainServiceJS(jsPlatformCallback))
}

@JsExport
actual external interface ITrustChainCallbackMarkerType
