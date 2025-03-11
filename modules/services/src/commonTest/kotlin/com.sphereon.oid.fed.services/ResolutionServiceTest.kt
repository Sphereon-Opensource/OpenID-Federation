package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.client.FederationClient
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.BaseStatementJwks
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.openapi.models.TrustMarkValidationResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResolutionServiceTest {
    private lateinit var resolutionService: ResolutionService
    private lateinit var accountService: AccountService
    private lateinit var federationClient: FederationClient

    companion object {
        private const val SUBJECT = "https://example.org"
        private const val TRUST_ANCHOR = "https://trustanchor.example.org"
        private const val ACCOUNT_IDENTIFIER = "https://issuer.example.org"
        private const val TRUST_MARK_ID = "https://trustmark.example.org/example-trustmark"
        private const val TRUST_MARK_VALUE = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImV4YW1wbGUta2V5LWlkIn0..."
    }

    @BeforeTest
    fun setup() {
        accountService = mockk()
        federationClient = mockk()

        mockkConstructor(FederationClient::class)

        coEvery { anyConstructed<FederationClient>().entityConfigurationStatementGet(any()) } returns createTestEntityConfigurationStatement()
        coEvery {
            anyConstructed<FederationClient>().trustChainResolve(
                any(),
                any()
            )
        } returns createSuccessfulTrustChainResolution()
        coEvery {
            anyConstructed<FederationClient>().trustMarksVerify(
                any(),
                any()
            )
        } returns TrustMarkValidationResponse(isValid = false, errorMessage = null)

        resolutionService = ResolutionService(accountService)
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkConstructor(FederationClient::class)
    }

    @Test
    fun `resolveEntity throws exception when trust chain resolution fails`() = runBlocking {
        // Setup mocks
        val account = createTestAccount()
        val entityConfigStatement = createTestEntityConfigurationStatement()
        val failedTrustChainResolution = TrustChainResolveResponse(
            trustChain = null,
            errorMessage = "Failed to resolve trust chain",
        )

        coEvery { anyConstructed<FederationClient>().entityConfigurationStatementGet(SUBJECT) } returns entityConfigStatement
        coEvery {
            anyConstructed<FederationClient>().trustChainResolve(
                SUBJECT,
                arrayOf(TRUST_ANCHOR)
            )
        } returns failedTrustChainResolution

        assertFailsWith<IllegalStateException> {
            resolutionService.resolveEntity(account, SUBJECT, TRUST_ANCHOR, null)
        }

        coVerify { anyConstructed<FederationClient>().entityConfigurationStatementGet(SUBJECT) }
        coVerify { anyConstructed<FederationClient>().trustChainResolve(SUBJECT, arrayOf(TRUST_ANCHOR)) }
    }

    @Test
    fun `resolveEntity filters metadata based on entity types`() = runBlocking {
        val account = createTestAccount()
        val entityConfigStatement = createTestEntityConfigurationStatement()
        val trustChainResolution = createSuccessfulTrustChainResolution()

        every { accountService.getAccountIdentifierByAccount(account) } returns ACCOUNT_IDENTIFIER
        coEvery { federationClient.entityConfigurationStatementGet(SUBJECT) } returns entityConfigStatement
        coEvery { federationClient.trustChainResolve(SUBJECT, arrayOf(TRUST_ANCHOR)) } returns trustChainResolution

        // For trust mark verification
        coEvery {
            federationClient.entityConfigurationStatementGet(any())
        } returns createTestEntityConfigurationStatement()

        coEvery {
            federationClient.trustMarksVerify(any(), any())
        } returns TrustMarkValidationResponse(isValid = false, errorMessage = null)

        val entityTypes = arrayOf("openid_relying_party")
        val result = resolutionService.resolveEntity(account, SUBJECT, TRUST_ANCHOR, entityTypes)

        assertNotNull(result.metadata)
        assertTrue(result.metadata.toString().contains("openid_relying_party"))
        assertFalse(result.metadata.toString().contains("federation_entity"))
    }

    @Test
    fun `resolveEntity includes all metadata when entity types is null`() = runBlocking {
        val account = createTestAccount()
        val entityConfigStatement = createTestEntityConfigurationStatement()
        val trustChainResolution = createSuccessfulTrustChainResolution()

        every { accountService.getAccountIdentifierByAccount(account) } returns ACCOUNT_IDENTIFIER
        coEvery { federationClient.entityConfigurationStatementGet(SUBJECT) } returns entityConfigStatement
        coEvery { federationClient.trustChainResolve(SUBJECT, arrayOf(TRUST_ANCHOR)) } returns trustChainResolution

        coEvery {
            federationClient.entityConfigurationStatementGet(any())
        } returns createTestEntityConfigurationStatement()

        coEvery {
            federationClient.trustMarksVerify(any(), any())
        } returns TrustMarkValidationResponse(isValid = false, errorMessage = null)

        val result = resolutionService.resolveEntity(account, SUBJECT, TRUST_ANCHOR, null)

        assertNotNull(result.metadata)
        assertTrue(result.metadata.toString().contains("openid_relying_party"))
        assertTrue(result.metadata.toString().contains("federation_entity"))
    }

    private fun createTestAccount(): Account {
        return Account(
            id = 1,
            username = "testuser",
            identifier = ACCOUNT_IDENTIFIER
        )
    }

    private fun createTestEntityConfigurationStatement(): EntityConfigurationStatement {
        val metadata = buildJsonObject {
            put("openid_relying_party", buildJsonObject {
                put("redirect_uris", JsonPrimitive("https://client.example.org/callback"))
            })
            put("federation_entity", buildJsonObject {
                put("organization_name", JsonPrimitive("Example Org"))
            })
        }

        val trustMarks = arrayOf(
            TrustMark(
                id = TRUST_MARK_ID,
                trustMark = TRUST_MARK_VALUE
            )
        )

        val trustMarkIssuers = mapOf(
            TRUST_MARK_ID to arrayOf("https://issuer.example.org")
        )

        return EntityConfigurationStatement(
            sub = SUBJECT,
            iat = 1609459200,
            exp = 1609545600,
            authorityHints = arrayOf(TRUST_ANCHOR),
            metadata = metadata,
            trustMarks = trustMarks,
            iss = SUBJECT,
            crit = arrayOf("type"),
            trustMarkIssuers = trustMarkIssuers,
            trustMarkOwners = emptyMap(),
            jwks = BaseStatementJwks(
                arrayOf(
                    Jwk(
                        kid = "test-kid",
                        kty = "EC",
                        use = "sig",
                        crv = "P-256"
                    )
                )
            )
        )
    }

    private fun createSuccessfulTrustChainResolution(): TrustChainResolveResponse {
        return TrustChainResolveResponse(
            errorMessage = null,
            trustChain = arrayOf(
                "eyJhbGciOiJSUzI1NiIsImtpZCI6ImV4YW1wbGUta2V5LWlkIn0..."
            )
        )
    }
}
