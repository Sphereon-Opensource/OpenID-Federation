package com.sphereon.oid.fed.client.fetch

import kotlin.js.JsExport

@JsExport.Ignore
interface IFetchService {
    suspend fun fetchStatement(
        endpoint: String
    ): String
}

expect fun fetchService(): IFetchService
