package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.common.jwt.Jose.generateKeyPair
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
        val result = async { sign("{ \"iss\": \"test\" }", mutableMapOf("privateKey" to keyPair.privateKey)) }
        assertTrue((result.await() as Promise<String>).await().startsWith("ey"))
    }

    @OptIn(ExperimentalJsExport::class)
    @Test
    fun verifyTest() = runTest {
        val keyPair = (generateKeyPair("RS256") as Promise<dynamic>).await()
        val signed = (sign("{ \"iss\": \"test\" }", mutableMapOf("privateKey" to keyPair.privateKey)) as Promise<dynamic>).await()
        val result = async { verify(signed, keyPair.publicKey) }
        assertTrue((result.await() as Promise<Boolean>).await())
    }
}
