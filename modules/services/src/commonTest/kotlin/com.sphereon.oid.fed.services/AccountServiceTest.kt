package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.AccountQueries
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import com.sphereon.oid.fed.services.mappers.account.toDTO
import io.mockk.*
import java.time.LocalDateTime
import kotlin.test.*

class AccountServiceTest {
    private lateinit var accountService: AccountService
    private lateinit var config: AccountServiceConfig
    private lateinit var accountQueries: AccountQueries

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
    }

    @BeforeTest
    fun setup() {
        config = mockk {
            every { rootIdentifier } returns "http://localhost:8080"
        }
        accountQueries = mockk(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.accountQueries } returns accountQueries
        accountService = AccountService(config)
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun `create account succeeds when username is unique`() {
        val createAccountDTO = CreateAccount(
            username = "testUser",
            identifier = "test-identifier"
        )
        val account = Account(
            id = 1,
            username = createAccountDTO.username,
            identifier = createAccountDTO.identifier,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { accountQueries.findByUsername(createAccountDTO.username) } returns mockk {
            every { executeAsOneOrNull() } returns null
        }
        every { accountQueries.create(createAccountDTO.username, createAccountDTO.identifier) } returns mockk {
            every { executeAsOne() } returns account
        }

        val result = accountService.createAccount(createAccountDTO)

        assertNotNull(result)
        assertEquals(createAccountDTO.username, result.username)
        assertEquals(createAccountDTO.identifier, result.identifier)
        verify { accountQueries.findByUsername(createAccountDTO.username) }
        verify { accountQueries.create(createAccountDTO.username, createAccountDTO.identifier) }
    }

    @Test
    fun `create account fails when username already exists`() {
        val createAccountDTO = CreateAccount(
            username = "testUser",
            identifier = "test-identifier"
        )
        val existingAccount = Account(
            id = 1,
            username = createAccountDTO.username,
            identifier = createAccountDTO.identifier,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { accountQueries.findByUsername(createAccountDTO.username) } returns mockk {
            every { executeAsOneOrNull() } returns existingAccount
        }

        assertFailsWith<EntityAlreadyExistsException> {
            accountService.createAccount(createAccountDTO)
        }
        verify { accountQueries.findByUsername(createAccountDTO.username) }
    }

    @Test
    fun `get all accounts returns list of accounts`() {
        val accounts = listOf(
            Account(1, "user1", "id1", FIXED_TIMESTAMP, FIXED_TIMESTAMP, null),
            Account(2, "user2", "id2", FIXED_TIMESTAMP, FIXED_TIMESTAMP, null)
        )
        every { accountQueries.findAll().executeAsList() } returns accounts

        val result = accountService.getAllAccounts()

        assertNotNull(result)
        assertEquals(2, result.size)
        verify { accountQueries.findAll() }
    }

    @Test
    fun `get account identifier returns explicit identifier when present`() {
        val explicitIdentifier = "https://explicit-identifier.com"
        val account = Account(
            id = 1,
            username = "testUser",
            identifier = explicitIdentifier,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val identifier = accountService.getAccountIdentifierByAccount(account.toDTO())
        assertEquals(explicitIdentifier, identifier)
    }

    @Test
    fun `get account identifier returns correct path for regular account`() {
        val account = Account(
            id = 1,
            username = "testUser",
            identifier = null,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val identifier = accountService.getAccountIdentifierByAccount(account.toDTO())
        assertEquals("${config.rootIdentifier}/${account.username}", identifier)
    }

    @Test
    fun `get account identifier returns root identifier for root account`() {
        val rootAccount = Account(
            id = 1,
            username = Constants.DEFAULT_ROOT_USERNAME,
            identifier = null,
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        val identifier = accountService.getAccountIdentifierByAccount(rootAccount.toDTO())
        assertEquals(config.rootIdentifier, identifier)
    }

    @Test
    fun `get account by username returns account when exists`() {
        val username = "existingUser"
        val account = Account(
            id = 1,
            username = username,
            identifier = "test-identifier",
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { accountQueries.findByUsername(username).executeAsOneOrNull() } returns account

        val result = accountService.getAccountByUsername(username)

        assertNotNull(result)
        assertEquals(username, result.username)
        assertEquals(account.identifier, result.identifier)
        verify { accountQueries.findByUsername(username) }
    }

    @Test
    fun `get account by username throws NotFoundException when account does not exist`() {
        val username = "thisShouldNotExist"
        every { accountQueries.findByUsername(username).executeAsOneOrNull() } returns null

        assertFailsWith<NotFoundException> {
            accountService.getAccountByUsername(username)
        }
        verify { accountQueries.findByUsername(username) }
    }

    @Test
    fun `delete account succeeds for non-root account`() {
        val account = Account(
            id = 1,
            username = "testUser",
            identifier = "test-identifier",
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { accountQueries.delete(account.id).executeAsOne() } returns account

        val result = accountService.deleteAccount(account.toDTO())
        assertNotNull(result)
        verify { accountQueries.delete(account.id) }
    }

    @Test
    fun `delete account fails for root account`() {
        val rootAccount = Account(
            id = 1,
            username = Constants.DEFAULT_ROOT_USERNAME,
            identifier = "root-identifier",
            created_at = FIXED_TIMESTAMP,
            updated_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        assertFailsWith<NotFoundException> {
            accountService.deleteAccount(rootAccount.toDTO())
        }
    }
}