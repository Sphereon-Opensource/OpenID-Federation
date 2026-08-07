package com.sphereon.openid.fed.core.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class NoOpOidfJweServiceTest {

    @Test
    fun `encrypt fails when jwe disabled`() = runTest {
        val result = NoOpOidfJweService.encryptCompact("hello")
        assertTrue(result.isFailure)
    }

    @Test
    fun `decrypt fails when jwe disabled`() = runTest {
        val result = NoOpOidfJweService.decryptCompact("a.b.c.d.e")
        assertTrue(result.isFailure)
    }
}
