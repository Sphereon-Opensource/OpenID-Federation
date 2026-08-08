package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.TrustMark
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Arguments for evaluating entity trust in the federation.
 */
data class EvaluateEntityTrustArgs(
    val entityIdentifier: String,
    val trustAnchors: Array<String>,
    val entityTypes: Array<String>? = null,
    val requiredTrustMarks: Array<String>? = null,
    val currentTime: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvaluateEntityTrustArgs) return false
        return entityIdentifier == other.entityIdentifier &&
                trustAnchors.contentEquals(other.trustAnchors) &&
                entityTypes.contentNullableEquals(other.entityTypes) &&
                requiredTrustMarks.contentNullableEquals(other.requiredTrustMarks) &&
                currentTime == other.currentTime
    }

    override fun hashCode(): Int {
        var result = entityIdentifier.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + (entityTypes?.contentHashCode() ?: 0)
        result = 31 * result + (requiredTrustMarks?.contentHashCode() ?: 0)
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        return result
    }
}

private fun Array<String>?.contentNullableEquals(other: Array<String>?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}

/**
 * Result of evaluating entity trust.
 */
@Serializable
data class EntityTrustResult(
    val trusted: Boolean,
    val entityIdentifier: String,
    val trustChain: List<String>,
    val effectiveMetadata: JsonObject?,
    val verifiedTrustMarks: List<TrustMark>,
    val trustAnchor: String
)

/**
 * Service interface for the EvaluateEntityTrust command.
 */
interface EvaluateEntityTrustCommandService {
    suspend fun evaluateEntityTrust(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityTypes: Array<String>? = null,
        requiredTrustMarks: Array<String>? = null,
        currentTime: Long? = null
    ): IdkResult<EntityTrustResult, FederationError>
}

/**
 * Command to evaluate whether an entity is trusted in the federation.
 *
 * This is the core command of the wallet architecture module. It:
 * 1. Resolves the trust chain from entity to trust anchor
 * 2. Cryptographically verifies the trust chain
 * 3. Applies metadata policies to derive effective metadata
 * 4. Validates trust marks
 * 5. Checks required trust marks are present
 */
interface EvaluateEntityTrustCommand :
    Command<EvaluateEntityTrustArgs, EntityTrustResult, FederationError>,
    EvaluateEntityTrustCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.evaluate-entity-trust"
    }
}
