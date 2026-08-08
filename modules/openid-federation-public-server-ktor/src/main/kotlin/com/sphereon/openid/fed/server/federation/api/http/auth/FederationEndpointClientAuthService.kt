package com.sphereon.openid.fed.server.federation.api.http.auth

import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwsUtils
import com.sphereon.di.session.SessionScope
import com.sphereon.oauth2.common.model.ClientAssertion
import com.sphereon.oauth2.common.model.ClientAuthenticationConfig
import com.sphereon.oauth2.common.model.ClientAuthenticationMethod
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses
import com.sphereon.openid.fed.services.clientauth.VerifyFederationClientAuthentication
import com.sphereon.openid.fed.services.clientauth.VerifyFederationClientAuthenticationArgs
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * OIDFed 1.1 §8.8 client authentication for federation protocol endpoints.
 *
 * - Reuses IDK OAuth2 types ([ClientAuthenticationConfig], [ClientAssertion],
 *   [ClientAuthenticationMethod]) for the credential envelope.
 * - Verifies via [VerifyFederationClientAuthentication], which resolves participants through
 *   [com.sphereon.openid.fed.services.clientauth.FederationParticipatingEntityResolver]
 *   (federation ClientRegistry analogue) instead of OAuth AS ClientRegistry.
 */
@Inject
@SingleIn(SessionScope::class)
class FederationEndpointClientAuthService(
    private val configBinder: OidfConfigBinder,
    private val verifyClientAuth: VerifyFederationClientAuthentication,
) {
    /**
     * @return `null` when authentication succeeds or is not required; otherwise a §8.9 error response.
     */
    suspend fun enforce(
        endpoint: FederationEndpointKind,
        methodIsPost: Boolean,
        formOrQueryParams: Map<String, String>,
        hostTenantId: String,
        audienceEntityId: String,
    ): GenericHttpResponse? {
        val fed = configBinder.getFederationConfig()
        val methods = fed.endpointAuthMethods.forEndpoint(endpoint)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(ClientAuthenticationMethod.NONE.value) }

        val allowsNone = methods.contains(ClientAuthenticationMethod.NONE.value)
        val allowsPrivateKeyJwt = methods.contains(ClientAuthenticationMethod.PRIVATE_KEY_JWT.value)

        val assertionJwt = formOrQueryParams["client_assertion"]
        val assertionType = formOrQueryParams["client_assertion_type"]
        val hasAssertion = !assertionJwt.isNullOrBlank()

        if (!hasAssertion) {
            return if (allowsNone) {
                null
            } else if (!methodIsPost) {
                FederationErrorResponses.invalidRequest(
                    "Client authentication is required at this endpoint; use HTTP POST with private_key_jwt (OIDFed §8.8)"
                )
            } else {
                FederationErrorResponses.invalidClient(
                    "Missing client_assertion; endpoint requires private_key_jwt (OIDFed §8.8)"
                )
            }
        }

        if (!allowsPrivateKeyJwt) {
            return FederationErrorResponses.invalidClient(
                "private_key_jwt is not accepted at this endpoint (configured methods: ${methods.joinToString()})"
            )
        }

        if (!methodIsPost) {
            return FederationErrorResponses.invalidRequest(
                "When client authentication is used, the request MUST use HTTP POST (OIDFed §8.8)"
            )
        }

        if (assertionType != JWT_BEARER_ASSERTION_TYPE) {
            return FederationErrorResponses.invalidClient(
                "client_assertion_type must be $JWT_BEARER_ASSERTION_TYPE"
            )
        }

        val clientIdFromBody = formOrQueryParams["client_id"]
        val clientIdHint = clientIdFromBody ?: extractSubFromJwt(assertionJwt!!)
            ?: return FederationErrorResponses.invalidClient(
                "Unable to determine client identity from client_id or assertion sub"
            )

        // Build IDK PrivateKeyJwt envelope (same shape as OAuth AS token endpoint)
        val authConfig = ClientAuthenticationConfig.PrivateKeyJwt(
            ClientAssertion(
                clientId = clientIdHint,
                assertionType = assertionType!!,
                assertion = assertionJwt,
            )
        )

        val result = verifyClientAuth.verify(
            VerifyFederationClientAuthenticationArgs(
                assertionJwt = authConfig.assertion.assertion,
                clientEntityId = authConfig.assertion.clientId,
                audienceEntityId = audienceEntityId,
                hostTenantId = hostTenantId,
                hostEntityId = audienceEntityId,
                allowedSigningAlgs = fed.endpointAuthSigningAlgs,
                membershipPolicy = fed.endpointAuthMembershipPolicy,
                trustAnchors = fed.endpointAuthTrustAnchors,
            )
        )

        return if (result.isOk) {
            null
        } else {
            FederationErrorResponses.fromServiceError(result.error)
        }
    }

    private fun extractSubFromJwt(jwt: String): String? =
        try {
            val general = JwsUtils.compactToGeneral(JwsCompact(jwt))
            val payload = JwsUtils.decodeBase64UrlToJson(general.payload)
            payload["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    companion object {
        const val JWT_BEARER_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    }
}
