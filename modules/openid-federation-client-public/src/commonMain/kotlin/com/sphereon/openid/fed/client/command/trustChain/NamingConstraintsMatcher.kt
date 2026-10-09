package com.sphereon.openid.fed.client.command.trustChain

/**
 * RFC 5280 §4.2.1.10 domain-name constraints as used by OpenID Federation 1.1 §6.2.2
 * for `naming_constraints` on Entity Identifiers.
 *
 * Constraints apply to the **host** part of the Entity Identifier URI (not the full URL path).
 *
 * - Pattern starting with `.` (e.g. `.example.com`): matches one or more DNS labels under that
 *   domain (`host.example.com`, `a.b.example.com`) but **not** the bare domain `example.com`.
 * - Pattern without leading `.` (e.g. `host.example.com`): matches that host exactly.
 * - A constraint MUST be a fully qualified domain name (§6.2.2); anything else never matches and is rejected when the
 *   constraints are parsed.
 */
object NamingConstraintsMatcher {

    /**
     * Returns true if [entityIdentifier] is allowed by [permitted] and not blocked by [excluded].
     * Empty/null permitted means all hosts are permitted (subject to excluded).
     * Matching any excluded pattern always fails.
     */
    fun isAllowed(
        entityIdentifier: String,
        permitted: List<String>?,
        excluded: List<String>?
    ): Boolean {
        if (!excluded.isNullOrEmpty() && excluded.any { matches(entityIdentifier, it) }) {
            return false
        }
        if (permitted.isNullOrEmpty()) {
            return true
        }
        return permitted.any { matches(entityIdentifier, it) }
    }

    /**
     * Whether [entityIdentifier] matches a single naming constraint [pattern].
     */
    fun matches(entityIdentifier: String, pattern: String): Boolean {
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.isEmpty()) return false

        if (!isDomainNameConstraint(trimmedPattern)) return false

        val host = extractHost(entityIdentifier) ?: return false
        return matchHostConstraint(host.lowercase(), trimmedPattern.lowercase())
    }

    private val DOMAIN_LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    private val DOMAIN_NAME_CONSTRAINT = Regex("""^\.?$DOMAIN_LABEL(?:\.$DOMAIN_LABEL)*$""")

    /** A host (`host.example.com`) or a domain (`.example.com`) written as a fully qualified domain name. */
    fun isDomainNameConstraint(pattern: String): Boolean = DOMAIN_NAME_CONSTRAINT.matches(pattern)

    /**
     * Extract host from an Entity Identifier. Supports `https://host[:port]/path` and bare hosts.
     */
    fun extractHost(entityIdentifier: String): String? {
        val value = entityIdentifier.trim()
        if (value.isEmpty()) return null

        val withoutScheme = when {
            value.contains("://") -> value.substringAfter("://")
            else -> value
        }
        // [userinfo@]host[:port]/path; the constraint applies to the host only
        val hostPort = withoutScheme.substringBefore('/').substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        // Strip IPv6 brackets if present, then port
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            // hostname or IPv4 — port is after last ':' only if it looks like port
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0 && hostPort.substring(colon + 1).all { it.isDigit() }) {
                hostPort.substring(0, colon)
            } else {
                hostPort
            }
        }
        return host.takeIf { it.isNotEmpty() }
    }

    private fun matchHostConstraint(host: String, pattern: String): Boolean {
        return if (pattern.startsWith(".")) {
            // Domain constraint: one or more labels under the suffix
            // ".example.com" matches "a.example.com" and "a.b.example.com", not "example.com"
            host.endsWith(pattern) && host.length > pattern.length
        } else {
            // Host constraint: exact match
            host == pattern
        }
    }
}
