package com.sphereon.openid.fed.account

import com.sphereon.openid.fed.common.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyAccountSessionTenantLookupTest {

    @Test
    fun usernameFromHeaders_prefers_account_header() {
        val headers = mapOf("X-Account-Username" to "tenant-a")
        val name = LegacyAccountSessionTenantLookup.usernameFromHeaders { headers[it] }
        assertEquals("tenant-a", name)
    }

    @Test
    fun usernameFromHeaders_defaults_to_root() {
        val name = LegacyAccountSessionTenantLookup.usernameFromHeaders { null }
        assertEquals(Constants.DEFAULT_ROOT_USERNAME, name)
    }

    @Test
    fun usernameFromHeaders_accepts_lowercase_header_name() {
        val headers = mapOf("x-account-username" to "leaf")
        val name = LegacyAccountSessionTenantLookup.usernameFromHeaders { headers[it] }
        assertEquals("leaf", name)
    }

    @Test
    fun resolveAccountId_does_not_hide_a_persistence_that_cannot_start() {
        // No datasource is configured in this test run, so the persistence layer cannot open. That is a fault of the
        // installation (or of the migration), not "database unavailable", and must reach the caller.
        assertFailsWith<IllegalStateException> { LegacyAccountSessionTenantLookup.resolveAccountId("anyone") }
    }
}
