package com.sphereon.oid.fed.services

import app.cash.sqldelight.Query
import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateTrustMark
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkTypeQueries
import com.sphereon.oid.fed.services.mappers.account.toDTO
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
import com.sphereon.oid.fed.persistence.models.TrustMark as TrustMarkEntity
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer as TrustMarkIssuerEntity
import com.sphereon.oid.fed.persistence.models.TrustMarkType as TrustMarkTypeEntity

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
            id = 1,
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
            id = 1,
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
            id = 1,
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
            TrustMarkTypeEntity(1, testAccount.id, "type1", FIXED_TIMESTAMP, null, deleted_at = null),
            TrustMarkTypeEntity(2, testAccount.id, "type2", FIXED_TIMESTAMP, null, deleted_at = null)
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
            id = 1,
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
        every {
            trustMarkTypeQueries.findByAccountIdAndId(testAccount.id, 999).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.findById(testAccount.toDTO(), 999)
        }
    }

    @Test
    fun `delete trust mark type succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = 1,
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
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuers = listOf(
            TrustMarkIssuerEntity(1, trustMarkType.id, "issuer1", created_at = FIXED_TIMESTAMP, deleted_at = null),
            TrustMarkIssuerEntity(2, trustMarkType.id, "issuer2", created_at = FIXED_TIMESTAMP, deleted_at = null)
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
        assertTrue(result.contains("issuer1"))
        assertTrue(result.contains("issuer2"))
    }

    @Test
    fun `add issuer to trust mark type succeeds`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = 1,
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
            1,
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
            id = 1,
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
                1,
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
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null,
            updated_at = FIXED_TIMESTAMP
        )

        val issuerIdentifier = "https://existing-issuer.com"

        val existingIssuer = TrustMarkIssuerEntity(
            1,
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
                issuer_identifier = issuerIdentifier
            ).executeAsOne()
        } returns existingIssuer

        val result = trustMarkService.removeIssuerFromTrustMarkType(
            testAccount.toDTO(),
            trustMarkType.id,
            issuerIdentifier
        )

        assertNotNull(result)
        assertEquals(issuerIdentifier, result.issuer_identifier)
    }

    @Test
    fun `remove issuer from trust mark type fails for non-existent issuer`() {
        val trustMarkType = TrustMarkTypeEntity(
            id = 1,
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
        val createDto = CreateTrustMark(
            sub = TEST_SUB,
            trustMarkTypeIdentifier = TEST_IDENTIFIER,
            exp = null,
            logoUri = null,
            ref = null,
            delegation = null
        )

        val key = kmsProvider.generateKeyAsync()

        val keys = arrayOf(
            AccountJwk(kid = key.kid, kty = key.jose.publicJwk.kty.toString(), use = key.jose.publicJwk.use)
        )

        every { jwkService.getKeys(testAccount.toDTO()) } returns keys
        every { accountService.getAccountIdentifierByAccount(testAccount.toDTO()) } returns TEST_ISS

        val expectedIat = (FIXED_TIMESTAMP.toEpochSecond(ZoneOffset.UTC)).toInt()
        val createdTrustMark = TrustMarkEntity(
            id = 1,
            account_id = testAccount.id,
            sub = TEST_SUB,
            trust_mark_type_identifier = TEST_IDENTIFIER,
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
                trust_mark_type_identifier = TEST_IDENTIFIER,
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
                trust_mark_type_identifier = TEST_IDENTIFIER,
                exp = null,
                iat = expectedIat,
                trust_mark_value = any()
            )
        }
    }

    @Test
    fun `create trust mark fails when no keys exist`() = runTest {
        val createDto = CreateTrustMark(
            sub = TEST_SUB,
            trustMarkTypeIdentifier = TEST_IDENTIFIER,
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
            id = 1,
            account_id = testAccount.id,
            sub = TEST_SUB,
            trust_mark_type_identifier = TEST_IDENTIFIER,
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
        every {
            trustMarkQueries.findByAccountIdAndId(testAccount.id, 999).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            trustMarkService.deleteTrustMark(testAccount.toDTO(), 999)
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
                id = 1,
                account_id = testAccount.id,
                sub = TEST_SUB,
                trust_mark_type_identifier = TEST_IDENTIFIER,
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
}
