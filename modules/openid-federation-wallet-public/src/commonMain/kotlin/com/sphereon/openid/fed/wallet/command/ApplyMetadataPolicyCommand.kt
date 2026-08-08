package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Arguments for applying metadata policy.
 */
data class ApplyMetadataPolicyArgs(
    val trustChain: Array<String>,
    val entityType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApplyMetadataPolicyArgs) return false
        return trustChain.contentEquals(other.trustChain) && entityType == other.entityType
    }

    override fun hashCode(): Int {
        var result = trustChain.contentHashCode()
        result = 31 * result + (entityType?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of applying metadata policies.
 */
@Serializable
data class EffectiveMetadataResult(
    val metadata: JsonObject,
    val entityType: String?,
    val policiesApplied: Int
)

/**
 * Service interface for the ApplyMetadataPolicy command.
 */
interface ApplyMetadataPolicyCommandService {
    suspend fun applyMetadataPolicy(
        trustChain: Array<String>,
        entityType: String? = null
    ): IdkResult<EffectiveMetadataResult, FederationError>
}

/**
 * Command to apply metadata policies from a verified trust chain to derive effective metadata.
 *
 * Walks the trust chain from trust anchor to leaf, collecting and merging metadata policies,
 * then applies the combined policy to the leaf entity's metadata.
 */
interface ApplyMetadataPolicyCommand :
    Command<ApplyMetadataPolicyArgs, EffectiveMetadataResult, FederationError>,
    ApplyMetadataPolicyCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.apply-metadata-policy"
    }
}
