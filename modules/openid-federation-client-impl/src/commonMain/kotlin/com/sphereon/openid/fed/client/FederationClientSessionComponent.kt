package com.sphereon.openid.fed.client

import com.sphereon.di.session.SessionComponent
import com.sphereon.di.session.SessionInstance
import com.sphereon.di.session.SessionScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Extension function to access FederationClient from a SessionInstance.
 *
 * Usage:
 * ```
 * val sessionInstance = contextInstance.sessionContextManager.createOrGetFromId("session-id")
 * val federationClient = sessionInstance.asFederationClientComponent().federationClient
 * ```
 */
fun SessionInstance.asFederationClientComponent(): FederationClientSessionComponent =
    this.component as FederationClientSessionComponent

/**
 * Extension function to access FederationClient from a SessionComponent.
 */
fun SessionComponent.asFederationClientComponent(): FederationClientSessionComponent =
    this as FederationClientSessionComponent

/**
 * Session-scoped component interface that provides access to FederationClient.
 *
 * This interface is contributed to the SessionScope and merged into all
 * session components, allowing access to FederationClient from any session
 * created by the context manager.
 */
@SingleIn(SessionScope::class)
@ContributesTo(SessionScope::class)
interface FederationClientSessionComponent {
    /**
     * The FederationClient instance for this session.
     */
    val federationClient: FederationClient
}
