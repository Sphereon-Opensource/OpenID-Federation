package com.sphereon.oid.fed.client.fetch

actual fun fetchService(platformCallback: IFetchCallbackMarkerType): IFetchService {
    if (platformCallback !is IFetchCallbackService) {
        throw IllegalArgumentException("Platform callback is not of type IFetchCallbackService, but ${platformCallback.javaClass.canonicalName}")
    }
    return FetchService(platformCallback)
}

actual interface IFetchCallbackMarkerType
