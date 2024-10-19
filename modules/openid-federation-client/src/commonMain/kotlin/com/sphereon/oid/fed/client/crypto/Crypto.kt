package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.types.ICallbackService
import com.sphereon.oid.fed.openapi.models.Jwk

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
