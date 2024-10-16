package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.types.ICallbackService
import kotlinx.coroutines.await
import kotlin.js.Promise

@JsExport
external interface ICallbackCryptoServiceJS<PlatformCallbackType> {
    /**
     * Register the platform specific callback that implements the verification
     *
     * External developers use this as an entry point for their platform code
     */
    @JsName("register")
    fun register(platformCallback: PlatformCallbackType): ICallbackCryptoServiceJS<PlatformCallbackType>
}

@JsExport
external interface ICryptoServiceCallbackJS {
    @JsName("verify")
    fun verify(
        jwt: String,
    ): Promise<Boolean>
}

@JsExport
object CryptoServiceJS : ICallbackCryptoServiceJS<ICryptoServiceCallbackJS>, ICryptoServiceCallbackJS {
    private lateinit var platformCallback: ICryptoServiceCallbackJS

    override fun register(platformCallback: ICryptoServiceCallbackJS): CryptoServiceJS {
        this.platformCallback = platformCallback
        return this
    }

    override fun verify(jwt: String): Promise<Boolean> {
        return platformCallback.verify(jwt)
    }
}


open class CryptoServiceJSAdapter(private val cryptoServiceCallbackJS: CryptoServiceJS = CryptoServiceJS) :
    ICryptoCallbackService {
    override fun register(platformCallback: ICryptoService?): ICallbackService<ICryptoService> {
        throw Error("Register function should not be used on the adapter. It depends on the Javascript CryptoService object")
    }

    override suspend fun verify(jwt: String): Boolean {
        return cryptoServiceCallbackJS.verify(jwt).await()
    }
}

object CryptoServiceJSAdapterObject : CryptoServiceJSAdapter(CryptoServiceJS)


actual fun cryptoService(): ICryptoCallbackService = CryptoServiceJSAdapterObject
