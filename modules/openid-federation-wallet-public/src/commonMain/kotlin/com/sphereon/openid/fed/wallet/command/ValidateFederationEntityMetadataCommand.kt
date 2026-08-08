package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.wallet.policy.MetadataValidationCheck
import kotlinx.serialization.Serializable

/**
 * Metadata profile to validate against.
 *
 * - [WALLET]: OpenID Federation Wallet Architecture (organization_name, OpenID4VCI fields, AS, …)
 * - [DIIP]: DIIP Appendix B with dual acceptance of wallet fields
 * - [BOTH]: Run wallet then DIIP (default); entity must pass the applicable set for its type
 */
enum class MetadataProfileMode {
    WALLET,
    DIIP,
    BOTH,
}

/**
 * Arguments for validating federation entity metadata against wallet and/or DIIP profiles.
 */
data class ValidateFederationEntityMetadataArgs(
    val entityIdentifier: String,
    val trustAnchors: Array<String>,
    val entityType: String? = null,
    val currentTime: Long? = null,
    val profileMode: MetadataProfileMode = MetadataProfileMode.BOTH,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidateFederationEntityMetadataArgs) return false
        return entityIdentifier == other.entityIdentifier &&
            trustAnchors.contentEquals(other.trustAnchors) &&
            entityType == other.entityType &&
            currentTime == other.currentTime &&
            profileMode == other.profileMode
    }

    override fun hashCode(): Int {
        var result = entityIdentifier.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + (entityType?.hashCode() ?: 0)
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        result = 31 * result + profileMode.hashCode()
        return result
    }
}

/**
 * Result of federation entity metadata profile validation.
 */
@Serializable
data class FederationEntityMetadataValidationResult(
    val valid: Boolean,
    val entityIdentifier: String,
    val validations: List<MetadataValidationCheck>,
    val entityTrustResult: EntityTrustResult,
)

/**
 * Service interface for the ValidateFederationEntityMetadata command.
 */
interface ValidateFederationEntityMetadataCommandService {
    suspend fun validateFederationEntityMetadata(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityType: String? = null,
        currentTime: Long? = null,
        profileMode: MetadataProfileMode = MetadataProfileMode.BOTH,
    ): IdkResult<FederationEntityMetadataValidationResult, FederationError>
}

/**
 * Validate that a federation entity's metadata conforms to the wallet architecture
 * and/or DIIP profiles after establishing federation trust.
 *
 * Wallet profile (default): organization_name (or display_name), entity-type metadata
 * for WP / CI / CV / AS / federation_entity, OpenID4VCI credential issuer keys.
 *
 * DIIP profile: display_name (or organization_name dual), vc_issuer.jwks (or
 * openid_credential_issuer.jwks dual).
 */
interface ValidateFederationEntityMetadataCommand :
    Command<ValidateFederationEntityMetadataArgs, FederationEntityMetadataValidationResult, FederationError>,
    ValidateFederationEntityMetadataCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.validate-federation-entity-metadata"
    }
}
