package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.client.service.ICallbackService
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.JsExport

expect interface ICryptoCallbackMarkerType
interface ICryptoMarkerType

@JsExport.Ignore
interface ICryptoCallbackService: ICryptoCallbackMarkerType {
    suspend fun verify(
        jwt: String,
        key: Jwk,
    ): Boolean
}

@JsExport.Ignore
interface ICryptoService: ICryptoMarkerType {
    suspend fun verify(
        jwt: String,
        key: Jwk,
    ): Boolean
}

expect fun cryptoService(platformCallback: ICryptoCallbackMarkerType = DefaultCallbacks.jwtService()): ICryptoService

abstract class AbstractCryptoService<CallbackServiceType>(open val platformCallback: CallbackServiceType?): ICallbackService<CallbackServiceType> {
    private var disabled = false

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun disable() = apply {
        this.disabled = true
    }

    override fun enable() = apply {
        this.disabled = false
    }

    protected fun assertEnabled() {
        if (!isEnabled()) {
            //CryptoConst.LOG.info("CRYPTO verify has been disabled")
            throw IllegalStateException("CRYPTO service is disable; cannot verify")
        } else if (this.platformCallback == null) {
            //CryptoConst.LOG.error("CRYPTO callback is not registered")
            throw IllegalStateException("CRYPTO has not been initialized. Please register your CryptoCallback implementation, or register a default implementation")
        }
    }
}

class CryptoService(override val platformCallback: ICryptoCallbackService = DefaultCallbacks.jwtService()): AbstractCryptoService<ICryptoCallbackService>(platformCallback), ICryptoService {
    override fun platform(): ICryptoCallbackService {
        return this.platformCallback
    }

    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return this.platformCallback.verify(jwt, key)
    }

}

// ###########################################################################


//class DefaultPlatformCallback : ICryptoCallbackService {
//    override suspend fun verify(jwt: String, key: Jwk): Boolean {
//        return verifyImpl(jwt, key)
//    }
//}
//
//object CryptoServiceObject : ICryptoCallbackService {
//    private lateinit var platformCallback: ICryptoCallbackService
//
//    override suspend fun verify(jwt: String, key: Jwk): Boolean {
//        if (!::platformCallback.isInitialized) {
//            throw IllegalStateException("CryptoServiceObject not initialized")
//        }
//        return platformCallback.verify(jwt, key)
//    }
//
//    override fun register(platformCallback: ICryptoCallbackService?): ICryptoCallbackService {
//        this.platformCallback = platformCallback ?: DefaultPlatformCallback()
//        return this
//    }
//}

//expect fun cryptoService(): ICryptoCallbackService
//
//expect suspend fun verifyImpl(jwt: String, key: Jwk): Boolean

fun findKeyInJwks(keys: JsonArray, kid: String): Jwk? {
    val key = keys.firstOrNull { it.jsonObject["kid"]?.jsonPrimitive?.content?.trim() == kid.trim() }

    if (key == null) return null

    return Json.decodeFromJsonElement(Jwk.serializer(), key)
}

fun getKeyFromJwt(jwt: String): Jwk {
    val decodedJwt = decodeJWTComponents(jwt)

    val key = findKeyInJwks(
        decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: JsonArray(emptyList()),
        decodedJwt.header.kid
    ) ?: throw IllegalStateException("Key not found")

    return key
}
