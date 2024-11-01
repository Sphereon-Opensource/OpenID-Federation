package com.sphereon.oid.fed.client.trustchain

actual fun trustChainService(platformCallback: ITrustChainCallbackMarkerType): ITrustChainService {
    if (platformCallback !is ITrustChainCallbackService) {
        throw IllegalArgumentException("Platform callback is not of type IFetchCallbackService, but ${platformCallback.javaClass.canonicalName}")
    }
    return TrustChainService(platformCallback)
}

actual interface ITrustChainCallbackMarkerType
