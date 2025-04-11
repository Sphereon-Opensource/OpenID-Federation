package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.admin.NotFoundException
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMark
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.oid.fed.services.mappers.toDTO
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@ExperimentalUuidApi
class ReceivedTrustMarkServiceTest {
    private lateinit var receivedTrustMarkService: ReceivedTrustMarkService
    private lateinit var receivedTrustMarkQueries: ReceivedTrustMarkQueries
    private lateinit var testAccount: Account

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_TRUST_MARK_TYPE_IDENTIFIER = "test-trust-mark-type"
        private const val TEST_JWT = "test.jwt.token"
    }

    @BeforeTest
    fun setup() {
        receivedTrustMarkQueries = mockk(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.receivedTrustMarkQueries } returns receivedTrustMarkQueries
        receivedTrustMarkService = ReceivedTrustMarkService()
        testAccount = Account(
            id = Uuid.random().toString(),
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
    fun `create received trust mark succeeds with valid input`() {
        val createDto = CreateReceivedTrustMark(
            trustMarkId = TEST_TRUST_MARK_TYPE_IDENTIFIER,
            jwt = TEST_JWT
        )
        val trustMark = ReceivedTrustMark(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            trust_mark_id = TEST_TRUST_MARK_TYPE_IDENTIFIER,
            jwt = TEST_JWT,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            receivedTrustMarkQueries.create(
                testAccount.id,
                TEST_TRUST_MARK_TYPE_IDENTIFIER,
                TEST_JWT
            )
        } returns mockk {
            every { executeAsOne() } returns trustMark
        }

        val result = receivedTrustMarkService.createReceivedTrustMark(testAccount.toDTO(), createDto)

        assertNotNull(result)
        assertEquals(TEST_TRUST_MARK_TYPE_IDENTIFIER, result.trustMarkId)
        assertEquals(TEST_JWT, result.jwt)
        verify {
            receivedTrustMarkQueries.create(
                testAccount.id,
                TEST_TRUST_MARK_TYPE_IDENTIFIER,
                TEST_JWT
            )
        }
    }

    @Test
    fun `list received trust marks returns all trust marks for account`() {
        val trustMarks = listOf(
            ReceivedTrustMark(Uuid.random().toString(), testAccount.id, "type1", "jwt1", FIXED_TIMESTAMP, null),
            ReceivedTrustMark(Uuid.random().toString(), testAccount.id, "type2", "jwt2", FIXED_TIMESTAMP, null)
        )

        every { receivedTrustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns trustMarks

        val result = receivedTrustMarkService.listReceivedTrustMarks(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("type1", result[0].trustMarkId)
        assertEquals("type2", result[1].trustMarkId)
        verify { receivedTrustMarkQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `delete received trust mark succeeds for existing trust mark`() {
        val trustMarkId = Uuid.random().toString()
        val trustMark = ReceivedTrustMark(
            id = trustMarkId,
            account_id = testAccount.id,
            trust_mark_id = TEST_TRUST_MARK_TYPE_IDENTIFIER,
            jwt = TEST_JWT,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            receivedTrustMarkQueries.findByAccountIdAndId(testAccount.id, trustMarkId)
        } returns mockk {
            every { executeAsOneOrNull() } returns trustMark
        }
        every { receivedTrustMarkQueries.delete(trustMarkId) } returns mockk {
            every { executeAsOne() } returns trustMark
        }

        val result = receivedTrustMarkService.deleteReceivedTrustMark(testAccount.toDTO(), trustMarkId)

        assertNotNull(result)
        assertEquals(TEST_TRUST_MARK_TYPE_IDENTIFIER, result.trustMarkId)
        verify { receivedTrustMarkQueries.findByAccountIdAndId(testAccount.id, trustMarkId) }
        verify { receivedTrustMarkQueries.delete(trustMarkId) }
    }

    @Test
    fun `delete received trust mark fails for non-existent trust mark`() {
        val nonExistentId = Uuid.random().toString()

        every {
            receivedTrustMarkQueries.findByAccountIdAndId(testAccount.id, nonExistentId)
        } returns mockk {
            every { executeAsOneOrNull() } returns null
        }

        assertFailsWith<NotFoundException> {
            receivedTrustMarkService.deleteReceivedTrustMark(testAccount.toDTO(), nonExistentId)
        }
        verify { receivedTrustMarkQueries.findByAccountIdAndId(testAccount.id, nonExistentId) }
        verify(exactly = 0) { receivedTrustMarkQueries.delete(any()) }
    }

    @Test
    fun `list received trust marks returns empty array when no trust marks exist`() {
        every { receivedTrustMarkQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()

        val result = receivedTrustMarkService.listReceivedTrustMarks(testAccount.toDTO())

        assertTrue(result.isEmpty())
        verify { receivedTrustMarkQueries.findByAccountId(testAccount.id) }
    }
}
