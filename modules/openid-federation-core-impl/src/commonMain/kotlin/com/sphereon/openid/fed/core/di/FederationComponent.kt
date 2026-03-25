package com.sphereon.openid.fed.core.di

import com.sphereon.openid.fed.core.logging.FederationLogService
import com.sphereon.openid.fed.core.logging.FederationLogServiceFactory

/**
 * Federation Logging Helpers.
 *
 * This file provides logging helper interfaces and extension properties for
 * federation services. It does NOT provide a shared DI graph.
 *
 * ## Why No Shared FederationAppGraph?
 *
 * Each server (admin-server, federation-server) and consumer application defines
 * its own DI graph hierarchy because:
 * - Different servers have different service dependencies
 * - Consumers may need only a subset of services
 * - Lifecycle and scope requirements vary by deployment
 *
 * ## Server Graphs (defined in their respective modules):
 * - `AdminServerAppGraph` - admin-server module
 * - `FederationServerAppGraph` - federation-server module
 *
 * ## Usage of Logging Helpers:
 *
 * ```kotlin
 * // In your graph, implement HasFederationLogging
 * @DependencyGraph(SessionScope::class)
 * abstract class MySessionGraph : HasFederationLogging {
 *     // Get domain-specific loggers via extension properties
 *     val trustChainLog = trustChainLogger
 *     val entityConfigLog = entityConfigLogger
 * }
 * ```
 */

/**
 * Interface for components that want to access the log service factory.
 */
interface HasFederationLogging {
    val logServiceFactory: FederationLogServiceFactory

    /**
     * Create a logger with a specific tag
     */
    fun logger(tag: String): FederationLogService = logServiceFactory.create(tag)
}

/**
 * Extension property to create common loggers
 */
val HasFederationLogging.entityConfigLogger: FederationLogService
    get() = logServiceFactory.entityConfig()

val HasFederationLogging.trustChainLogger: FederationLogService
    get() = logServiceFactory.trustChain()

val HasFederationLogging.trustMarkLogger: FederationLogService
    get() = logServiceFactory.trustMark()

val HasFederationLogging.subordinateLogger: FederationLogService
    get() = logServiceFactory.subordinate()

val HasFederationLogging.resolutionLogger: FederationLogService
    get() = logServiceFactory.resolution()

val HasFederationLogging.jwtLogger: FederationLogService
    get() = logServiceFactory.jwt()

val HasFederationLogging.kmsLogger: FederationLogService
    get() = logServiceFactory.kms()

val HasFederationLogging.accountLogger: FederationLogService
    get() = logServiceFactory.account()
