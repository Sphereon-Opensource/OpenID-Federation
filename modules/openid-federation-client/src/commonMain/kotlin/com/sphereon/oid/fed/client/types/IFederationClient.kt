package com.sphereon.oid.fed.client.types

import io.ktor.client.*
import kotlin.js.JsExport

/**
 * Interface for the FederationClient
 */
@JsExport.Ignore
interface IFederationClient {
    val cryptoServiceCallback: ICryptoService?
    val httpClient: HttpClient?
}