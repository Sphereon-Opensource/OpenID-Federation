package com.sphereon.openid.fed.wallet.command

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidateEndpointConstraintsCommandTest {

    @Test
    fun testEndpointConstraintsResultSerialization() {
        val result = EndpointConstraintsResult(
            valid = true,
            validatedEndpoints = mapOf(
                "request_uri" to true,
                "response_uri" to true,
                "redirect_uri" to false
            )
        )

        assertEquals(true, result.valid)
        assertEquals(3, result.validatedEndpoints.size)
        assertEquals(true, result.validatedEndpoints["request_uri"])
        assertEquals(false, result.validatedEndpoints["redirect_uri"])
    }

    @Test
    fun testValidateEndpointConstraintsArgsEquality() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(JsonPrimitive("https://example.com/request")))
        ))

        val args1 = ValidateEndpointConstraintsArgs(
            entityMetadata = metadata,
            requestUri = "https://example.com/request",
            entityType = "openid_credential_verifier"
        )
        val args2 = ValidateEndpointConstraintsArgs(
            entityMetadata = metadata,
            requestUri = "https://example.com/request",
            entityType = "openid_credential_verifier"
        )

        assertEquals(args1, args2)
    }

    @Test
    fun testEntityTrustResultSerialization() {
        val result = EntityTrustResult(
            trusted = true,
            entityIdentifier = "https://issuer.example.com",
            trustChain = listOf("jwt1", "jwt2", "jwt3"),
            effectiveMetadata = JsonObject(mapOf(
                "openid_credential_issuer" to JsonObject(mapOf(
                    "credential_endpoint" to JsonPrimitive("https://issuer.example.com/credential")
                ))
            )),
            verifiedTrustMarks = emptyList(),
            trustAnchor = "https://trust-anchor.example.com"
        )

        assertTrue(result.trusted)
        assertEquals("https://issuer.example.com", result.entityIdentifier)
        assertEquals(3, result.trustChain.size)
        assertEquals("https://trust-anchor.example.com", result.trustAnchor)
    }

    @Test
    fun testDcqlTrustResultSerialization() {
        val entityTrustResult = EntityTrustResult(
            trusted = true,
            entityIdentifier = "https://issuer.example.com",
            trustChain = listOf("jwt1", "jwt2"),
            effectiveMetadata = null,
            verifiedTrustMarks = emptyList(),
            trustAnchor = "https://ta.example.com"
        )

        val result = DcqlTrustResult(
            trusted = true,
            matchedAuthority = "https://ta.example.com",
            entityTrustResult = entityTrustResult
        )

        assertTrue(result.trusted)
        assertEquals("https://ta.example.com", result.matchedAuthority)
        assertTrue(result.entityTrustResult!!.trusted)
    }

    @Test
    fun testWalletAttestationResultSerialization() {
        val entityTrustResult = EntityTrustResult(
            trusted = true,
            entityIdentifier = "https://wallet-provider.example.com",
            trustChain = listOf("jwt1", "jwt2"),
            effectiveMetadata = null,
            verifiedTrustMarks = emptyList(),
            trustAnchor = "https://ta.example.com"
        )

        val result = WalletAttestationResult(
            valid = true,
            walletProviderIdentifier = "https://wallet-provider.example.com",
            walletProviderTrustResult = entityTrustResult
        )

        assertTrue(result.valid)
        assertEquals("https://wallet-provider.example.com", result.walletProviderIdentifier)
    }

    @Test
    fun testEffectiveMetadataResultSerialization() {
        val result = EffectiveMetadataResult(
            metadata = JsonObject(mapOf(
                "credential_endpoint" to JsonPrimitive("https://issuer.example.com/credential")
            )),
            entityType = "openid_credential_issuer",
            policiesApplied = 2
        )

        assertEquals("openid_credential_issuer", result.entityType)
        assertEquals(2, result.policiesApplied)
        assertTrue(result.metadata.containsKey("credential_endpoint"))
    }

    @Test
    fun testEvaluateEntityTrustArgsEquality() {
        val args1 = EvaluateEntityTrustArgs(
            entityIdentifier = "https://entity.example.com",
            trustAnchors = arrayOf("https://ta1.example.com", "https://ta2.example.com"),
            entityTypes = arrayOf("openid_credential_issuer"),
            requiredTrustMarks = arrayOf("https://trust-mark.example.com"),
            currentTime = 1000L
        )
        val args2 = EvaluateEntityTrustArgs(
            entityIdentifier = "https://entity.example.com",
            trustAnchors = arrayOf("https://ta1.example.com", "https://ta2.example.com"),
            entityTypes = arrayOf("openid_credential_issuer"),
            requiredTrustMarks = arrayOf("https://trust-mark.example.com"),
            currentTime = 1000L
        )

        assertEquals(args1, args2)
        assertEquals(args1.hashCode(), args2.hashCode())
    }

    @Test
    fun testApplyMetadataPolicyArgsEquality() {
        val args1 = ApplyMetadataPolicyArgs(
            trustChain = arrayOf("jwt1", "jwt2"),
            entityType = "openid_credential_issuer"
        )
        val args2 = ApplyMetadataPolicyArgs(
            trustChain = arrayOf("jwt1", "jwt2"),
            entityType = "openid_credential_issuer"
        )

        assertEquals(args1, args2)
        assertEquals(args1.hashCode(), args2.hashCode())
    }

    @Test
    fun testVerifyWalletAttestationArgsEquality() {
        val args1 = VerifyWalletAttestationArgs(
            walletAttestationJwt = "jwt",
            trustAnchors = arrayOf("https://ta.example.com"),
            currentTime = 1000L
        )
        val args2 = VerifyWalletAttestationArgs(
            walletAttestationJwt = "jwt",
            trustAnchors = arrayOf("https://ta.example.com"),
            currentTime = 1000L
        )

        assertEquals(args1, args2)
        assertEquals(args1.hashCode(), args2.hashCode())
    }

    @Test
    fun testResolveDcqlTrustedAuthoritiesArgsEquality() {
        val args1 = ResolveDcqlTrustedAuthoritiesArgs(
            credentialIssuerIdentifier = "https://issuer.example.com",
            trustedAuthorities = listOf("https://ta1.example.com"),
            currentTime = 1000L
        )
        val args2 = ResolveDcqlTrustedAuthoritiesArgs(
            credentialIssuerIdentifier = "https://issuer.example.com",
            trustedAuthorities = listOf("https://ta1.example.com"),
            currentTime = 1000L
        )

        assertEquals(args1, args2)
    }
}
