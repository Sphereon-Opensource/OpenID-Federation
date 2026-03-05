package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/**
 * Arguments for resolving DCQL trusted authorities.
 */
data class ResolveDcqlTrustedAuthoritiesArgs(
    val credentialIssuerIdentifier: String,
    val trustedAuthorities: List<String>,
    val currentTime: Long? = null
)

/**
 * Result of DCQL trusted authority resolution.
 */
@Serializable
data class DcqlTrustResult(
    val trusted: Boolean,
    val matchedAuthority: String?,
    val entityTrustResult: EntityTrustResult?
)

/**
 * Service interface for the ResolveDcqlTrustedAuthorities command.
 */
interface ResolveDcqlTrustedAuthoritiesCommandService {
    suspend fun resolveDcqlTrustedAuthorities(
        credentialIssuerIdentifier: String,
        trustedAuthorities: List<String>,
        currentTime: Long? = null
    ): IdkResult<DcqlTrustResult, FederationError>
}

/**
 * Command to evaluate whether a credential issuer is trusted by any of the
 * DCQL trusted authorities of type "openid_federation".
 *
 * Iterates through the trusted authorities (federation trust anchors) and
 * attempts to evaluate entity trust for the credential issuer using each one.
 * Returns success on the first matching authority.
 */
interface ResolveDcqlTrustedAuthoritiesCommand :
    Command<ResolveDcqlTrustedAuthoritiesArgs, DcqlTrustResult, FederationError>,
    ResolveDcqlTrustedAuthoritiesCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.resolve-dcql-trusted-authorities"
    }
}
