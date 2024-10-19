package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.openapi.models.Jwk

actual fun cryptoService(): ICryptoCallbackService = CryptoServiceObject

actual suspend fun verifyImpl(jwt: String, key: Jwk): Boolean {
    TODO("Not yet implemented")
}
