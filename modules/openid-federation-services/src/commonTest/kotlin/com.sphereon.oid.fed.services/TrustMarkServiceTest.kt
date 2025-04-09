package com.sphereon.oid.fed.services

import app.cash.sqldelight.Query
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.crypto.kms.ecdsa.EcDSACryptoProvider
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkTypeQueries
import com.sphereon.oid.fed.services.mappers.toDTO
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.sphereon.oid.fed.persistence.models.TrustMark as TrustMarkEntity
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer as TrustMarkIssuerEntity
import com.sphereon.oid.fed.persistence.models.TrustMarkType as TrustMarkTypeEntity

@ExperimentalUuidApi
class TrustMarkServiceTest {
    private lateinit var trustMarkService: TrustMarkService
    private lateinit var jwkService: JwkService
    private lateinit var accountService: AccountService
    private lateinit var testAccount: Account
    private lateinit var trustMarkQueries: TrustMarkQueries
    private lateinit var trustMarkTypeQueries: TrustMarkTypeQueries
    private lateinit var trustMarkIssuerQueries: TrustMarkIssuerQueries
    private lateinit var kmsProvider: IKeyManagementSystem

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_IDENTIFIER = "test-trust-mark-type"
        private const val TEST_SUB = "https://test-subject.com"
        private const val TEST_ISS = "https://test-issuer.com"

    }

    @BeforeTest
    fun setup() {
        // Mock dependencies
        jwkService = mockk()
        kmsProvider = EcDSACryptoProvider()
        accountService = mockk()

        // Mock queries
        trustMarkQueries = mockk()
        trustMarkTypeQueries = mockk(relaxed = true)
        trustMarkIssuerQueries = mockk(relaxed = true)

        // Mock Persistence object
        mockkObject(Persistence)
        every { Persistence.trustMarkQueries } returns trustMarkQueries
        every { Persistence.trustMarkTypeQueries } returns trustMarkTypeQueries
        every { Persistence.trustMarkIssuerQueries } returns trustMarkIssuerQueries

        // Create test account
        testAccount = Account(
            id = Uuid.random().toString(),
            username = "testUser",
            identifier = TEST_ISS,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        // Initialize service
        trustMarkService = TrustMarkService(jwkService, kmsProvider, accountService)
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun `create trust mark type succeeds with unique identifier`() {
        val createDto = CreateTrustMarkType(
            identifier = TEST_IDENTIFIER
        )

        every {
            trustMarkTypeQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER).executeAsOneOrNull()
        } returns null

        val createdType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        every {
            trustMarkTypeQueries.create(
                account_id = testAccount.id,
                identifier = TEST_IDENTIFIER
            ).executeAsOne()
        } returns createdType

        val result = trustMarkService.createTrustMarkType(testAccount.toDTO(), createDto)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
        verify { trustMarkTypeQueries.create(TEST_IDENTIFIER, testAccount.id) }
    }

    @Test
    fun `create trust mark type fails for duplicate identifier`() {
        val createDto = CreateTrustMarkType(
            identifier = TEST_IDENTIFIER
        )

        val existingType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        every {
            trustMarkTypeQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER).executeAsOneOrNull()
        } returns existingType

        assertFailsWith<EntityAlreadyExistsException> {
            trustMarkService.createTrustMarkType(testAccount.toDTO(), createDto)
        }
    }

    @Test
    fun `find all trust mark types by account returns correct types`() {
        val trustMarkTypes = listOf(
            TrustMarkTypeEntity(
                Uuid.random().toString(),
                testAccount.id,
                "type1",
                FIXED_TIMESTAMP,
                null,
                deleted_at = null
            ),
            TrustMarkTypeEntity(
                Uuid.random().toString(),
                testAccount.id,
                "type2",
                FIXED_TIMESTAMP,
                null,
                deleted_at = null
            )
        )

        every { trustMarkTypeQueries.findByAccountId(testAccount.id).executeAsList() } returns trustMarkTypes

        val result = trustMarkService.findAllByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("type1", result[0].identifier)
        assertEquals("type2", result[1].identifier)
    }

    @Test
    fun `find trust mark type by id succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType

        val result = trustMarkService.findById(testAccount.toDTO(), trustMarkType.id)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
    }

    @Test
    fun `find trust mark type by id fails for non-existent type`() {
        val id = Uuid.random().toString()
        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, id).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.findById(testAccount.toDTO(), id)
        }
    }

    @Test
    fun `delete trust mark type succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType
        every { trustMarkTypeQueries.delete(trustMarkType.id).executeAsOne() } returns trustMarkType

        val result = trustMarkService.deleteTrustMarkType(testAccount.toDTO(), trustMarkType.id)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
        verify { trustMarkTypeQueries.delete(trustMarkType.id) }
    }

    @Test
    fun `get issuers for trust mark type succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuers = listOf(
            TrustMarkIssuerEntity(
                Uuid.random().toString(),
                trustMarkType.id,
                "issuer1",
                created_at = FIXED_TIMESTAMP,
                deleted_at = null
            ),
            TrustMarkIssuerEntity(
                Uuid.random().toString(),
                trustMarkType.id,
                "issuer2",
                created_at = FIXED_TIMESTAMP,
                deleted_at = null
            )
        )

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType
        every {
            trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkType.id).executeAsList()
        } returns issuers

        val result = trustMarkService.getIssuersForTrustMarkType(testAccount.toDTO(), trustMarkType.id)

        assertNotNull(result)
        assertEquals(2, result.size)
        assertTrue(result.any { it.issuer_identifier == "issuer1" })
        assertTrue(result.any { it.issuer_identifier == "issuer2" })
    }

    @Test
    fun `add issuer to trust mark type succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuerIdentifier = "https://new-issuer.com"

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType
        every {
            trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkType.id).executeAsList()
        } returns emptyList()
        every {
            trustMarkIssuerQueries.create(
                trust_mark_type_id = trustMarkType.id,
                issuer_identifier = issuerIdentifier
            ).executeAsOne()
        } returns TrustMarkIssuerEntity(
            Uuid.random().toString(),
            trustMarkType.id,
            issuerIdentifier,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val result = trustMarkService.addIssuerToTrustMarkType(
            testAccount.toDTO(),
            trustMarkType.id,
            issuerIdentifier
        )

        assertNotNull(result)
        assertEquals(issuerIdentifier, result.issuer_identifier)
    }

    @Test
    fun `add issuer to trust mark type fails for duplicate issuer`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuerIdentifier = "https://existing-issuer.com"

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType
        every {
            trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkType.id).executeAsList()
        } returns listOf(
            TrustMarkIssuerEntity(
                Uuid.random().toString(),
                trustMarkType.id,
                issuerIdentifier,
                created_at = FIXED_TIMESTAMP,
                deleted_at = null
            )
        )

        assertFailsWith<EntityAlreadyExistsException> {
            trustMarkService.addIssuerToTrustMarkType(
                testAccount.toDTO(),
                trustMarkType.id,
                issuerIdentifier
            )
        }
    }

    @Test
    fun `remove issuer from trust mark type succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuerIdentifier = "https://existing-issuer.com"

        val existingIssuer = TrustMarkIssuerEntity(
            Uuid.random().toString(),
            trustMarkType.id,
            issuerIdentifier,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType
        every {
            trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkType.id).executeAsList()
        } returns listOf(existingIssuer)
        every {
            trustMarkIssuerQueries.delete(
                trust_mark_type_id = trustMarkType.id,
                id = existingIssuer.id
            ).executeAsOne()
        } returns existingIssuer

        val result = trustMarkService.removeIssuerFromTrustMarkType(
            testAccount.toDTO(),
            trustMarkType.id,
            existingIssuer.id
        )

        assertNotNull(result)
        assertEquals(issuerIdentifier, result.issuer_identifier)
    }

    @Test
    fun `remove issuer from trust mark type fails for non-existent issuer`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuerIdentifier = "https://non-existent-issuer.com"

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, trustMarkType.id).executeAsOneOrNull()
        } returns trustMarkType
        every {
            trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkType.id).executeAsList()
        } returns emptyList()

        assertFailsWith<NotFoundException> {
            trustMarkService.removeIssuerFromTrustMarkType(
                testAccount.toDTO(),
                trustMarkType.id,
                issuerIdentifier
            )
        }
    }

    @Test
    fun `create trust mark succeeds`() = runTest {
        val createDto = CreateTrustMarkRequest(
            sub = TEST_SUB,
            trustMarkId = TEST_IDENTIFIER,
            exp = null,
            logoUri = null,
            ref = null,
            delegation = null
        )

        val key = kmsProvider.generateKeyAsync()

        val keys = arrayOf(
            AccountJwk(
                id = "c83e83e7-ed9e-4dda-85f7-d43b51065cca",
                kid = key.kid ?: key.kmsKeyRef,
                kty = key.jose.publicJwk.kty.toString(),
                use = key.jose.publicJwk.use
            )
        )

        every { jwkService.getKeys(testAccount.toDTO()) } returns keys
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_ISS

        val expectedIat = (FIXED_TIMESTAMP.toEpochSecond(ZoneOffset.UTC)).toInt()
        val createdTrustMark = TrustMarkEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            sub = TEST_SUB,
            trust_mark_id = TEST_IDENTIFIER,
            exp = null,
            iat = expectedIat,
            trust_mark_value = "TEST_JWT",
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )
        val queryResult = mockk<Query<TrustMarkEntity>>()
        every { queryResult.executeAsOne() } returns createdTrustMark
        every {
            trustMarkQueries.create(
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_id = TEST_IDENTIFIER,
                exp = null,
                iat = expectedIat,
                trust_mark_value = any()
            )
        } returns queryResult

        val result = trustMarkService.createTrustMark(
            testAccount.toDTO(),
            createDto,
            FIXED_TIMESTAMP.toEpochSecond(ZoneOffset.UTC) * 1000
        )

        assertNotNull(result)

        verify {
            trustMarkQueries.create(
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_id = TEST_IDENTIFIER,
                exp = null,
                iat = expectedIat,
                trust_mark_value = any()
            )
        }
    }

    @Test
    fun `create trust mark fails when no keys exist`() = runTest {
        val createDto = CreateTrustMarkRequest(
            sub = TEST_SUB,
            trustMarkId = TEST_IDENTIFIER,
            exp = null,
            logoUri = null,
            ref = null,
            delegation = null
        )

        every { jwkService.getKeys(testAccount.toDTO()) } returns emptyArray()

        assertFailsWith<IllegalArgumentException> {
            trustMarkService.createTrustMark(
                testAccount.toDTO(),
                createDto,
                FIXED_TIMESTAMP.toEpochSecond(ZoneOffset.UTC) * 1000
            )
        }
    }

    @Test
    fun `delete trust mark succeeds`() {
        val trustMark = TrustMarkEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            sub = TEST_SUB,
            trust_mark_id = TEST_IDENTIFIER,
            exp = null,
            iat = (System.currentTimeMillis() / 1000).toInt(),
            trust_mark_value = "TEST_JWT",
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            trustMarkQueries.findByAccountIdAndId(testAccount.id, trustMark.id).executeAsOneOrNull()
        } returns trustMark
        every {
            trustMarkQueries.delete(trustMark.id).executeAsOne()
        } returns trustMark

        val result = trustMarkService.deleteTrustMark(testAccount.toDTO(), trustMark.id)

        assertNotNull(result)
        verify { trustMarkQueries.delete(trustMark.id) }
    }

    @Test
    fun `delete trust mark fails for non-existent trust mark`() {
        val id = Uuid.random().toString()
        every {
            trustMarkQueries.findByAccountIdAndId(testAccount.id, id).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.deleteTrustMark(testAccount.toDTO(), id)
        }
    }

    @Test
    fun `get trust mark status returns true when trust mark exists`() {
        val request = TrustMarkStatusRequest(
            trustMarkId = TEST_IDENTIFIER,
            sub = TEST_SUB,
            iat = null
        )

        val trustMarks = listOf(
            TrustMarkEntity(
                id = Uuid.random().toString(),
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_id = TEST_IDENTIFIER,
                exp = null,
                iat = (System.currentTimeMillis() / 1000).toInt(),
                trust_mark_value = "TEST_JWT",
                created_at = FIXED_TIMESTAMP,
                deleted_at = null
            )
        )

        every {
            trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(
                testAccount.id,
                request.trustMarkId,
                request.sub
            ).executeAsList()
        } returns trustMarks

        val result = trustMarkService.getTrustMarkStatus(testAccount.toDTO(), request)

        assertTrue(result)
    }

    @Test
    fun `get trust mark status returns false when no trust mark exists`() {
        val request = TrustMarkStatusRequest(
            trustMarkId = TEST_IDENTIFIER,
            sub = TEST_SUB,
            iat = null
        )

        every {
            trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(
                testAccount.id,
                request.trustMarkId,
                request.sub
            ).executeAsList()
        } returns emptyList()

        val result = trustMarkService.getTrustMarkStatus(testAccount.toDTO(), request)

        assertFalse(result)
    }

    @Test
    fun `get trust marked subs returns filtered subjects when sub is provided`() {
        val request = TrustMarkListRequest(
            trustMarkId = TEST_IDENTIFIER,
            sub = TEST_SUB
        )

        val subjects = listOf(TEST_SUB)

        every {
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(
                testAccount.id,
                request.trustMarkId,
                request.sub!!
            ).executeAsList()
        } returns subjects

        val result = trustMarkService.getTrustMarkedSubs(testAccount.toDTO(), request)

        assertEquals(1, result.size)
        assertEquals(TEST_SUB, result[0])
        verify {
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(
                testAccount.id,
                request.trustMarkId,
                request.sub!!
            )
        }
    }

    @Test
    fun `get trust marked subs returns all subjects when no sub is provided`() {
        val request = TrustMarkListRequest(
            trustMarkId = TEST_IDENTIFIER,
            sub = null
        )

        val subjects = listOf(TEST_SUB, "https://another-subject.com")

        every {
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(
                testAccount.id,
                request.trustMarkId
            ).executeAsList()
        } returns subjects

        val result = trustMarkService.getTrustMarkedSubs(testAccount.toDTO(), request)

        assertEquals(2, result.size)
        assertTrue(result.contains(TEST_SUB))
        assertTrue(result.contains("https://another-subject.com"))
        verify {
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(
                testAccount.id,
                request.trustMarkId
            )
        }
    }

    @Test
    fun `get trust mark returns trust mark value for valid request`() {
        val request = TrustMarkRequest(
            trustMarkId = TEST_IDENTIFIER,
            sub = TEST_SUB
        )

        val trustMark = TrustMarkEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            sub = TEST_SUB,
            trust_mark_id = TEST_IDENTIFIER,
            exp = null,
            iat = (System.currentTimeMillis() / 1000).toInt(),
            trust_mark_value = "test-trust-mark-jwt",
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
                testAccount.id,
                request.trustMarkId,
                request.sub
            ).executeAsOneOrNull()
        } returns trustMark

        val result = trustMarkService.getTrustMark(testAccount.toDTO(), request)

        assertEquals("test-trust-mark-jwt", result)
        verify {
            trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
                testAccount.id,
                request.trustMarkId,
                request.sub
            )
        }
    }

    @Test
    fun `get trust mark throws NotFoundException when trust mark does not exist`() {
        val request = TrustMarkRequest(
            trustMarkId = TEST_IDENTIFIER,
            sub = TEST_SUB
        )

        every {
            trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
                testAccount.id,
                request.trustMarkId,
                request.sub
            ).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.getTrustMark(testAccount.toDTO(), request)
        }
        verify {
            trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
                testAccount.id,
                request.trustMarkId,
                request.sub
            )
        }
    }

    @Test
    fun `get trust marks for account returns all trust marks`() {
        val trustMarks = listOf(
            TrustMarkEntity(
                id = Uuid.random().toString(),
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_id = TEST_IDENTIFIER,
                exp = null,
                iat = (System.currentTimeMillis() / 1000).toInt(),
                trust_mark_value = "test-trust-mark-jwt-1",
                created_at = FIXED_TIMESTAMP,
                deleted_at = null
            ),
            TrustMarkEntity(
                id = Uuid.random().toString(),
                account_id = testAccount.id,
                sub = "https://another-subject.com",
                trust_mark_id = "another-type",
                exp = null,
                iat = (System.currentTimeMillis() / 1000).toInt(),
                trust_mark_value = "test-trust-mark-jwt-2",
                created_at = FIXED_TIMESTAMP,
                deleted_at = null
            )
        )

        every { trustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns trustMarks

        val result = trustMarkService.getTrustMarksForAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        verify { trustMarkQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `get trust marks for account returns empty list when no trust marks exist`() {
        every { trustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()

        val result = trustMarkService.getTrustMarksForAccount(testAccount.toDTO())

        assertTrue(result.isEmpty())
        verify { trustMarkQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `delete trust mark type fails for non-existent type`() {
        val nonExistentTypeId = Uuid.random().toString()

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.deleteTrustMarkType(testAccount.toDTO(), nonExistentTypeId)
        }

        verify {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId)
        }
    }

    @Test
    fun `get issuers fails when trust mark type does not exist`() {
        val nonExistentTypeId = Uuid.random().toString()

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.getIssuersForTrustMarkType(testAccount.toDTO(), nonExistentTypeId)
        }

        verify {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId)
        }
    }

    @Test
    fun `add issuer fails when trust mark type does not exist`() {
        val nonExistentTypeId = Uuid.random().toString()
        val issuerIdentifier = "https://test-issuer.com"

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.addIssuerToTrustMarkType(
                testAccount.toDTO(),
                nonExistentTypeId,
                issuerIdentifier
            )
        }

        verify {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId)
        }
    }

    @Test
    fun `remove issuer fails when trust mark type does not exist`() {
        val nonExistentTypeId = Uuid.random().toString()
        val issuerIdentifier = "https://test-issuer.com"

        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.removeIssuerFromTrustMarkType(
                testAccount.toDTO(),
                nonExistentTypeId,
                issuerIdentifier
            )
        }

        verify {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, nonExistentTypeId)
        }
    }

    @Test
    fun `create trust mark with expiration succeeds`() = runTest {
        val expirationTime = (System.currentTimeMillis() / 1000 + 3600).toInt() // 1 hour from now
        val createDto = CreateTrustMarkRequest(
            sub = TEST_SUB,
            trustMarkId = TEST_IDENTIFIER,
            exp = expirationTime,
            logoUri = null,
            ref = null,
            delegation = null
        )

        val key = kmsProvider.generateKeyAsync()

        val keys = arrayOf(
            AccountJwk(
                id = "c83e83e7-ed9e-4dda-85f7-d43b51065cca",
                kid = key.kid ?: key.kmsKeyRef,
                kty = key.jose.publicJwk.kty.toString(),
                use = key.jose.publicJwk.use
            )
        )

        every { jwkService.getKeys(testAccount.toDTO()) } returns keys
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_ISS

        val expectedIat = (FIXED_TIMESTAMP.toEpochSecond(ZoneOffset.UTC)).toInt()
        val createdTrustMark = TrustMarkEntity(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            sub = TEST_SUB,
            trust_mark_id = TEST_IDENTIFIER,
            exp = expirationTime,
            iat = expectedIat,
            trust_mark_value = "TEST_JWT",
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )
        val queryResult = mockk<Query<TrustMarkEntity>>()
        every { queryResult.executeAsOne() } returns createdTrustMark
        every {
            trustMarkQueries.create(
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_id = TEST_IDENTIFIER,
                exp = expirationTime,
                iat = expectedIat,
                trust_mark_value = any()
            )
        } returns queryResult

        val result = trustMarkService.createTrustMark(
            testAccount.toDTO(),
            createDto,
            FIXED_TIMESTAMP.toEpochSecond(ZoneOffset.UTC) * 1000
        )

        assertNotNull(result)
        verify {
            trustMarkQueries.create(
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_id = TEST_IDENTIFIER,
                exp = expirationTime,
                iat = expectedIat,
                trust_mark_value = any()
            )
        }
    }
}
