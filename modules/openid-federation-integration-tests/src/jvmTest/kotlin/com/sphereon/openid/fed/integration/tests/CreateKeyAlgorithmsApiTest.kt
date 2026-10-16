package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.openapi.models.TenantJwksResponse
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uses the existing authenticated production HTTP fixture and real software crypto/Postgres.
 * The fixture's key store is memory; this is not a restart/PKCS12 custody acceptance test. */
class CreateKeyAlgorithmsApiTest {
    @Test
    fun publicApiGeneratesAllFourAlgorithmsAndPersistsOnlyPublicKeyMaterial() = runTest {
        val username = "create-key-algorithms-${UUID.randomUUID()}"
        val baseUrl = adminTestBaseUrl()
        val client = createAuthenticatedAdminClient()
        var accountCreated = false
        try {
            val account = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(CreateAccount(identifier = "https://create-key-algorithms.example/$username", username = username))
            }
            assertEquals(HttpStatusCode.Created, account.status, "Account prerequisite failed")
            accountCreated = true
            for (algorithm in listOf("ES256", "ES384", "ES512", "RS256")) {
                val created = client.post("$baseUrl/keys") {
                    header("X-Account-Username", username)
                    contentType(ContentType.Application.Json)
                    setBody("""{"signatureAlgorithm":"$algorithm"}""")
                }
                assertEquals(HttpStatusCode.Created, created.status, "$algorithm key generation failed")
                val body = created.bodyAsText()
                val fields = adminTestJson.parseToJsonElement(body).jsonObject
                for (privateField in listOf("d", "p", "q", "dp", "dq", "qi", "dP", "dQ", "qInv", "oth", "k")) {
                    assertFalse(privateField in fields, "Public API disclosed private key field $privateField")
                }
                val key = adminTestJson.decodeFromString<TenantJwk>(body)
                assertEquals(algorithm, key.alg)
                assertTrue(key.kid.isNotBlank())
                if (algorithm == "RS256") {
                    assertEquals("RSA", key.kty)
                    val modulus = BigInteger(1, Base64.getUrlDecoder().decode(assertNotNull(key.n)))
                    val exponent = BigInteger(1, Base64.getUrlDecoder().decode(assertNotNull(key.e)))
                    val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
                    assertTrue(publicKey.modulus.bitLength() >= 2048, "RS256 modulus must have at least 2048 bits")
                    assertTrue(publicKey.publicExponent > BigInteger.ONE)
                } else {
                    assertEquals("EC", key.kty)
                    assertEquals(mapOf("ES256" to "P-256", "ES384" to "P-384", "ES512" to "P-521").getValue(algorithm), key.crv)
                    assertNotNull(key.x)
                    assertNotNull(key.y)
                }
                val listed = client.get("$baseUrl/keys") { header("X-Account-Username", username) }
                assertEquals(HttpStatusCode.OK, listed.status)
                val stored = adminTestJson.decodeFromString<TenantJwksResponse>(listed.bodyAsText()).jwks.single { it.id == key.id }
                assertEquals(key.kid, stored.kid)
                assertEquals(key.alg, stored.alg)
                assertEquals(key.kty, stored.kty)
                assertEquals(key.n, stored.n)
                assertEquals(key.e, stored.e)
                assertEquals(key.crv, stored.crv)
                assertEquals(key.x, stored.x)
                assertEquals(key.y, stored.y)
            }
        } finally {
            try {
                if (accountCreated) {
                    val deleted = client.delete("$baseUrl/accounts") { header("X-Account-Username", username) }
                    assertEquals(HttpStatusCode.OK, deleted.status, "Account cleanup failed")
                }
            } finally {
                client.close()
            }
        }
    }
}
