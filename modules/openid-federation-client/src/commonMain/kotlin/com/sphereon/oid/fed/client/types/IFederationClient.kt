package com.sphereon.oid.fed.client.types

import kotlin.js.JsExport

/**
 * Interface for the FederationClient
 */
@JsExport.Ignore
interface IFederationClient {
    val fetchServiceCallback: IFetchService?
    val cryptoServiceCallback: ICryptoService?
}
