package com.sphereon.openid.fed.server.federation.api.http

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.CommandBackedHttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommandRegistry
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.OpenApiHints
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey

/**
 * HTTP Adapter for the OpenID Federation Server API.
 *
 * This adapter resolves the selected federation endpoint lazily through the IDK
 * command registry and provides the HTTP routing layer.
 *
 * All endpoints are mounted at the root path (no base path prefix).
 * Endpoints are available both at root level (for default account) and
 * under /{username} for account-specific access.
 *
 * The map key must match [ID] so the dispatcher can resolve the selected adapter.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpAdapter>())
@StringKey(FederationHttpAdapter.ID)
class FederationHttpAdapter(
    execution: SessionExecution,
    endpointCommandRegistry: HttpEndpointCommandRegistry,
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    ),
    endpointCommandRegistry = endpointCommandRegistry,
) {
    companion object {
        /** CommandId-compatible adapter id (module.service.command). */
        const val ID = "fed.server.http"
    }

    override val openApiHints: OpenApiHints = OpenApiHints(
        tags = setOf(
            "federation",
            "trust-marks",
            "keys",
            "resolution"
        ),
        operationIdPrefix = "federation"
    )
}
