package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.httpclient.httpService


object OidFederationClientServiceJS {
    val HTTP = httpService()
}

/**
 * The main entry point for platform validation, delegating to a platform specific callback implemented by external developers
 */
interface ICallbackServiceJS<PlatformCallbackType> {
    /**
     * Disable callback verification (be careful!)
     */
    fun disable(): PlatformCallbackType

    /**
     * Enable the callback verification (default)
     */
    fun enable(): PlatformCallbackType


    /**
     * Is the service enabled or not
     */
    fun isEnabled(): Boolean

    /**
     * Register the platform specific callback that implements the verification
     *
     * External developers use this as an entry point for their platform code
     */
    @JsName("register")
    fun register(platformCallback: PlatformCallbackType): ICallbackServiceJS<PlatformCallbackType>
}
