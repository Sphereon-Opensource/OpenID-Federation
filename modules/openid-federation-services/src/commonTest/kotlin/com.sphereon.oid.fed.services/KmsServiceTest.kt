package com.sphereon.oid.fed.services

import com.sphereon.crypto.jose.JwaAlgorithm
import com.sphereon.crypto.kms.aws.AwsKmsCryptoProvider
import com.sphereon.crypto.kms.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.ecdsa.EcDSACryptoProvider
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.openapi.models.JwtHeader
import com.sphereon.oid.fed.services.mappers.jsonSerialization
import com.sphereon.oid.fed.services.mappers.toJsonString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `getKmsProvider returns AwsKeyVaultCryptoProvider when using AWS provider`() {
        // Create KmsService with placeholder configuration
        val kmsService = KmsService.createAwsKms(
            applicationId = "test-app-id",
            region = "eu-west-1",
            accessKeyId = "test-access-key-id",
            secretAccessKey = "test-secret-access-key"
        )

        val provider = kmsService.getKmsProvider()

        assertNotNull(provider)
        assertIs<AwsKmsCryptoProvider>(provider)
    }

    @Test
    fun `fromString returns correct KmsProviderType for valid input`() {
        assertEquals(KmsType.MEMORY, KmsType.fromString("memory"))
        assertEquals(KmsType.MEMORY, KmsType.fromString("MeMoRy"))
        assertEquals(KmsType.AZURE, KmsType.fromString("azure"))
        assertEquals(KmsType.AZURE, KmsType.fromString("AZURE"))
        assertEquals(KmsType.AWS, KmsType.fromString("aws"))
        assertEquals(KmsType.AWS, KmsType.fromString("AWS"))
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


    @Test
    fun `create key with AWS KMS, sign JWT, map to Jwk model and verify signature`() = runTest {
        // Create KmsService with AWS provider
        val kmsService = KmsService.createAwsKms(
            applicationId = "test-app-id",
            region = System.getenv("AWS_REGION"),
            accessKeyId = System.getenv("AWS_ACCESS_KEY_ID"),
            secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY")
        )

        // Get the KMS provider
        val provider = kmsService.getKmsProvider()
        assertIs<AwsKmsCryptoProvider>(provider)

        // 1. Try to get an existing key or generate a new one
        var generatedKey = provider.generateKeyAsync()

        assertNotNull(generatedKey)
        assertNotNull(generatedKey.kid)
        assertNotNull(generatedKey.kmsKeyRef)

        // 2. Create a JWT header and payload
        val kid = requireNotNull(generatedKey.kid) { "Generated key must have a kid" }
        val header = JwtHeader(
            alg = JwaAlgorithm.ES256.value,
            kid = kid,
            typ = "JWT"
        )

        val payload = JsonObject(
            mapOf(
                "iss" to JsonPrimitive("test-issuer"),
                "sub" to JsonPrimitive("test-subject"),
                "iat" to JsonPrimitive((System.currentTimeMillis() / 1000).toInt()),
                "exp" to JsonPrimitive((System.currentTimeMillis() / 1000 + 3600).toInt()),
                "test-claim" to JsonPrimitive("test-value")
            )
        )

        // 3. Sign the JWT using the JwtService
        val jwtService = JwtService(provider)
        val kmsKeyRef = requireNotNull(generatedKey.kmsKeyRef) { "Generated key must have a kmsKeyRef" }
        val jwt = jwtService.sign(payload, header, kid, kmsKeyRef)

        // Verify the JWT format
        assertNotNull(jwt)
        val jwtParts = jwt.split(".")
        assertEquals(3, jwtParts.size, "JWT should have three parts separated by dots")

        // Verify the JWT signature using the CryptoService
        val cryptoSvc = cryptoService()
        val jwkJson = generatedKey.jose.publicJwk.toJsonString()
        val jwk: com.sphereon.oid.fed.openapi.models.Jwk = jsonSerialization.decodeFromString(jwkJson)
        val isValid = cryptoSvc.verify(jwt, jwk)
        if (!isValid) {
            // Let's print the JWK and JWT for easy debugging in case it is not valid
            println("JWK:\n${jwk.toJsonString()}\n\n")
            println("JWT: $jwt")
        }

        assertTrue(isValid, "JWT signature should be valid")

        // Verify the Jwk model properties
        assertNotNull(jwk)
        assertEquals(generatedKey.kid, jwk.kid)
        assertEquals(JwaAlgorithm.ES256.value, jwk.alg)
        assertEquals("EC", jwk.kty)
        assertEquals("P-256", jwk.crv)
        assertNotNull(jwk.x)
        assertNotNull(jwk.y)
    }
}
