package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.EntityJwk
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.*
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import com.sphereon.oid.fed.services.mappers.toDTO
import io.mockk.*
import java.time.LocalDateTime
import kotlin.test.*

class EntityConfigurationStatementServiceTest {
    private lateinit var statementService: EntityConfigurationStatementService
    private lateinit var accountService: AccountService
    private lateinit var jwkService: JwkService
    private lateinit var kmsClient: KmsClient
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
        kmsClient = mockk()
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
        statementService = EntityConfigurationStatementService(accountService, jwkService, kmsClient)
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun testFindByAccount() {
        // Mock account service response
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        // Mock key service response
        val testKey = EntityJwk(kid = TEST_KEY_ID, kty = "RSA", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)

        // Mock empty results for optional components
        every { subordinateQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { trustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { authorityHintQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { metadataQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { critQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { trustMarkTypeQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { receivedTrustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()

        val result = statementService.findByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.iss)
        assertNotNull(result.jwks)
        assertNotNull(result.jwks.propertyKeys)
        assertTrue { result.jwks.propertyKeys?.isNotEmpty() ?: false }
        assertEquals(TEST_KEY_ID, result.jwks.propertyKeys?.first()?.kid)
    }

    @Test
    fun testPublishByAccount() {
        // Mock account service response
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        // Mock key service response
        val testKey = EntityJwk(kid = TEST_KEY_ID, kty = "RSA", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)

        // Mock KMS client response
        val expectedJwt = "test.jwt.token"
        every {
            kmsClient.sign(
                any(),
                any(),
                TEST_KEY_ID
            )
        } returns expectedJwt

        // Mock empty results for optional components
        every { subordinateQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { trustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { authorityHintQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { metadataQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { critQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { trustMarkTypeQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()
        every { receivedTrustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()

        val result = statementService.publishByAccount(testAccount.toDTO())

        assertEquals(expectedJwt, result)
        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { jwkService.getKeys(testAccount.toDTO()) }
        verify { kmsClient.sign(any(), any(), TEST_KEY_ID) }
        verify { entityConfigurationStatementQueries.create(any(), any(), any()) }
    }

    @Test
    fun testPublishByAccountDryRun() {
        // Mock account service response
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        // Mock key service response
        val testKey = EntityJwk(kid = TEST_KEY_ID, kty = "RSA", use = "sig")
        every { jwkService.getKeys(testAccount.toDTO()) } returns arrayOf(testKey)

        // Mock KMS client response
        val expectedJwt = "test.jwt.token"
        every {
            kmsClient.sign(
                any(),
                any(),
                TEST_KEY_ID
            )
        } returns expectedJwt

        val result = statementService.publishByAccount(testAccount.toDTO(), dryRun = true)

        assertEquals(expectedJwt, result)
        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { jwkService.getKeys(testAccount.toDTO()) }
        verify { kmsClient.sign(any(), any(), TEST_KEY_ID) }
        verify(exactly = 0) { entityConfigurationStatementQueries.create(any(), any(), any()) }
    }

    @Test
    fun testPublishByAccountNoKeys() {
        // Mock account service response
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_IDENTIFIER

        // Mock empty key response
        every { jwkService.getKeys(testAccount.toDTO()) } returns emptyArray()

        assertFailsWith<IllegalArgumentException> {
            statementService.publishByAccount(testAccount.toDTO())
        }

        verify { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) }
        verify { jwkService.getKeys(testAccount.toDTO()) }
        verify(exactly = 0) { kmsClient.sign(any(), any(), any()) }
    }
}