package com.sphereon.openid.fed.wallet

import com.sphereon.openid.fed.core.error.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WalletErrorTypesTest {

    @Test
    fun testEntityNotTrustedError() {
        val error = EntityNotTrustedError(
            entityId = "https://entity.example.com",
            reason = "Trust chain resolution failed"
        )

        assertIs<FederationError>(error)
        assertEquals(HttpStatusCode.Forbidden, error.httpStatus)
        assertEquals("entity_not_trusted", error.errorCode)
        assertTrue(error.message.defaultMessage.contains("https://entity.example.com"))
        assertTrue(error.message.defaultMessage.contains("Trust chain resolution failed"))
    }

    @Test
    fun testEndpointConstraintViolationError() {
        val error = EndpointConstraintViolationError(
            entityId = "https://verifier.example.com",
            endpointType = "request_uri",
            actualValue = "https://evil.example.com/request",
            reason = "not pre-registered"
        )

        assertIs<FederationError>(error)
        assertEquals(HttpStatusCode.BadRequest, error.httpStatus)
        assertEquals("endpoint_constraint_violation", error.errorCode)
        assertTrue(error.message.defaultMessage.contains("request_uri"))
    }

    @Test
    fun testWalletAttestationInvalidError() {
        val error = WalletAttestationInvalidError(
            walletProviderId = "https://wallet-provider.example.com",
            reason = "Signature verification failed"
        )

        assertIs<FederationError>(error)
        assertEquals(HttpStatusCode.BadRequest, error.httpStatus)
        assertEquals("wallet_attestation_invalid", error.errorCode)
        assertTrue(error.message.defaultMessage.contains("wallet-provider.example.com"))
    }

    @Test
    fun testMetadataPolicyApplicationError() {
        val error = MetadataPolicyApplicationError(
            entityId = "https://entity.example.com",
            reason = "Conflicting policy operators"
        )

        assertIs<FederationError>(error)
        assertEquals(HttpStatusCode.BadRequest, error.httpStatus)
        assertEquals("metadata_policy_application_error", error.errorCode)
    }

    @Test
    fun testRequiredTrustMarkMissingError() {
        val error = RequiredTrustMarkMissingError(
            entityId = "https://entity.example.com",
            missingTrustMarkIds = listOf("https://tm1.example.com", "https://tm2.example.com")
        )

        assertIs<FederationError>(error)
        assertEquals(HttpStatusCode.Forbidden, error.httpStatus)
        assertEquals("required_trust_mark_missing", error.errorCode)
        assertTrue(error.message.defaultMessage.contains("tm1.example.com"))
        assertTrue(error.message.defaultMessage.contains("tm2.example.com"))
    }

    @Test
    fun testDcqlTrustAuthorityNotFoundError() {
        val error = DcqlTrustAuthorityNotFoundError(
            credentialIssuerId = "https://issuer.example.com",
            attemptedAuthorities = listOf("https://ta1.example.com", "https://ta2.example.com")
        )

        assertIs<FederationError>(error)
        assertEquals(HttpStatusCode.Forbidden, error.httpStatus)
        assertEquals("dcql_trust_authority_not_found", error.errorCode)
        assertTrue(error.message.defaultMessage.contains("issuer.example.com"))
    }
}
