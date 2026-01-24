package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.ResolveResponse

/**
 * Arguments for the ResolveEntity command.
 */
data class ResolveEntityArgs(
    val account: Account,
    val sub: String,
    val trustAnchor: String,
    val entityTypes: Array<String>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ResolveEntityArgs
        if (account != other.account) return false
        if (sub != other.sub) return false
        if (trustAnchor != other.trustAnchor) return false
        if (entityTypes != null) {
            if (other.entityTypes == null) return false
            if (!entityTypes.contentEquals(other.entityTypes)) return false
        } else if (other.entityTypes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = account.hashCode()
        result = 31 * result + sub.hashCode()
        result = 31 * result + trustAnchor.hashCode()
        result = 31 * result + (entityTypes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Service interface for resolve entity operation.
 */
interface ResolveEntityCommandService {
    /**
     * Resolves and retrieves information for a specified entity based on the given parameters,
     * including trust chain resolution, metadata filtering, and trust mark verification.
     *
     * @param account The account information of the user initiating the resolution.
     * @param sub The entity identifier (subject) whose information is to be resolved.
     * @param trustAnchor The trust anchor against which the entity's trust chain is validated.
     * @param entityTypes Array of entity types used for filtering metadata; can be null to include all types.
     * @return IdkResult containing the ResolveResponse or an error.
     */
    suspend fun resolveEntity(
        account: Account,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): IdkResult<ResolveResponse, FederationError>
}

/**
 * Command to resolve an entity and retrieve its information including trust chain,
 * metadata, and trust marks.
 */
interface ResolveEntityCommand : Command<ResolveEntityArgs, ResolveResponse, FederationError>, ResolveEntityCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.resolution.resolve-entity"
    }
}
