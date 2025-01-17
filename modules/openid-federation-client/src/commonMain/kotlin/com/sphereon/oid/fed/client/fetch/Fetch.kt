package com.sphereon.oid.fed.client.fetch

interface IFetchService {
    suspend fun fetchStatement(
        endpoint: String
    ): String
}

expect fun fetchService(): IFetchService
