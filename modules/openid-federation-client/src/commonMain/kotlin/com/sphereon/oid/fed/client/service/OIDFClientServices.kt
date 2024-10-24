package com.sphereon.oid.fed.client.service

import com.sphereon.oid.fed.client.crypto.ICryptoCallbackMarkerType
import com.sphereon.oid.fed.client.fetch.IFetchCallbackMarkerType
import kotlin.js.JsExport

@JsExport
object DefaultCallbacks {
    private var cryptoCallbackService: ICryptoCallbackMarkerType? = null
    private var fetchCallbackService: IFetchCallbackMarkerType? = null

    fun <CallbackType: ICryptoCallbackMarkerType> jwtService(): CallbackType {
        if (cryptoCallbackService == null) {
            throw IllegalStateException("No default Crypto Platform Callback implementation was registered")
        }
        return cryptoCallbackService as CallbackType
    }

    fun setCryptoServiceDefault(cryptoCallbackService: ICryptoCallbackMarkerType?) {
        this.cryptoCallbackService = cryptoCallbackService
    }

    fun <CallbackType: IFetchCallbackMarkerType> fetchService(): CallbackType {
        if (fetchCallbackService == null) {
            throw IllegalStateException("No default Crypto Platform Callback implementation was registered")
        }
        return fetchCallbackService as CallbackType
    }

    fun setFetchServiceDefault(fetchCallbackService: IFetchCallbackMarkerType?) {
        this.fetchCallbackService = fetchCallbackService
    }
}

/**
 * The main entry point for platform validation, delegating to a platform specific callback implemented by external developers
 */

interface ICallbackService<PlatformCallbackType> {

    /**
     * Disable callback verification (be careful!)
     */
    fun disable(): ICallbackService<PlatformCallbackType>

    /**
     * Enable the callback verification (default)
     */
    fun enable(): ICallbackService<PlatformCallbackType>

    /**
     * Is the service enabled or not
     */
    fun isEnabled(): Boolean

    fun platform(): PlatformCallbackType
}