package com.sphereon.openid.fed.services.clientauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class InMemoryFederationClientAssertionJtiStoreTest {

    @Test
    fun recordIfNew_rejectsReplay() = runTest {
        val store = InMemoryFederationClientAssertionJtiStore()
        val exp = Clock.System.now().epochSeconds + 120
        assertTrue(store.recordIfNew("https://client.example", "jti-1", exp))
        assertFalse(store.recordIfNew("https://client.example", "jti-1", exp))
        assertTrue(store.recordIfNew("https://client.example", "jti-2", exp))
        assertTrue(store.recordIfNew("https://other.example", "jti-1", exp))
    }
}
