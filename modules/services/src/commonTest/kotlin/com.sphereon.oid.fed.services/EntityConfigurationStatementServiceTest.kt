package com.sphereon.oid.fed.services


import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.crypto.kms.ecdsa.EcDSACryptoProvider
import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.oid.fed.persistence.models.CritQueries
import com.sphereon.oid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.oid.fed.persistence.models.MetadataQueries
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.oid.fed.persistence.models.SubordinateQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkTypeQueries
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import com.sphereon.oid.fed.services.mappers.toDTO
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EntityConfigurationStatementServiceTest {
    private lateinit var statementService: EntityConfigurationStatementService
    private lateinit var accountService: AccountService
    private lateinit var jwkService: JwkService
    private lateinit var testAccount: Account
    private lateinit var accountServiceConfig: AccountServiceConfig

    // Mock queries
    private lateinit var subordinateQueries: SubordinateQueries
    private lateinit var trustMarkQueries: TrustMarkQueries
    private lateinit var authorityHintQueries: AuthorityHintQueries
    private lateinit var metadataQueries: MetadataQueries
    private lateinit var critQueries: CritQueries
    private lateinit var trustMarkTypeQueries: TrustMarkTypeQueries
    private lateinit var trustMarkIssuerQueries: TrustMarkIssuerQueries
    private lateinit var receivedTrustMarkQueries: ReceivedTrustMarkQueries
    private lateinit var entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    private lateinit var kmsProvider: IKeyManagementSystem

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_IDENTIFIER = "test-identifier"
        private const val TEST_KEY_ID = "test-key-id"
    }

    @BeforeTest
    fun setup() {
        // Initialize mocks for all dependencies
        accountService = mockk()
        jwkService = mockk()
        kmsProvider = EcDSACryptoProvider()
        accountServiceConfig = AccountServiceConfig(Constants.DEFAULT_ROOT_USERNAME)

        // Initialize all query mocks
        subordinateQueries = mockk(relaxed = true)
        trustMarkQueries = mockk(relaxed = true)
        authorityHintQueries = mockk(relaxed = true)
        metadataQueries = mockk(relaxed = true)
        critQueries = mockk(relaxed = true)
        trustMarkTypeQueries = mockk(relaxed = true)
        trustMarkIssuerQueries = mockk(relaxed = true)
        receivedTrustMarkQueries = mockk(relaxed = true)
        entityConfigurationStatementQueries = mockk(relaxed = true)

        // Mock Persistence object
        mockkObject(Persistence)
        every { Persistence.subordinateQueries } returns subordinateQueries
        every { Persistence.trustMarkQueries } returns trustMarkQueries
        every { Persistence.authorityHintQueries } returns authorityHintQueries
        every { Persistence.metadataQueries } returns metadataQueries
        every { Persistence.critQueries } returns critQueries
        every { Persistence.trustMarkTypeQueries } returns trustMarkTypeQueries
        every { Persistence.trustMarkIssuerQueries } returns trustMarkIssuerQueries
        every { Persistence.receivedTrustMarkQueries } returns receivedTrustMarkQueries
        every { Persistence.entityConfigurationStatementQueries } returns entityConfigurationStatementQueries

        // Initialize test account
        testAccount = Account(
            id = 1,
            username = "testUser",
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        // Initialize the service under test
        statementService = EntityConfigurationStatementService(accountService, jwkService, kmsProvider)

        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        every { subordinateQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { trustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { authorityHintQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { metadataQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { critQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { trustMarkTypeQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { receivedTrustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun `test find by account`() {
        val testKey = AccountJwk(kid = TEST_KEY_ID, kty = "RSA", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO(), any()) } returns arrayOf(testKey)

        val result = statementService.findByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.iss)
        assertNotNull(result.jwks)
        assertNotNull(result.jwks.propertyKeys)
        assertTrue { result.jwks.propertyKeys?.isNotEmpty() == true }
        assertEquals(TEST_KEY_ID, result.jwks.propertyKeys?.first()?.kid)
    }

    @Test
    fun `test publish by account`() = runTest {
        val key = kmsProvider.generateKeyAsync()

        val testKey = AccountJwk(kid = key.kid ?: key.kmsKeyRef, kty = "EC", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)
        every { jwkService.getAssertedKeysForAccount(testAccount.toDTO(), any(), any(),any()) } returns arrayOf(testKey)

        val result = statementService.publishByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(3, result.split(".").size)
        assertTrue(result.startsWith("ey"))

        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { jwkService.getKeys(testAccount.toDTO()) }
        verify { entityConfigurationStatementQueries.create(any(), any(), any()) }
    }

    @Test
    fun `test publish by account dry run`() = runTest {
        val key = kmsProvider.generateKeyAsync()

        val testKey = AccountJwk(kid = key.kid ?: key.kmsKeyRef, kty = key.jose.publicJwk.kty.toString(), use = key.jose.publicJwk.use)

        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)
        every { jwkService.getAssertedKeysForAccount(testAccount.toDTO(), any(), any(),any()) } returns arrayOf(testKey)

        val result = statementService.publishByAccount(testAccount.toDTO(), dryRun = true)

        assertNotNull(result)
        assertEquals(3, result.split(".").size)
        assertTrue(result.startsWith("ey"))

        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { jwkService.getKeys(testAccount.toDTO()) }
        verify(exactly = 0) { entityConfigurationStatementQueries.create(any(), any(), any()) }
    }

    @Test
    fun `test publish by account no keys`() = runTest {
        every { jwkService.getAssertedKeysForAccount(any(), any(), any(), any()) } throws NotFoundException("No keys found")
        every { jwkService.getKeys(any(), any()) } returns emptyArray()

        assertFailsWith<NotFoundException> {
            statementService.publishByAccount(testAccount.toDTO())
        }

        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { jwkService.getKeys(testAccount.toDTO()) }
    }

    @Test
    fun `test add federation entity metadata`() = runTest {
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        val testKey = AccountJwk(kid = TEST_KEY_ID, kty = "RSA", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)

        val testMetadata = listOf(
            mockk<com.sphereon.oid.fed.persistence.models.Metadata> {
                every { key } returns "test_key"
                every { metadata } returns """{"test": "value"}"""
            }
        )
        every { Persistence.metadataQueries.findByAccountId(testAccount.id).executeAsList() } returns testMetadata

        every { Persistence.subordinateQueries.findByAccountId(testAccount.id).executeAsList() } returns listOf(mockk())
        every { Persistence.trustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()

        val resultWithSubordinates = statementService.findByAccount(testAccount.toDTO())

        assertNotNull(resultWithSubordinates.metadata)
        assertTrue(resultWithSubordinates.metadata?.containsKey("federation_entity") == true)
        assertTrue(resultWithSubordinates.metadata?.containsKey("test_key") == true)

        val parsedMetadata = resultWithSubordinates.metadata?.get("test_key")
        assertNotNull(parsedMetadata)
        assertTrue(parsedMetadata.jsonObject.containsKey("test"))
    }

    @Test
    fun `test add trust mark issuers`() = runTest {
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        val testKey = AccountJwk(kid = TEST_KEY_ID, kty = "EC", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)

        val testTrustMarkType = mockk<com.sphereon.oid.fed.persistence.models.TrustMarkType> {
            every { id } returns 1  // Note the 'L' for Long
            every { identifier } returns "test_trust_mark_type"
        }
        every { Persistence.trustMarkTypeQueries.findByAccountId(testAccount.id).executeAsList() } returns listOf(
            testTrustMarkType
        )

        val testTrustMarkIssuers = listOf(
            mockk<com.sphereon.oid.fed.persistence.models.TrustMarkIssuer> {
                every { issuer_identifier } returns "issuer1"
            },
            mockk<com.sphereon.oid.fed.persistence.models.TrustMarkIssuer> {
                every { issuer_identifier } returns "issuer2"
            }
        )

        every {
            Persistence.trustMarkIssuerQueries.findByTrustMarkTypeId(1).executeAsList()
        } returns testTrustMarkIssuers

        val result = statementService.findByAccount(testAccount.toDTO())

        assertNotNull(result.trustMarkIssuers)
        assertTrue(result.trustMarkIssuers?.containsKey("test_trust_mark_type") == true)

        val issuers = result.trustMarkIssuers?.get("test_trust_mark_type")
        assertNotNull(issuers)
        assertEquals(2, issuers.size)
        assertTrue(issuers.contains("issuer1"))
        assertTrue(issuers.contains("issuer2"))
    }

    @Test
    fun `test add received trust marks`() = runTest {
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        val testKey = AccountJwk(kid = TEST_KEY_ID, kty = "EC", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)

        val testReceivedTrustMark = mockk<com.sphereon.oid.fed.persistence.models.ReceivedTrustMark> {
            every { id } returns 1
            every { account_id } returns testAccount.id
            every { created_at } returns FIXED_TIMESTAMP
            every { trust_mark_type_identifier } returns "test-trust-mark-id"
            every { jwt } returns "test-jwt-token"
        }

        every {
            Persistence.receivedTrustMarkQueries.findByAccountId(testAccount.id).executeAsList()
        } returns listOf(testReceivedTrustMark)

        val result = statementService.findByAccount(testAccount.toDTO())

        assertNotNull(result.trustMarks)
        assertEquals(1, result.trustMarks?.size)

        val trustMark = result.trustMarks?.first()
        assertNotNull(trustMark)
        assertEquals("test-trust-mark-id", trustMark.id)
    }
}
