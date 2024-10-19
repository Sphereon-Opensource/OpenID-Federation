package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.types.ICallbackService
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface ICryptoService {
    suspend fun verify(
        jwt: String,
        key: Jwk,
    ): Boolean
}

interface ICryptoCallbackService : ICallbackService<ICryptoService>, ICryptoService

class DefaultPlatformCallback : ICryptoService {
    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return verifyImpl(jwt, key)
    }
}

object CryptoServiceObject : ICryptoCallbackService {
    private lateinit var platformCallback: ICryptoService

    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        if (!::platformCallback.isInitialized) {
            throw IllegalStateException("CryptoServiceObject not initialized")
        }
        return platformCallback.verify(jwt, key)
    }

    override fun register(platformCallback: ICryptoService?): ICryptoCallbackService {
        this.platformCallback = platformCallback ?: DefaultPlatformCallback()
        return this
    }
}

expect fun cryptoService(): ICryptoCallbackService

expect suspend fun verifyImpl(jwt: String, key: Jwk): Boolean

private fun findKeyInJwks(keys: JsonArray, kid: String): Jwk? {
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
