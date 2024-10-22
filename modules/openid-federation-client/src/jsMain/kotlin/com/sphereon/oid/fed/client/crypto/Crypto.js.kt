package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.types.ICallbackService
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.Promise

@JsModule("jose")
@JsNonModule
external object Jose {
    fun importJWK(jwk: Jwk, alg: String, options: dynamic = definedExternally): Promise<dynamic>
    fun jwtVerify(jwt: String, key: Any, options: dynamic = definedExternally): Promise<dynamic>
}

@JsExport
@JsName("ICallbackCryptoService")
external interface ICallbackCryptoServiceJS<PlatformCallbackType> {
    /**
     * Register the platform specific callback that implements the verification
     *
     * External developers use this as an entry point for their platform code
     */
    @JsName("register")
    fun register(platformCallback: PlatformCallbackType?): ICallbackCryptoServiceJS<PlatformCallbackType>
}

@JsExport
@JsName("ICryptoServiceCallback")
external interface ICryptoServiceCallbackJS {
    fun verify(
        jwt: String,
        key: Jwk
    ): Promise<Boolean>
}

class DefaultPlatformCallbackJS : ICryptoServiceCallbackJS {
    @OptIn(DelicateCoroutinesApi::class)
    override fun verify(jwt: String, key: Jwk): Promise<Boolean> {
        return GlobalScope.promise { verifyImpl(jwt, key) }
    }
}

@JsExport
@JsName("CryptoService")
object CryptoServiceJS : ICallbackCryptoServiceJS<ICryptoServiceCallbackJS>, ICryptoServiceCallbackJS {
    private lateinit var platformCallback: ICryptoServiceCallbackJS

    override fun register(platformCallback: ICryptoServiceCallbackJS?): CryptoServiceJS {
        this.platformCallback = (platformCallback ?: DefaultPlatformCallbackJS())
        return this
    }

    override fun verify(jwt: String, key: Jwk): Promise<Boolean> {
        if (!::platformCallback.isInitialized) {
            throw IllegalStateException("CryptoServiceJS not initialized")
        }

        return platformCallback.verify(jwt, key)
    }
}

open class CryptoServiceJSAdapter(private val cryptoServiceCallbackJS: CryptoServiceJS = CryptoServiceJS) :
    ICryptoCallbackService {
    override fun register(platformCallback: ICryptoService?): ICallbackService<ICryptoService> {
        throw Error("Register function should not be used on the adapter. It depends on the Javascript CryptoService object")
    }

    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return cryptoServiceCallbackJS.verify(jwt, key).await()
    }
}

object CryptoServiceJSAdapterObject : CryptoServiceJSAdapter(CryptoServiceJS)

actual fun cryptoService(): ICryptoCallbackService = CryptoServiceJSAdapterObject

actual suspend fun verifyImpl(jwt: String, key: Jwk): Boolean {
    try {
        val decodedJwt = decodeJWTComponents(jwt)

        val publicKey = Jose.importJWK(
            JSON.parse<dynamic>(Json.encodeToString(key)), alg = decodedJwt.header.alg ?: "RS256"
        ).await()

        val verification = Jose.jwtVerify(jwt, publicKey).await()
        return verification != undefined
    } catch (e: Throwable) {
        return false
    }
}
