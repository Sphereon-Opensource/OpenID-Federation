package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.httpclient.httpService
import com.sphereon.oid.fed.client.validation.trustChainValidationService

object OidFederationClientService {
    val HTTP = httpService()
    val TRUST_CHAIN_VALIDATION = trustChainValidationService()
}


interface ICallbackService<PlatformCallbackType> {
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
    fun register(platformCallback: PlatformCallbackType): PlatformCallbackType
}
