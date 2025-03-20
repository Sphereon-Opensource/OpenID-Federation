package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.AuthorityHint
import com.sphereon.oid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.oid.fed.services.mappers.toDTO
import io.mockk.*
import java.time.LocalDateTime
import kotlin.test.*

class AuthorityHintServiceTest {
    private lateinit var authorityHintService: AuthorityHintService
    private lateinit var authorityHintQueries: AuthorityHintQueries
    private lateinit var testAccount: Account

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_IDENTIFIER = "test-authority-hint"
        private const val NON_EXISTENT_ID = 999
    }

    @BeforeTest
    fun setup() {
        authorityHintQueries = mockk(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.authorityHintQueries } returns authorityHintQueries
        authorityHintService = AuthorityHintService()
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
    fun `create authority hint - should succeed when hint doesn't exist`() {
        val authorityHint = AuthorityHint(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { authorityHintQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }
        every { authorityHintQueries.create(testAccount.id, TEST_IDENTIFIER) } returns mockk {
            every { executeAsOneOrNull() } returns authorityHint
        }

        val result = authorityHintService.createAuthorityHint(testAccount.toDTO(), TEST_IDENTIFIER)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
        verify { authorityHintQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER) }
        verify { authorityHintQueries.create(testAccount.id, TEST_IDENTIFIER) }
    }

    @Test
    fun `create authority hint - should throw EntityAlreadyExistsException when hint exists`() {
        val existingAuthorityHint = AuthorityHint(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { authorityHintQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER) } returns mockk {
            every { executeAsOneOrNull() } returns existingAuthorityHint
        }

        val exception = assertFailsWith<EntityAlreadyExistsException> {
            authorityHintService.createAuthorityHint(testAccount.toDTO(), TEST_IDENTIFIER)
        }
        assertEquals(Constants.AUTHORITY_HINT_ALREADY_EXISTS, exception.message)
        verify { authorityHintQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER) }
    }

    @Test
    fun `create authority hint - should throw IllegalStateException when creation fails`() {
        every { authorityHintQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }
        every { authorityHintQueries.create(testAccount.id, TEST_IDENTIFIER) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }

        val exception = assertFailsWith<IllegalStateException> {
            authorityHintService.createAuthorityHint(testAccount.toDTO(), TEST_IDENTIFIER)
        }
        assertEquals(Constants.FAILED_TO_CREATE_AUTHORITY_HINT, exception.message)
    }

    @Test
    fun `delete authority hint - should succeed when hint exists`() {
        val authorityHint = AuthorityHint(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { authorityHintQueries.findByAccountIdAndId(testAccount.id, authorityHint.id) } returns mockk {
            every { executeAsOneOrNull() } returns authorityHint
        }
        every { authorityHintQueries.delete(authorityHint.id) } returns mockk {
            every { executeAsOneOrNull() } returns authorityHint
        }

        val result = authorityHintService.deleteAuthorityHint(testAccount.toDTO(), authorityHint.id)

        assertNotNull(result)
        assertEquals(TEST_IDENTIFIER, result.identifier)
        verify { authorityHintQueries.findByAccountIdAndId(testAccount.id, authorityHint.id) }
        verify { authorityHintQueries.delete(authorityHint.id) }
    }

    @Test
    fun `delete authority hint - should throw NotFoundException when hint doesn't exist`() {
        every { authorityHintQueries.findByAccountIdAndId(testAccount.id, NON_EXISTENT_ID) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }

        val exception = assertFailsWith<NotFoundException> {
            authorityHintService.deleteAuthorityHint(testAccount.toDTO(), NON_EXISTENT_ID)
        }
        assertEquals(Constants.AUTHORITY_HINT_NOT_FOUND, exception.message)
        verify { authorityHintQueries.findByAccountIdAndId(testAccount.id, NON_EXISTENT_ID) }
    }

    @Test
    fun `delete authority hint - should throw IllegalStateException when deletion fails`() {
        val authorityHint = AuthorityHint(
            id = 1,
            account_id = testAccount.id,
            identifier = TEST_IDENTIFIER,
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { authorityHintQueries.findByAccountIdAndId(testAccount.id, authorityHint.id) } returns mockk {
            every { executeAsOneOrNull() } returns authorityHint
        }
        every { authorityHintQueries.delete(authorityHint.id) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }

        val exception = assertFailsWith<IllegalStateException> {
            authorityHintService.deleteAuthorityHint(testAccount.toDTO(), authorityHint.id)
        }
        assertEquals(Constants.FAILED_TO_DELETE_AUTHORITY_HINT, exception.message)
    }

    @Test
    fun `find authority hints - should return list when hints exist`() {
        val authorityHints = listOf(
            AuthorityHint(1, testAccount.id, "hint1", FIXED_TIMESTAMP, null),
            AuthorityHint(2, testAccount.id, "hint2", FIXED_TIMESTAMP, null)
        )

        every { authorityHintQueries.findByAccountId(testAccount.id).executeAsList() } returns authorityHints

        val result = authorityHintService.findByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("hint1", result[0].identifier)
        assertEquals("hint2", result[1].identifier)
        verify { authorityHintQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `find authority hints - should return empty list when no hints exist`() {
        every { authorityHintQueries.findByAccountId(testAccount.id).executeAsList() } returns emptyList()

        val result = authorityHintService.findByAccount(testAccount.toDTO())

        assertTrue(result.isEmpty())
        verify { authorityHintQueries.findByAccountId(testAccount.id) }
    }
}