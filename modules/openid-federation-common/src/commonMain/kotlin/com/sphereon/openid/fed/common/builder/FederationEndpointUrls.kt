package com.sphereon.openid.fed.common.builder

/**
 * Canonical federation endpoint URL construction from an Entity Identifier.
 *
 * Used both when publishing `federation_entity` metadata and when setting Subordinate
 * Statement `source_endpoint`, so they always agree (OIDFed 1.1 §3.1.3 / §5.1.1 / §8.1).
 */
object FederationEndpointUrls {
    fun base(entityIdentifier: String): String = entityIdentifier.trimEnd('/')

    fun join(entityIdentifier: String, path: String): String =
        "${base(entityIdentifier)}/${path.trimStart('/')}"

    fun fetch(entityIdentifier: String): String = join(entityIdentifier, "fetch")

    fun list(entityIdentifier: String): String = join(entityIdentifier, "list")

    fun resolve(entityIdentifier: String): String = join(entityIdentifier, "resolve")

    fun trustMarkStatus(entityIdentifier: String): String = join(entityIdentifier, "trust-mark-status")

    fun trustMarkList(entityIdentifier: String): String = join(entityIdentifier, "trust-mark-list")

    fun trustMark(entityIdentifier: String): String = join(entityIdentifier, "trust-mark")

    fun historicalKeys(entityIdentifier: String): String = join(entityIdentifier, "historical-keys")
}
