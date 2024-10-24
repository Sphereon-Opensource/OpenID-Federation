package com.sphereon.oid.fed.client.crypto

actual fun cryptoService(platformCallback: ICryptoCallbackMarkerType): ICryptoService {
    if (platformCallback !is ICryptoCallbackService) {
        throw IllegalArgumentException("Platform callback is not of type ICryptoCallbackService, but ${platformCallback.javaClass.canonicalName}")
    }
    return CryptoService(platformCallback)
}

actual interface ICryptoCallbackMarkerType
