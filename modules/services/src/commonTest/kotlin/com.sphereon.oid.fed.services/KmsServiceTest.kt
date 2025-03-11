package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.crypto.kms.azure.AzureKeyVaultCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class KmsServiceTest {

    @Test
    fun `getKmsProvider returns EcDSACryptoProvider when using memory provider`() {
        val kmsService = KmsService.createMemoryKms()
        val provider = kmsService.getKmsProvider()

        assertNotNull(provider)
        assertIs<EcDSACryptoProvider>(provider)
    }

    @Test
    fun `getKmsProvider returns AzureKeyVaultCryptoProvider when using Azure provider`() {
        // Create KmsService with placeholder configuration
        val kmsService = KmsService.createAzureKms(
            applicationId = "test-app-id",
            keyvaultUrl = "https://test-vault.vault.azure.net/",
            tenantId = "test-tenant-id",
            clientId = "test-client-id",
            clientSecret = "test-client-secret"
        )

        val provider = kmsService.getKmsProvider()

        assertNotNull(provider)
        assertIs<AzureKeyVaultCryptoProvider>(provider)
    }

    @Test
    fun `fromString returns correct KmsProviderType for valid input`() {
        assertEquals(KmsType.MEMORY, KmsType.fromString("memory"))
        assertEquals(KmsType.MEMORY, KmsType.fromString("MeMoRy"))
        assertEquals(KmsType.AZURE, KmsType.fromString("azure"))
        assertEquals(KmsType.AZURE, KmsType.fromString("AZURE"))
    }

    @Test
    fun `fromString throws SecurityException for invalid input`() {
        assertFailsWith<SecurityException> {
            KmsType.fromString("invalid")
        }

        assertFailsWith<SecurityException> {
            KmsType.fromString("")
        }
    }
}
