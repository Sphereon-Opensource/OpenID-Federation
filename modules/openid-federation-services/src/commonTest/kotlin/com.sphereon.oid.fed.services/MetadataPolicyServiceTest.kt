package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.MetadataPolicy
import com.sphereon.oid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.oid.fed.services.mappers.toDTO
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@ExperimentalUuidApi
class MetadataPolicyServiceTest {
    private lateinit var metadataPolicyService: MetadataPolicyService
    private lateinit var metadataPolicyQueries: MetadataPolicyQueries
    private lateinit var testAccount: Account

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_KEY = "test-policy-key"
        private val TEST_POLICY = JsonObject(mapOf("policyValue" to JsonPrimitive("test")))
    }

    @BeforeTest
    fun setup() {
        metadataPolicyQueries = mockk<MetadataPolicyQueries>(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.metadataPolicyQueries } returns metadataPolicyQueries
        metadataPolicyService = MetadataPolicyService()
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
    fun `create metadata policy succeeds`() {
        val metadataPolicy = MetadataPolicy(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            key = TEST_KEY,
            policy = TEST_POLICY.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            metadataPolicyQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY).executeAsOneOrNull()
        } returns null

        every {
            metadataPolicyQueries.create(testAccount.id, TEST_KEY, TEST_POLICY.toString()).executeAsOneOrNull()
        } returns metadataPolicy

        val result = metadataPolicyService.createPolicy(testAccount.toDTO(), TEST_KEY, TEST_POLICY)

        assertNotNull(result)
        assertEquals(TEST_KEY, result.key)
        assertEquals(TEST_POLICY.toString(), result.policy.toString())
        verify { metadataPolicyQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY) }
        verify { metadataPolicyQueries.create(testAccount.id, TEST_KEY, TEST_POLICY.toString()) }
    }

    @Test
    fun `create duplicate metadata policy fails with exception`() {
        val existingMetadataPolicy = MetadataPolicy(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            key = TEST_KEY,
            policy = TEST_POLICY.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { metadataPolicyQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY) } returns mockk {
            every { executeAsOneOrNull() } returns existingMetadataPolicy
        }

        assertFailsWith<EntityAlreadyExistsException> {
            metadataPolicyService.createPolicy(testAccount.toDTO(), TEST_KEY, TEST_POLICY)
        }
        verify { metadataPolicyQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY) }
    }

    @Test
    fun `find by account returns list of metadata policies`() {
        val policyList = listOf(
            MetadataPolicy(
                Uuid.random().toString(),
                testAccount.id,
                "key1",
                """{"policy": "value1"}""",
                FIXED_TIMESTAMP,
                null
            ),
            MetadataPolicy(
                Uuid.random().toString(),
                testAccount.id,
                "key2",
                """{"policy": "value2"}""",
                FIXED_TIMESTAMP,
                null
            )
        )

        every { metadataPolicyQueries.findByAccountId(testAccount.id).executeAsList() } returns policyList

        val result = metadataPolicyService.findByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("key1", result[0].key)
        assertEquals("key2", result[1].key)
        verify { metadataPolicyQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `delete metadata policy succeeds for valid id`() {
        val metadataPolicy = MetadataPolicy(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            key = TEST_KEY,
            policy = TEST_POLICY.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            metadataPolicyQueries.findById(metadataPolicy.id).executeAsOneOrNull()
        } returns metadataPolicy

        every {
            metadataPolicyQueries.delete(metadataPolicy.id).executeAsOneOrNull()
        } returns metadataPolicy

        val result = metadataPolicyService.deletePolicy(testAccount.toDTO(), metadataPolicy.id)

        assertNotNull(result)
        assertEquals(TEST_KEY, result.key)
        verify { metadataPolicyQueries.findById(metadataPolicy.id) }
        verify { metadataPolicyQueries.delete(metadataPolicy.id) }
    }

    @Test
    fun `delete non-existent metadata policy fails with not found exception`() {
        val nonExistentId = Uuid.random().toString()

        every {
            metadataPolicyQueries.findById(nonExistentId).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            metadataPolicyService.deletePolicy(testAccount.toDTO(), nonExistentId)
        }
        verify { metadataPolicyQueries.findById(nonExistentId) }
    }

    @Test
    fun `delete metadata policy from different account fails with not found exception`() {
        val differentAccountId = Uuid.random().toString()
        val metadataPolicy = MetadataPolicy(
            id = Uuid.random().toString(),
            account_id = differentAccountId, // Different account ID
            key = TEST_KEY,
            policy = TEST_POLICY.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            metadataPolicyQueries.findById(metadataPolicy.id).executeAsOneOrNull()
        } returns metadataPolicy

        assertFailsWith<NotFoundException> {
            metadataPolicyService.deletePolicy(testAccount.toDTO(), metadataPolicy.id)
        }
        verify { metadataPolicyQueries.findById(metadataPolicy.id) }
    }

    @Test
    fun `create metadata policy fails with unexpected error`() {
        val unexpectedError = RuntimeException("Unexpected database error")

        every {
            Persistence.metadataPolicyQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY)
        } returns mockk {
            every { executeAsOneOrNull() } returns null
        }

        every {
            Persistence.metadataPolicyQueries.create(testAccount.id, TEST_KEY, TEST_POLICY.toString())
        } throws unexpectedError

        val exception = assertFailsWith<RuntimeException> {
            metadataPolicyService.createPolicy(
                testAccount.toDTO(),
                TEST_KEY,
                TEST_POLICY
            )
        }

        assertEquals(unexpectedError.message, exception.message)
        verify {
            Persistence.metadataPolicyQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY)
        }
        verify {
            Persistence.metadataPolicyQueries.create(testAccount.id, TEST_KEY, TEST_POLICY.toString())
        }
    }
}
