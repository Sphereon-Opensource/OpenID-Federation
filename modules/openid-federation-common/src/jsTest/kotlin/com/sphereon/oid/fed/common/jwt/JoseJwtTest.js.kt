package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.common.jwt.Jose.generateKeyPair
import com.sphereon.oid.fed.openapi.models.JWKS
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

class JoseJwtTest {
    @OptIn(ExperimentalJsExport::class)
    @Test
    fun signTest() = runTest {
        val keyPair = (generateKeyPair("RS256") as Promise<dynamic>).await()
        val result = async {
            sign(
                JwtPayload(iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = JWKS()),
                JwtHeader(typ = "JWT", alg = "RS256", kid = "test"),
                mutableMapOf("privateKey" to keyPair.privateKey)
            )
        }
        assertTrue((result.await() as Promise<String>).await().startsWith("ey"))
    }

    @OptIn(ExperimentalJsExport::class)
    @Test
    fun verifyTest() = runTest {
        val keyPair = (generateKeyPair("RS256") as Promise<dynamic>).await()
        val signed = (sign(
            JwtPayload(iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = JWKS()),
            JwtHeader(typ = "JWT", alg = "RS256", kid = "test"),
            mutableMapOf("privateKey" to keyPair.privateKey)
        ) as Promise<dynamic>).await()
        val result = async { verify(signed, keyPair.publicKey, emptyMap()) }
        assertTrue((result.await()))
    }
}
