package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.log.Log

/**
 * Object containing constants for the HTTP resolver module.
 *
 * Provides logging capabilities for the HTTP resolver functionality,
 * using a predefined namespace for consistent log tagging.
 */
object HttpResolverConst {
    private const val LOG_NAMESPACE = "sphereon:oidf:http:resolver"
    val LOG = Log.app().withTag(LOG_NAMESPACE)
}