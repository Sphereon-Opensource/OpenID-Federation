package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.persistence.models.JwkQueries
import com.sphereon.oid.fed.services.mappers.account.toDTO
import io.mockk.*
import java.time.LocalDateTime
import kotlin.test.*

class JwkServiceTest {
    private lateinit var jwkService: JwkService
    private lateinit var kmsClient: KmsClient
    private lateinit var jwkQueries: JwkQueries
    private lateinit var testAccount: Account

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_KID = "test-kid"
        private const val TEST_KEY = """{"kid":"test-kid","kty":"RSA","use":"sig"}"""
    }

    @BeforeTest
    fun setup() {
        kmsClient = mockk()
        jwkQueries = mockk(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.jwkQueries } returns jwkQueries
        jwkService = JwkService(kmsClient)
        testAccount = Account(
            id = 1,
            username = "testUser",
            identifier = "test-identifier",
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun `create key succeeds with valid input`() {
        val jwkWithPrivateKey = JwkWithPrivateKey(
            kid = TEST_KID,
            kty = "RSA",
            use = "sig"
        )
        val jwk = Jwk(
            id = 1,
            account_id = testAccount.id,
            kid = TEST_KID,
            key = TEST_KEY,
            created_at = FIXED_TIMESTAMP,
            revoked_at = null,
            revoked_reason = null
        )

        every { kmsClient.generateKeyPair() } returns jwkWithPrivateKey
        every { jwkQueries.create(testAccount.id, TEST_KID, any()) } returns mockk {
            every { executeAsOne() } returns jwk
        }

        val result = jwkService.createKey(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(TEST_KID, result.kid)
        verify { kmsClient.generateKeyPair() }
        verify { jwkQueries.create(testAccount.id, TEST_KID, any()) }
    }

    @Test
    fun `get keys returns all keys for account`() {
        val jwks = listOf(
            Jwk(1, testAccount.id, "kid1", TEST_KEY, FIXED_TIMESTAMP, null, null),
            Jwk(2, testAccount.id, "kid2", TEST_KEY, FIXED_TIMESTAMP, null, null)
        )

        every { jwkQueries.findByAccountId(testAccount.id).executeAsList() } returns jwks

        val result = jwkService.getKeys(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        verify { jwkQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `revoke key succeeds for valid key`() {
        val keyId = 1
        val reason = "Test revocation"
        val jwk = Jwk(
            id = keyId,
            account_id = testAccount.id,
            kid = TEST_KID,
            key = TEST_KEY,
            created_at = FIXED_TIMESTAMP,
            revoked_at = FIXED_TIMESTAMP,
            revoked_reason = reason
        )

        every { jwkQueries.findById(keyId) } returns mockk {
            every { executeAsOne() } returns jwk
        }
        every { jwkQueries.revoke(reason, keyId) } returns mockk {
            every { executeAsOne() } returns jwk
        }

        val result = jwkService.revokeKey(testAccount.toDTO(), keyId, reason)

        assertNotNull(result)
        assertEquals(TEST_KID, result.kid)
        verify { jwkQueries.findById(keyId) }
        verify { jwkQueries.revoke(reason, keyId) }
    }

    @Test
    fun `revoke key fails for key from different account`() {
        val keyId = 1
        val differentAccountId = 2
        val jwk = Jwk(
            id = keyId,
            account_id = differentAccountId,
            kid = TEST_KID,
            key = TEST_KEY,
            created_at = FIXED_TIMESTAMP,
            revoked_at = null,
            revoked_reason = null
        )

        every { jwkQueries.findById(keyId) } returns mockk {
            every { executeAsOne() } returns jwk
        }

        assertFailsWith<NotFoundException> {
            jwkService.revokeKey(testAccount.toDTO(), keyId, "Test reason")
        }
        verify { jwkQueries.findById(keyId) }
        verify(exactly = 0) { jwkQueries.revoke(any(), any()) }
    }

    @Test
    fun `get federation historical keys jwt succeeds`() {
        val accountService = mockk<AccountService>()
        val jwks = listOf(
            Jwk(1, testAccount.id, TEST_KID, TEST_KEY, FIXED_TIMESTAMP, null, null)
        )
        val expectedJwt = "test.jwt.token"

        every { jwkQueries.findByAccountId(testAccount.id).executeAsList() } returns jwks
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns "test-identifier"
        every { kmsClient.sign(any(), any(), TEST_KID) } returns expectedJwt

        val result = jwkService.getFederationHistoricalKeysJwt(testAccount.toDTO(), accountService)

        assertEquals(expectedJwt, result)
        verify { jwkQueries.findByAccountId(testAccount.id) }
        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { kmsClient.sign(any(), any(), TEST_KID) }
    }

    @Test
    fun `get federation historical keys jwt fails when no keys exist`() {
        val accountService = mockk<AccountService>()

        every { jwkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns "test-identifier"

        assertFailsWith<IllegalArgumentException> {
            jwkService.getFederationHistoricalKeysJwt(testAccount.toDTO(), accountService)
        }
        verify { jwkQueries.findByAccountId(testAccount.id) }
        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
    }
}