package com.sphereon.oid.fed.httpResolver

import com.sphereon.oid.fed.logger.Logger

/**
 * Object containing constants for the HTTP resolver module.
 *
 * Provides logging capabilities for the HTTP resolver functionality,
 * using a predefined namespace for consistent log tagging.
 */
object HttpResolverConst {
    private const val LOG_NAMESPACE = "sphereon:oidf:http:resolver"
    val LOG = Logger.tag(LOG_NAMESPACE)
}