package com.sphereon.openid.fed.core.security

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvOpaqueSecretResolverTest {

    @Test
    fun resolve_from_oidf_secret_prefix() = runTest {
        val env = mapOf("OIDF_SECRET_DB_PASSWORD" to "s3cret")
        val resolver = EnvOpaqueSecretResolver { env[it] }

        val result = resolver.resolve("db-password")
        assertTrue(result.isOk)
        assertEquals("s3cret", result.value)
    }

    @Test
    fun resolve_from_normalized_id() = runTest {
        val env = mapOf("MY_SECRET" to "value")
        val resolver = EnvOpaqueSecretResolver { env[it] }

        val result = resolver.resolve("my.secret")
        assertTrue(result.isOk)
        assertEquals("value", result.value)
    }

    @Test
    fun resolve_missing_returns_err() = runTest {
        val resolver = EnvOpaqueSecretResolver { null }
        val result = resolver.resolve("does-not-exist")
        assertTrue(result.isErr)
        assertEquals("secret_not_found", result.error.code)
    }

    @Test
    fun secretIdKeyFor_appends_suffix() {
        assertEquals(
            "oidf.datasource.password.secret.id",
            secretIdKeyFor("oidf.datasource.password"),
        )
    }
}
