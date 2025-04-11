package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.admin.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.admin.NotFoundException
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Metadata
import com.sphereon.oid.fed.persistence.models.MetadataQueries
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
class MetadataServiceTest {
    private lateinit var metadataService: MetadataService
    private lateinit var metadataQueries: MetadataQueries
    private lateinit var testAccount: Account

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
        private const val TEST_KEY = "test-metadata-key"
        private val TEST_METADATA = JsonObject(mapOf("key" to JsonPrimitive("value")))
    }

    @BeforeTest
    fun setup() {
        metadataQueries = mockk<MetadataQueries>(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.metadataQueries } returns metadataQueries
        metadataService = MetadataService()
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
    fun `create entity configuration metadata succeeds`() {
        val metadata = Metadata(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            key = TEST_KEY,
            metadata = TEST_METADATA.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            metadataQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY).executeAsOneOrNull()
        } returns null

        every {
            metadataQueries.create(testAccount.id, TEST_KEY, TEST_METADATA.toString()).executeAsOneOrNull()
        } returns metadata

        val result = metadataService.createMetadata(testAccount.toDTO(), TEST_KEY, TEST_METADATA)

        assertNotNull(result)
        assertEquals(TEST_KEY, result.key)
        assertEquals(TEST_METADATA.toString(), result.metadata.toString())
        verify { metadataQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY) }
        verify { metadataQueries.create(testAccount.id, TEST_KEY, TEST_METADATA.toString()) }
    }

    @Test
    fun `create duplicate metadata fails with exception`() {
        val existingMetadata = Metadata(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            key = TEST_KEY,
            metadata = TEST_METADATA.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every { metadataQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY) } returns mockk {
            every { executeAsOneOrNull() } returns existingMetadata
        }

        assertFailsWith<EntityAlreadyExistsException> {
            metadataService.createMetadata(testAccount.toDTO(), TEST_KEY, TEST_METADATA)
        }
        verify { metadataQueries.findByAccountIdAndKey(testAccount.id, TEST_KEY) }
    }

    @Test
    fun `find by account returns list of metadata`() {
        val metadataList = listOf(
            Metadata(Uuid.random().toString(), testAccount.id, "key1", """{"test": "value1"}""", FIXED_TIMESTAMP, null),
            Metadata(Uuid.random().toString(), testAccount.id, "key2", """{"test": "value2"}""", FIXED_TIMESTAMP, null)
        )

        every { metadataQueries.findByAccountId(testAccount.id).executeAsList() } returns metadataList

        val result = metadataService.findByAccount(testAccount.toDTO())

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("key1", result[0].key)
        assertEquals("key2", result[1].key)
        verify { metadataQueries.findByAccountId(testAccount.id) }
    }

    @Test
    fun `delete metadata succeeds for valid id`() {
        val metadata = Metadata(
            id = Uuid.random().toString(),
            account_id = testAccount.id,
            key = TEST_KEY,
            metadata = TEST_METADATA.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            metadataQueries.findById(metadata.id).executeAsOneOrNull()
        } returns metadata

        every {
            metadataQueries.delete(metadata.id).executeAsOneOrNull()
        } returns metadata

        val result = metadataService.deleteMetadata(testAccount.toDTO(), metadata.id)

        assertNotNull(result)
        assertEquals(TEST_KEY, result.key)
        verify { metadataQueries.findById(metadata.id) }
        verify { metadataQueries.delete(metadata.id) }
    }


    @Test
    fun `delete non-existent metadata fails with not found exception`() {
        val nonExistentId = Uuid.random().toString()

        every {
            metadataQueries.findById(nonExistentId).executeAsOneOrNull()
        } returns null

        assertFailsWith<NotFoundException> {
            metadataService.deleteMetadata(testAccount.toDTO(), nonExistentId)
        }
        verify { metadataQueries.findById(nonExistentId) }
    }

    @Test
    fun `delete metadata from different account fails with not found exception`() {
        val differentAccountId = Uuid.random().toString()
        val metadata = Metadata(
            id = Uuid.random().toString(),
            account_id = differentAccountId, // Different account ID
            key = TEST_KEY,
            metadata = TEST_METADATA.toString(),
            created_at = FIXED_TIMESTAMP,
            deleted_at = null
        )

        every {
            metadataQueries.findById(metadata.id).executeAsOneOrNull()
        } returns metadata

        assertFailsWith<NotFoundException> {
            metadataService.deleteMetadata(testAccount.toDTO(), metadata.id)
        }
        verify { metadataQueries.findById(metadata.id) }
    }
}
