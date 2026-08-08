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
 * - Patterns that look like absolute URIs (contain `://`) are matched as URI-prefix constraints
 *   for backwards compatibility with deployments that published full URI namespaces.
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

        // Full-URI namespace (non-RFC5280 extension used by some deployments)
        if (trimmedPattern.contains("://")) {
            return matchUriPrefix(entityIdentifier, trimmedPattern)
        }

        val host = extractHost(entityIdentifier) ?: return false
        return matchHostConstraint(host.lowercase(), trimmedPattern.lowercase())
    }

    private fun matchUriPrefix(identifier: String, pattern: String): Boolean {
        // Normalize trailing slash on pattern for prefix match of path namespaces
        val p = pattern.trimEnd('/')
        val id = identifier.trimEnd('/')
        return id == p || id.startsWith("$p/") || id.startsWith(p)
    }

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
        // host[:port]/path
        val hostPort = withoutScheme.substringBefore('/')
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
