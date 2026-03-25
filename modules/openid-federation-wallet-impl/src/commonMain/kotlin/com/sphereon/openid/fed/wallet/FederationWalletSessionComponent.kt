package com.sphereon.openid.fed.wallet

import com.sphereon.di.session.SessionGraph
import com.sphereon.di.session.SessionInstance
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.SingleIn

/**
 * Extension function to access FederationWalletClient from a SessionInstance.
 *
 * Usage:
 * ```
 * val sessionInstance = contextInstance.sessionContextManager.createOrGetFromId("session-id")
 * val walletClient = sessionInstance.asFederationWalletGraph().federationWalletClient
 * ```
 */
fun SessionInstance.asFederationWalletGraph(): FederationWalletSessionGraph =
    this.graph as FederationWalletSessionGraph

/**
 * Extension function to access FederationWalletClient from a SessionGraph.
 */
fun SessionGraph.asFederationWalletGraph(): FederationWalletSessionGraph =
    this as FederationWalletSessionGraph

/**
 * Session-scoped component interface that provides access to FederationWalletClient.
 *
 * This interface is contributed to the SessionScope and merged into all
 * session components, allowing access to FederationWalletClient from any session
 * created by the context manager.
 */
@SingleIn(SessionScope::class)
@ContributesTo(SessionScope::class)
interface FederationWalletSessionGraph {
    val federationWalletClient: FederationWalletClient
}
