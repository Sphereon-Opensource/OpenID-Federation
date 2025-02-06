package com.sphereon.oid.fed.services

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
    }

    @BeforeTest
    fun setup() {
        authorityHintQueries = mockk<AuthorityHintQueries>(relaxed = true)
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
    fun testCreateAuthorityHint() {
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
    fun testCreateDuplicateAuthorityHint() {
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

        assertFailsWith<EntityAlreadyExistsException> {
            authorityHintService.createAuthorityHint(testAccount.toDTO(), TEST_IDENTIFIER)
        }
        verify { authorityHintQueries.findByAccountIdAndIdentifier(testAccount.id, TEST_IDENTIFIER) }
    }

    @Test
    fun testDeleteAuthorityHint() {
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
    fun testDeleteNonExistentAuthorityHint() {
        val nonExistentId = 999

        every { authorityHintQueries.findByAccountIdAndId(testAccount.id, nonExistentId) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }

        assertFailsWith<NotFoundException> {
            authorityHintService.deleteAuthorityHint(testAccount.toDTO(), nonExistentId)
        }
        verify { authorityHintQueries.findByAccountIdAndId(testAccount.id, nonExistentId) }
    }

    @Test
    fun testFindByAccount() {
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
}