package com.sphereon.oid.fed.client.types

import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import kotlin.js.JsExport

/**
 * Interface for the FederationClient
 */
@JsExport.Ignore
interface IFederationClient {
    val fetchServiceCallback: IFetchService?
    val cryptoServiceCallback: ICryptoService?
}
