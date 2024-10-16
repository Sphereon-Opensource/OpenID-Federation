package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.types.ICallbackService


interface ICryptoService {
    suspend fun verify(
        jwt: String,
    ): Boolean
}

interface ICryptoCallbackService : ICallbackService<ICryptoService>, ICryptoService

expect fun cryptoService(): ICryptoCallbackService

class DefaultPlatformCallback : ICryptoService {
    override suspend fun verify(jwt: String): Boolean {
        return verify(jwt)
    }
}

object CryptoServiceObject : ICryptoCallbackService {
    private lateinit var platformCallback: ICryptoService

    override suspend fun verify(jwt: String): Boolean {
        return this.platformCallback.verify(jwt)
    }

    override fun register(platformCallback: ICryptoService?): ICryptoCallbackService {
        this.platformCallback = platformCallback ?: DefaultPlatformCallback()
        return this
    }
}
