package com.sphereon.openid.fed.wallet

import com.sphereon.di.session.SessionComponent
import com.sphereon.di.session.SessionInstance
import com.sphereon.di.session.SessionScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Extension function to access FederationWalletClient from a SessionInstance.
 *
 * Usage:
 * ```
 * val sessionInstance = contextInstance.sessionContextManager.createOrGetFromId("session-id")
 * val walletClient = sessionInstance.asFederationWalletComponent().federationWalletClient
 * ```
 */
fun SessionInstance.asFederationWalletComponent(): FederationWalletSessionComponent =
    this.component as FederationWalletSessionComponent

/**
 * Extension function to access FederationWalletClient from a SessionComponent.
 */
fun SessionComponent.asFederationWalletComponent(): FederationWalletSessionComponent =
    this as FederationWalletSessionComponent

/**
 * Session-scoped component interface that provides access to FederationWalletClient.
 *
 * This interface is contributed to the SessionScope and merged into all
 * session components, allowing access to FederationWalletClient from any session
 * created by the context manager.
 */
@SingleIn(SessionScope::class)
@ContributesTo(SessionScope::class)
interface FederationWalletSessionComponent {
    val federationWalletClient: FederationWalletClient
}
