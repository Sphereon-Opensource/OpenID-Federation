package com.sphereon.openid.fed.server.admin.api.http

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor

/**
 * Optional contribution of LEGACY account-management REST to the admin HTTP adapter.
 *
 * ## Classpath contract (not a build "mode")
 * Implementations live in `openid-federation-account-http`. If that module is absent,
 * Metro injects an empty [Set] and `/accounts` is not registered.
 *
 * ## Boundary
 * Do not put account types on the admin-server core constructor graph; inject
 * [AdminAccountEndpointContribution] sets only.
 */
interface AdminAccountEndpointContribution {
    /** Session-scoped endpoint command instances. */
    val endpointCommands: List<HttpEndpointCommand>
}

/**
 * App-scoped OpenAPI / descriptor contribution for account REST.
 * Empty when account-http is not on the classpath.
 */
interface AdminAccountDescriptorContribution {
    val endpointDescriptors: List<HttpEndpointDescriptor>
}
