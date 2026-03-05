package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.wallet.policy.DiipProfileValidator
import kotlinx.serialization.Serializable

/**
 * Arguments for validating federation entity metadata against the DIIP profile.
 */
data class ValidateFederationEntityMetadataArgs(
    val entityIdentifier: String,
    val trustAnchors: Array<String>,
    val entityType: String? = null,
    val currentTime: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidateFederationEntityMetadataArgs) return false
        return entityIdentifier == other.entityIdentifier &&
                trustAnchors.contentEquals(other.trustAnchors) &&
                entityType == other.entityType &&
                currentTime == other.currentTime
    }

    override fun hashCode(): Int {
        var result = entityIdentifier.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + (entityType?.hashCode() ?: 0)
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of DIIP profile metadata validation.
 */
@Serializable
data class FederationEntityMetadataValidationResult(
    val valid: Boolean,
    val entityIdentifier: String,
    val validations: List<@Serializable DiipProfileValidator.DiipValidation>,
    val entityTrustResult: EntityTrustResult
)

/**
 * Service interface for the ValidateFederationEntityMetadata command.
 */
interface ValidateFederationEntityMetadataCommandService {
    suspend fun validateFederationEntityMetadata(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityType: String? = null,
        currentTime: Long? = null
    ): IdkResult<FederationEntityMetadataValidationResult, FederationError>
}

/**
 * Validate that a federation entity's metadata conforms to the DIIP profile.
 *
 * Evaluates entity trust first, then checks DIIP-specific metadata requirements:
 * - federation_entity.display_name present
 * - For issuers: openid_credential_issuer present, credential_issuer matches entity ID, vc_issuer.jwks has signing keys
 * - For verifiers: openid_credential_verifier present
 */
interface ValidateFederationEntityMetadataCommand :
    Command<ValidateFederationEntityMetadataArgs, FederationEntityMetadataValidationResult, FederationError>,
    ValidateFederationEntityMetadataCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.validate-federation-entity-metadata"
    }
}
