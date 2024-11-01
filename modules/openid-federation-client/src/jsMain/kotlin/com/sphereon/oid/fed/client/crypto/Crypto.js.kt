package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlin.js.Promise

@JsExport
external interface ICryptoCallbackServiceJS: ICryptoCallbackMarkerType {
    fun verify(
        jwt: String,
        key: Jwk,
    ): Promise<Boolean>
}

@JsExport
external interface ICryptoServiceJS {
    fun verify(
        jwt: String,
        key: Jwk
    ): Promise<Boolean>
}

private const val CRYPTO_SERVICE_JS_SCOPE = "CryptoServiceJS"

@JsExport
class CryptoServiceJS(override val platformCallback: ICryptoCallbackServiceJS = DefaultCallbacks.jwtService()): AbstractCryptoService<ICryptoCallbackServiceJS>(platformCallback), ICryptoServiceJS {

    override fun platform(): ICryptoCallbackServiceJS {
        return this.platformCallback
    }

    override fun verify(
        jwt: String,
        key: Jwk
    ): Promise<Boolean> {
        return CoroutineScope(context = CoroutineName(CRYPTO_SERVICE_JS_SCOPE)).async {
            return@async platformCallback.verify(jwt, key).await()
        }.asPromise()
    }
}

class CryptoServiceJSAdapter(val cryptoCallbackJS: CryptoServiceJS = CryptoServiceJS()): AbstractCryptoService<ICryptoCallbackServiceJS>(cryptoCallbackJS.platformCallback), ICryptoService {

    override fun platform(): ICryptoCallbackServiceJS = cryptoCallbackJS.platformCallback

    override suspend fun verify(
        jwt: String,
        key: Jwk
    ): Boolean = this.cryptoCallbackJS.verify(jwt, key).await()
}

@JsExport.Ignore
actual fun cryptoService(platformCallback: ICryptoCallbackMarkerType): ICryptoService {
    val jsPlatformCallback = platformCallback.unsafeCast<ICryptoCallbackServiceJS>()
    if (jsPlatformCallback === undefined) {
        throw IllegalStateException("Invalid platform callback supplied: Needs to be of type ICryptoCallbackServiceJS, but is of type ${platformCallback::class::simpleName} instead")
    }
    return CryptoServiceJSAdapter(CryptoServiceJS(jsPlatformCallback))
}

@JsExport
actual external interface ICryptoCallbackMarkerType
