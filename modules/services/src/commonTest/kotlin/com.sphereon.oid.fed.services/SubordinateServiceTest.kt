package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateSubordinate
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import com.sphereon.oid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.oid.fed.persistence.models.SubordinateMetadata
import com.sphereon.oid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.oid.fed.persistence.models.SubordinateQueries
import com.sphereon.oid.fed.persistence.models.SubordinateStatement
import com.sphereon.oid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.oid.fed.services.mappers.account.toDTO
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SubordinateServiceTest {
    private lateinit var subordinateService: SubordinateService
    private lateinit var accountService: AccountService
    private lateinit var jwkService: JwkService
    private lateinit var kmsProvider: IKeyManagementSystem
    private lateinit var testAccount: Account
    private lateinit var subordinateQueries: SubordinateQueries
    private lateinit var subordinateJwkQueries: SubordinateJwkQueries
    private lateinit var subordinateStatementQueries: SubordinateStatementQueries
    private lateinit var subordinateMetadataQueries: SubordinateMetadataQueries

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_IDENTIFIER = "test-subordinate-identifier"
        private const val TEST_SUB = "https://test-subordinate.com"
        private const val TEST_ISS = "https://test-issuer.com"
    }

    @BeforeTest
    fun setup() {
        // Mock dependencies
        accountService = mockk()
        jwkService = mockk()
        kmsProvider = EcDSACryptoProvider()

        // Mock queries
        subordinateQueries = mockk(relaxed = true)
        subordinateJwkQueries = mockk(relaxed = true)
        subordinateStatementQueries = mockk(relaxed = true)
        subordinateMetadataQueries = mockk(relaxed = true)

        // Mock Persistence object
        mockkObject(Persistence)
        every { Persistence.subordinateQueries } returns subordinateQueries
        every { Persistence.subordinateJwkQueries } returns subordinateJwkQueries
        every { Persistence.subordinateStatementQueries } returns subordinateStatementQueries
        every { Persistence.subordinateMetadataQueries } returns subordinateMetadataQueries

        // Create test account
        testAccount = Account(
            id = 1,
            username = "testUser",
            identifier = TEST_ISS,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        // Initialize service
        subordinateService = SubordinateService(accountService, jwkService, kmsProvider)
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun `find subordinates by account returns correct subordinates`() {
        val subordinates = listOf(
            Subordinate(1, testAccount.id, "sub1", FIXED_TIMESTAMP, null),
            Subordinate(2, testAccount.id, "sub2", FIXED_TIMESTAMP, null)
        )

        every { subordinateQueries.findByAccountId(testAccount.id).executeAsList() } returns subordinates

        val result = subordinateService.findSubordinatesByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        verify { subordinateQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `find subordinates by account as array returns correct identifiers`() {
        val subordinates = listOf(
            Subordinate(1, testAccount.id, "sub1", FIXED_TIMESTAMP, null),
            Subordinate(2, testAccount.id, "sub2", FIXED_TIMESTAMP, null)
        )

        every { subordinateQueries.findByAccountId(testAccount.id).executeAsList() } returns subordinates

        val result = subordinateService.findSubordinatesByAccountAsArray(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        assertContentEquals(arrayOf("sub1", "sub2"), result)
    }

    @Test
    fun `delete subordinate succeeds for valid subordinate`() {
        val subordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate
        every { subordinateQueries.delete(subordinate.id).executeAsOne() } returns subordinate

        val result = subordinateService.deleteSubordinate(testAccount.toDTO(), subordinate.id)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
        verify { subordinateQueries.findById(subordinate.id) }
        verify { subordinateQueries.delete(subordinate.id) }
    }

    @Test
    fun `delete subordinate fails for non-existent subordinate`() {
        every { subordinateQueries.findById(999).executeAsOneOrNull() } returns null

        assertFailsWith<NotFoundException> {
            subordinateService.deleteSubordinate(testAccount.toDTO(), 999)
        }
    }

    @Test
    fun `delete subordinate fails for subordinate from different account`() {
        val subordinate = Subordinate(
            id = 1,
            account_id = 999, // Different account
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate

        assertFailsWith<NotFoundException> {
            subordinateService.deleteSubordinate(testAccount.toDTO(), subordinate.id)
        }
    }

    @Test
    fun `create subordinate succeeds with unique identifier`() {
        val createSubordinateDTO = CreateSubordinate(
            identifier = TEST_IDENTIFIER
        )

        every {
            subordinateQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER).executeAsList()
        } returns emptyList()

        val createdSubordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { subordinateQueries.create(testAccount.id, TEST_IDENTIFIER).executeAsOne() } returns createdSubordinate

        val result = subordinateService.createSubordinate(testAccount.toDTO(), createSubordinateDTO)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
        verify { subordinateQueries.create(testAccount.id, TEST_IDENTIFIER) }
    }

    @Test
    fun `create subordinate fails for duplicate identifier`() {
        val createSubordinateDTO = CreateSubordinate(
            identifier = TEST_IDENTIFIER
        )

        val existingSubordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            subordinateQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER).executeAsList()
        } returns listOf(existingSubordinate)

        assertFailsWith<EntityAlreadyExistsException> {
            subordinateService.createSubordinate(testAccount.toDTO(), createSubordinateDTO)
        }
    }

    @Test
    fun `get subordinate statement succeeds`() {
        val subordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_SUB,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val subordinateJwks = listOf(
            SubordinateJwk(1, subordinate.id, """{"kid":"kid1", "kty":"EC"}""", FIXED_TIMESTAMP, null)
        )

        val subordinateMetadata = listOf(
            SubordinateMetadata(
                1, testAccount.id, subordinate.id, "test-key",
                """{"test": "value"}""", FIXED_TIMESTAMP, null
            )
        )

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate
        every { subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList() } returns subordinateJwks
        every {
            Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateId(testAccount.id, subordinate.id)
                .executeAsList()
        } returns subordinateMetadata
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_ISS

        val result = subordinateService.getSubordinateStatement(testAccount.toDTO(), subordinate.id)

        assertNotNull(result)
        assertEquals(TEST_ISS, result.iss)
        assertEquals(TEST_SUB, result.sub)
        assertNotNull(result.jwks)
        assertNotNull(result.metadata)
    }

    @Test
    fun `publish subordinate statement succeeds`() = runTest {
        val subordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_SUB,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val generatedKey = kmsProvider.generateKeyAsync()

        val keys = arrayOf(
            AccountJwk(
                kid = generatedKey.kid,
                kty = generatedKey.jose.publicJwk.kty.toString(),
                use = generatedKey.jose.publicJwk.use
            )
        )

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate
        every { subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList() } returns emptyList()
        every {
            Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateId(testAccount.id, subordinate.id)
                .executeAsList()
        } returns emptyList()
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_ISS
        every { jwkService.getKeys(testAccount.toDTO()) } returns keys

        every {
            subordinateStatementQueries.create(
                any(),
                any(),
                any(),
                any(),
                any()
            ).executeAsOne()
        } returns SubordinateStatement(
            id = 1,
            subordinate_id = subordinate.id,
            iss = TEST_ISS,
            sub = TEST_SUB,
            statement = "placeholder",
            expires_at = System.currentTimeMillis() / 1000 + 3600 * 24 * 365,
            created_at = FIXED_TIMESTAMP
        )

        val result = subordinateService.publishSubordinateStatement(testAccount.toDTO(), subordinate.id)

        assertNotNull(result)

        verify { subordinateStatementQueries.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `publish subordinate statement fails when no keys exist`() = runTest {
        val subordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_SUB,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate
        every { subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList() } returns emptyList()
        every {
            Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateId(testAccount.id, subordinate.id)
                .executeAsList()
        } returns emptyList()
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_ISS
        every { jwkService.getKeys(testAccount.toDTO()) } returns emptyArray()

        assertFailsWith<IllegalArgumentException> {
            subordinateService.publishSubordinateStatement(testAccount.toDTO(), subordinate.id)
        }
    }

    @Test
    fun `create subordinate JWK succeeds`() {
        val subordinate = Subordinate(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_SUB,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val testJwk = JsonObject(mapOf("kid" to JsonPrimitive("test-kid")))

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate
        every {
            subordinateJwkQueries.create(
                key = testJwk.toString(),
                subordinate_id = subordinate.id
            ).executeAsOne()
        } returns SubordinateJwk(
            id = 1,
            subordinate_id = subordinate.id,
            key = testJwk.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val result = subordinateService.createSubordinateJwk(testAccount.toDTO(), subordinate.id, testJwk)

        assertNotNull(result)
        assertEquals(subordinate.id, result.subordinateId)
        verify { subordinateJwkQueries.create(any(), any()) }
    }

    @Test
    fun `create subordinate JWK fails for subordinate from different account`() {
        val subordinate = Subordinate(
            id = 1,
            account_id = 999, // Different account
            identifier = TEST_SUB,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val testJwk = JsonObject(mapOf("kid" to JsonPrimitive("test-kid")))

        every { subordinateQueries.findById(subordinate.id).executeAsOneOrNull() } returns subordinate

        assertFailsWith<NotFoundException> {
            subordinateService.createSubordinateJwk(testAccount.toDTO(), subordinate.id, testJwk)
        }
    }
}
