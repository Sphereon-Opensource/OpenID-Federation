package com.sphereon.oid.fed.client.services.trustMarkService

import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.helpers.findKeyInJwks
import com.sphereon.oid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementService
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwt
import com.sphereon.oid.fed.openapi.models.TrustMarkOwner
import com.sphereon.oid.fed.openapi.models.TrustMarkValidationResponse
import kotlinx.serialization.json.jsonPrimitive

private val logger = TrustMarkServiceConst.LOG

/**
 * Service for validating Trust Marks according to the OpenID Federation specification.
 */
class TrustMarkService(
    private val context: FederationContext,
    private val entityConfigurationStatementService: EntityConfigurationStatementService = EntityConfigurationStatementService(
        context
    )
) {
    /**
     * Validates a Trust Mark according to the OpenID Federation specification.
     *
     * @param trustMark The Trust Mark to validate
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration
     * @param currentTime Optional timestamp for validation (defaults to current epoch time in seconds)s
     * @return TrustMarkValidationResponse containing the validation result and any error message
     */
    suspend fun validateTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long? = null
    ): TrustMarkValidationResponse {
        logger.debug("Starting Trust Mark validation")
        val timeToUse = currentTime ?: getCurrentEpochTimeSeconds()

        try {
            // 1. Decode the Trust Mark JWT
            val decodedTrustMark = decodeJWTComponents(trustMark)

            // 2. Check if Trust Mark has expired
            val exp = decodedTrustMark.payload["exp"]?.jsonPrimitive?.content?.toLongOrNull()
            if (exp == null || exp <= timeToUse) {
                logger.error("Trust Mark has expired")
                return TrustMarkValidationResponse(false, "Trust Mark has expired")
            }

            // 3. Get Trust Mark issuer for signature verification
            val trustMarkIssuer = decodedTrustMark.payload["iss"]?.jsonPrimitive?.content
                ?: return TrustMarkValidationResponse(false, "Trust Mark missing required issuer claim")

            // 4. Get Trust Mark identifier
            val trustMarkId = decodedTrustMark.payload["id"]?.jsonPrimitive?.content
                ?: return TrustMarkValidationResponse(false, "Trust Mark missing required 'id' claim")

            // 5. Fetch issuer's configuration and verify signature
            logger.debug("Fetching issuer configuration for signature verification")
            val issuerConfig = entityConfigurationStatementService.fetchEntityConfigurationStatement(trustMarkIssuer)
            val signingKey = issuerConfig.jwks.propertyKeys?.find { it.kid == decodedTrustMark.header.kid }
                ?: return TrustMarkValidationResponse(false, "Trust Mark signing key not found in issuer's JWKS")

            if (!context.cryptoService.verify(trustMark, signingKey)) {
                logger.error("Trust Mark signature verification failed")
                return TrustMarkValidationResponse(false, "Trust Mark signature verification failed")
            }
            logger.debug("Trust Mark signature verified successfully")

            // 6. Check if Trust Mark is recognized in Trust Anchor's configuration
            val trustMarkOwners = trustAnchorConfig.trustMarkOwners?.filterKeys { it == trustMarkId }
            if (trustMarkOwners != null) {
                logger.debug("Validating Trust Mark using trust_mark_owners claim")
                return validateWithTrustMarkOwners(trustMarkId, trustMarkOwners, decodedTrustMark)
            }

            // 7. Check if Trust Mark issuer is in Trust Anchor's trust_mark_issuers
            val trustMarkIssuers = trustAnchorConfig.trustMarkIssuers
            if (trustMarkIssuers != null) {
                logger.debug("Validating Trust Mark using trust_mark_issuers claim")
                return validateWithTrustMarkIssuers(
                    trustMarkId,
                    trustMarkIssuers,
                    decodedTrustMark
                )
            }

            // If neither trust_mark_owners nor trust_mark_issuers is present
            logger.debug("Trust Mark not recognized in federation - no trust_mark_owners or trust_mark_issuers found")
            return TrustMarkValidationResponse(
                false,
                "Trust Mark not recognized in federation - no trust_mark_owners or trust_mark_issuers found"
            )

        } catch (e: Exception) {
            logger.error("Trust Mark validation failed", e)
            return TrustMarkValidationResponse(false, "Trust Mark validation failed: ${e.message}")
        }
    }

    private suspend fun validateWithTrustMarkOwners(
        trustMarkId: String,
        trustMarkOwners: Map<String, TrustMarkOwner>,
        decodedTrustMark: Jwt
    ): TrustMarkValidationResponse {
        val ownerClaims = trustMarkOwners[trustMarkId]
            ?: return TrustMarkValidationResponse(false, "Trust Mark identifier not found in trust_mark_owners")

        // Verify delegation claim exists
        val delegation = decodedTrustMark.payload["delegation"]?.jsonPrimitive?.content
            ?: return TrustMarkValidationResponse(false, "Trust Mark missing required delegation claim")

        // Verify delegation JWT signature with owner's JWKS
        val ownerJwks = ownerClaims.jwks
            ?: return TrustMarkValidationResponse(false, "No JWKS found for Trust Mark owner")

        val decodedDelegation = decodeJWTComponents(delegation)

        val delegationKey = findKeyInJwks(
            ownerJwks.toTypedArray(),
            decodedDelegation.header.kid
        ) ?: return TrustMarkValidationResponse(false, "Delegation signing key not found in owner's JWKS")

        if (!context.cryptoService.verify(delegation, delegationKey)) {
            return TrustMarkValidationResponse(false, "Delegation signature verification failed")
        }

        // Verify delegation issuer matches owner's sub
        val ownerSub = ownerClaims.sub
            ?: return TrustMarkValidationResponse(false, "Trust Mark owner missing sub claim")

        if (decodedDelegation.payload["iss"]?.jsonPrimitive?.content != ownerSub) {
            return TrustMarkValidationResponse(false, "Delegation issuer does not match Trust Mark owner")
        }

        return TrustMarkValidationResponse(true)
    }

    private fun validateWithTrustMarkIssuers(
        trustMarkId: String,
        trustMarkIssuers: Map<String, List<String>>,
        decodedTrustMark: Jwt
    ): TrustMarkValidationResponse {
        val issuerClaims = trustMarkIssuers[trustMarkId]
            ?: return TrustMarkValidationResponse(false, "Trust Mark identifier not found in trust_mark_issuers")

        // Verify Trust Mark issuer is authorized
        val trustMarkIssuer = decodedTrustMark.payload["iss"]?.jsonPrimitive?.content
            ?: return TrustMarkValidationResponse(false, "Trust Mark missing required issuer claim")

        val isAuthorizedIssuer = issuerClaims.any { issuer ->
            issuer == trustMarkIssuer
        }

        if (!isAuthorizedIssuer) {
            return TrustMarkValidationResponse(false, "Trust Mark issuer not authorized")
        }
        // Signature has already been verified in validateTrustMark
        return TrustMarkValidationResponse(true)
    }
}
