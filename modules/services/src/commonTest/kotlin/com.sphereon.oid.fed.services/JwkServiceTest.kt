package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.persistence.models.JwkQueries
import com.sphereon.oid.fed.services.mappers.account.toDTO
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class JwkServiceTest {
    private lateinit var jwkService: JwkService
    private lateinit var jwkQueries: JwkQueries
    private lateinit var testAccount: Account
    private val cryptoProvider = EcDSACryptoProvider()

    // Use default value until we can generate one
    private var generatedKid: String = "test-kid"

    protected val testDispatcher = StandardTestDispatcher()
    protected val testScope = TestScope(testDispatcher)

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_KEY = """{"kid":"test-kid","kty":"EC","use":"sig","crv":"P-256"}"""
    }

    @BeforeTest
    fun setup() {
        jwkQueries = mockk(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.jwkQueries } returns jwkQueries
        jwkService = JwkService(cryptoProvider)
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
    fun `create key succeeds with valid input`() = testScope.runTest {
        // First generate a real key using the crypto provider
        val generatedKey = cryptoProvider.generateKeyAsync()
        generatedKid = generatedKey.kid ?: "test-kid"

        val jwkWithPrivateKey = JwkWithPrivateKey(
            kid = generatedKid,
            kty = "EC",
            use = "sig"
        )

        val returnedJwk = Jwk(
            id = 1,
            account_id = testAccount.id,
            kid = generatedKid,
            key = TEST_KEY.replace("test-kid", generatedKid),
            created_at = FIXED_TIMESTAMP,
            revoked_at = null,
            revoked_reason = null
        )

        // Mock JwkQueries.create to return a valid Jwk
        every { jwkQueries.create(any(), any(), any()) } returns mockk {
            every { executeAsOne() } returns returnedJwk
        }

        val result = jwkService.createKey(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(generatedKid, result.kid)
        verify { jwkQueries.create(testAccount.id, any(), any()) }
    }

    @Test
    fun `get keys returns all keys for account`() {
        val jwks = listOf(
            Jwk(
                1,
                testAccount.id,
                generatedKid,
                TEST_KEY.replace("test-kid", generatedKid),
                FIXED_TIMESTAMP,
                null,
                null
            ),
            Jwk(2, testAccount.id, "kid2", TEST_KEY.replace("test-kid", "kid2"), FIXED_TIMESTAMP, null, null)
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
            kid = generatedKid,
            key = TEST_KEY.replace("test-kid", generatedKid),
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
        assertEquals(generatedKid, result.kid)
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
            kid = generatedKid,
            key = TEST_KEY.replace("test-kid", generatedKid),
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
    fun `get federation historical keys jwt succeeds`() = testScope.runTest {
        // First generate a real key using the crypto provider
        val generatedKey = cryptoProvider.generateKeyAsync()
        generatedKid = generatedKey.kid ?: "test-kid"

        val accountService = mockk<AccountService>()
        val jwk = Jwk(
            id = 1,
            account_id = testAccount.id,
            kid = generatedKid,
            key = TEST_KEY.replace("test-kid", generatedKid),
            created_at = FIXED_TIMESTAMP,
            revoked_at = null,
            revoked_reason = null
        )

        val jwks = listOf(jwk)

        every { jwkQueries.findByAccountId(testAccount.id).executeAsList() } returns jwks
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns "test-identifier"

        val result = jwkService.getFederationHistoricalKeysJwt(testAccount.toDTO(), accountService)

        assertNotNull(result)
        verify { jwkQueries.findByAccountId(testAccount.id) }
        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
    }

    @Test
    fun `get federation historical keys jwt fails when no keys exist`() = testScope.runTest {
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
