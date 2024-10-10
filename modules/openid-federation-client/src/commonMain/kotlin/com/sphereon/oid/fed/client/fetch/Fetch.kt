package com.sphereon.oid.fed.client.fetch

import kotlin.jvm.JvmStatic

interface ICallbackService<PlatformCallbackType> {
    fun register(platformCallback: PlatformCallbackType): ICallbackService<PlatformCallbackType>
}

interface IFetchService {
    suspend fun fetchStatement(
        endpoint: String
    ): String
}

interface IFetchCallbackService : ICallbackService<IFetchService>, IFetchService

expect fun fetchService(): IFetchCallbackService

object FetchServiceObject : IFetchCallbackService {
    @JvmStatic
    private lateinit var platformCallback: IFetchService

    override suspend fun fetchStatement(endpoint: String): String {
        return platformCallback.fetchStatement(endpoint)
    }

    override fun register(platformCallback: IFetchService): IFetchCallbackService {
        this.platformCallback = platformCallback
        return this
    }
}
