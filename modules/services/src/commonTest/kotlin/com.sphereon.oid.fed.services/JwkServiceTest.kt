package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.JwkQueries
import io.mockk.*
import java.time.LocalDateTime
import kotlin.test.*

class JwkServiceTest {
    private lateinit var jwkService: JwkService
    private lateinit var kmsClient: KmsClient
    private lateinit var jwkQueries: JwkQueries

    companion object {
        private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.parse("2025-01-13T12:00:00")
    }

    @BeforeTest
    fun setup() {
        kmsClient = mockk<KmsClient>(relaxed = true)
        jwkQueries = mockk<JwkQueries>(relaxed = true)
        mockkObject(Persistence)
        every { Persistence.jwkQueries } returns jwkQueries
        jwkService = JwkService(kmsClient)
    }

    @AfterTest
    fun cleanup() {
        clearAllMocks()
        unmockkObject(Persistence)
    }

    @Test
    fun testCreateKey() {
        val account = Account(
            id = 1,
            username = "testUser",
            identifier = "test-identifier"
        )

        val expectedJwk = JwkWithPrivateKey(
            kty = "EC",
            crv = "P-256",
            x = "example-x",
            y = "example-y",
            kid = "test-kid-124",
            alg = "ES256",
            use = "sig"
        )

        every { kmsClient.generateKeyPair() } returns expectedJwk

        val result = jwkService.createKey(account)

        assertNotNull(result)
        assertEquals(expectedJwk.kid, result.kid)
        assertEquals(expectedJwk.use, result.use)
        assertEquals(expectedJwk.alg, result.alg)

        verify { kmsClient.generateKeyPair() }
        verify { jwkQueries.create(any(), any(), any()) }
    }
}
