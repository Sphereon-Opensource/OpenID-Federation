package com.sphereon.openid.fed.client.services

import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommandService
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse

/**
 * Service interface for trust mark operations (client-side).
 *
 * Provides functionality to verify trust marks according to
 * the OpenID Federation specification.
 *
 * This service aggregates all trust mark-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface TrustMarkService :
    VerifyTrustMarkCommandService {

    /**
     * Provides access to individual trust mark commands for advanced use cases
     * like composition, chaining, or extension-based processing.
     */
    val commands: Commands

    /**
     * Container interface for all trust mark-related commands.
     */
    interface Commands {
        val verifyTrustMark: VerifyTrustMarkCommand
    }

    /**
     * Verifies a Trust Mark under a federation Trust Anchor (OIDFed 1.1 §7.3).
     *
     * @param trustMark The Trust Mark JWT string to validate.
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration for the evaluating federation.
     * @param currentTime Optional timestamp for validation (defaults to current time).
     * @param subject Optional Entity Identifier that must match the Trust Mark `sub` claim.
     * @return FederationResult containing the TrustMarkValidationResponse or an error.
     *   [com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError] when the mark type
     *   is not recognized by this federation (filter out for cross-federation use).
     */
    override suspend fun verifyTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?,
        subject: String?
    ): FederationResult<TrustMarkValidationResponse>
}
