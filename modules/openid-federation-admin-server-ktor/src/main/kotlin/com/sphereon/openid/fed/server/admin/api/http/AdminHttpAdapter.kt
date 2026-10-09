package com.sphereon.openid.fed.server.admin.api.http

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommandRegistry
import com.sphereon.core.api.http.command.PublicApiHttpAdapter
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey

/**
 * HTTP Adapter for the OpenID Federation Admin API.
 *
 * This adapter resolves the selected admin endpoint lazily through the IDK
 * command registry and provides the HTTP routing layer.
 *
 * All endpoints are mounted at the root path (no base path prefix).
 * The server configuration can add a server prefix like "/api" if needed.
 *
 * The map key must match [ID] so the dispatcher can resolve the selected adapter.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpAdapter>())
@StringKey(AdminHttpAdapter.ID)
class AdminHttpAdapter(
    execution: SessionExecution,
    endpointCommandRegistry: HttpEndpointCommandRegistry,
) : PublicApiHttpAdapter(
    id = ID,
    sessionExecution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    ),
    endpointCommandRegistry = endpointCommandRegistry,
) {
    companion object {
        /** CommandId-compatible adapter id (module.service.command). */
        const val ID = "fed.admin.http"
    }

}
