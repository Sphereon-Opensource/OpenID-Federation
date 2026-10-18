package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeDefinition
import kotlinx.serialization.json.Json

/**
 * Validation and storage format of Trust Mark type definitions, shared by every command that accepts one.
 */
object TrustMarkTypeDefinitions {
    const val NAME_MAX_LENGTH = 200
    const val DESCRIPTION_MAX_LENGTH = 4000
    const val LIFETIME_MIN_SECONDS = 60L
    const val LIFETIME_MAX_SECONDS = 63_072_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Checks [definition] and returns it normalized: the name and the entity types trimmed and an absent entity type
     * list made empty. The first violation is returned as an [InvalidRequestError]. `extensions` is opaque and is
     * returned unchanged.
     */
    fun validate(definition: TrustMarkTypeDefinition): FederationResult<TrustMarkTypeDefinition> {
        val name = definition.name.trim()
        if (name.isEmpty()) return invalid("name must not be blank")
        if (name.length > NAME_MAX_LENGTH) return invalid("name must be at most $NAME_MAX_LENGTH characters")
        val description = definition.description
        if (description != null && description.length > DESCRIPTION_MAX_LENGTH) {
            return invalid("description must be at most $DESCRIPTION_MAX_LENGTH characters")
        }
        definition.ref?.let { if (!isAbsoluteHttpsUri(it)) return invalid("ref must be an absolute https URI") }
        definition.logoUri?.let { if (!isAbsoluteHttpsUri(it)) return invalid("logoUri must be an absolute https URI") }
        definition.lifetimeSeconds?.let {
            if (it !in LIFETIME_MIN_SECONDS..LIFETIME_MAX_SECONDS) {
                return invalid("lifetimeSeconds must be between $LIFETIME_MIN_SECONDS and $LIFETIME_MAX_SECONDS")
            }
        }
        val entityTypes = definition.applicableEntityTypes.orEmpty().map { it.trim() }
        if (entityTypes.any { it.isEmpty() }) return invalid("applicableEntityTypes must not contain blank values")
        if (entityTypes.toSet().size != entityTypes.size) return invalid("applicableEntityTypes must not contain duplicates")
        return IdkResult.ok(definition.copy(name = name, applicableEntityTypes = entityTypes))
    }

    /** The JSON text stored for [definition]. */
    fun encode(definition: TrustMarkTypeDefinition): String =
        json.encodeToString(TrustMarkTypeDefinition.serializer(), definition)

    /** Reads a stored definition; null when none was stored. */
    fun decode(stored: String?): TrustMarkTypeDefinition? =
        stored?.let { json.decodeFromString(TrustMarkTypeDefinition.serializer(), it) }

    private fun invalid(reason: String): FederationResult<TrustMarkTypeDefinition> =
        federationErr(InvalidRequestError("Invalid trust mark type definition: $reason"))

    private val HTTPS_URI = Regex("^https://[^/?#@\\s]+(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)

    private fun isAbsoluteHttpsUri(value: String): Boolean =
        value.none { it.isWhitespace() || it.isISOControl() } && HTTPS_URI.matches(value)
}
