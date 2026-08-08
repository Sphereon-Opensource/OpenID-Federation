package com.sphereon.openid.fed.account

import com.sphereon.openid.fed.common.Constants
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
