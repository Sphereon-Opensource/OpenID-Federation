package com.sphereon.oid.fed.client.types

interface IFetchService {
    suspend fun fetchStatement(
        endpoint: String
    ): String
}