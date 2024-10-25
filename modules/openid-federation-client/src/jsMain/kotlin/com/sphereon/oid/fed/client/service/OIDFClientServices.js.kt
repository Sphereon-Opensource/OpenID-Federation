package com.sphereon.oid.fed.client.service

import com.sphereon.oid.fed.client.crypto.CryptoServiceJS
import com.sphereon.oid.fed.client.crypto.ICryptoCallbackServiceJS
import com.sphereon.oid.fed.client.fetch.FetchServiceJS
import com.sphereon.oid.fed.client.fetch.IFetchCallbackServiceJS
import com.sphereon.oid.fed.client.trustchain.ITrustChainCallbackServiceJS
import com.sphereon.oid.fed.client.trustchain.TrustChainServiceJS

@JsExport
object CryptoServicesJS {
    fun crypto(platformCallback: ICryptoCallbackServiceJS = DefaultCallbacks.jwtService()) = CryptoServiceJS(platformCallback)
    fun fetch(platformCallback: IFetchCallbackServiceJS = DefaultCallbacks.fetchService()) = FetchServiceJS(platformCallback)
    fun trustChain(platformCallback: ITrustChainCallbackServiceJS = DefaultCallbacks.trustChainService()) = TrustChainServiceJS(platformCallback)
}

@JsExport
external interface ICallbackServiceJS<PlatformCallbackType> {
    /**
     * Disable callback verification (be careful!)
     */
    fun disable(): ICallbackServiceJS<PlatformCallbackType>

    /**
     * Enable the callback verification (default)
     */
    fun enable(): ICallbackServiceJS<PlatformCallbackType>


    /**
     * Is the service enabled or not
     */
    fun isEnabled(): Boolean

    fun platform(): PlatformCallbackType
}
