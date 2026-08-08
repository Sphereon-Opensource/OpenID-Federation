package com.sphereon.openid.fed.client

import com.sphereon.di.session.SessionGraph
import com.sphereon.di.session.SessionInstance
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.SingleIn

/**
 * Extension function to access FederationClient from a SessionInstance.
 *
 * Usage:
 * ```
 * val sessionInstance = contextInstance.sessionContextManager.createOrGetFromId("session-id")
 * val federationClient = sessionInstance.asFederationClientGraph().federationClient
 * ```
 */
fun SessionInstance.asFederationClientGraph(): FederationClientSessionGraph =
    this.graph as FederationClientSessionGraph

/**
 * Extension function to access FederationClient from a SessionGraph.
 */
fun SessionGraph.asFederationClientGraph(): FederationClientSessionGraph =
    this as FederationClientSessionGraph

/**
 * Session-scoped component interface that provides access to FederationClient.
 *
 * This interface is contributed to the SessionScope and merged into all
 * session components, allowing access to FederationClient from any session
 * created by the context manager.
 */
@SingleIn(SessionScope::class)
@ContributesTo(SessionScope::class)
interface FederationClientSessionGraph {
    /**
     * The FederationClient instance for this session.
     */
    val federationClient: FederationClient
}
