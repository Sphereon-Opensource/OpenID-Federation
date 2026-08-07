package com.sphereon.openid.fed.core.tenant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityModeTest {

    @Test
    fun parse_defaults_to_account() {
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse(null))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse(""))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("   "))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("banana"))
    }

    @Test
    fun parse_account_and_aliases() {
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("account"))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("ACCOUNT"))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("accounts"))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("legacy"))
    }

    @Test
    fun parse_external_and_aliases() {
        assertEquals(IdentityMode.EXTERNAL, IdentityMode.parse("external"))
        assertEquals(IdentityMode.EXTERNAL, IdentityMode.parse("EXTERNAL"))
        assertEquals(IdentityMode.EXTERNAL, IdentityMode.parse("platform"))
        assertEquals(IdentityMode.EXTERNAL, IdentityMode.parse("idk"))
        assertEquals(IdentityMode.EXTERNAL, IdentityMode.parse("session"))
    }

    @Test
    fun identity_config_flags() {
        val account = IdentityConfig(mode = IdentityMode.ACCOUNT)
        assertTrue(account.isAccount)
        assertFalse(account.isExternal)

        val external = IdentityConfig(mode = IdentityMode.EXTERNAL, externalRootTenantId = "default")
        assertTrue(external.isExternal)
        assertFalse(external.isAccount)
        assertEquals("default", external.externalRootTenantId)
    }

    @Test
    fun header_allow_list_defaults_to_empty_deny() {
        val cfg = IdentityConfig()
        assertFalse(cfg.accountHeaderAllowsAnyAuthenticated)
        assertTrue(cfg.accountHeaderAllowedPrincipals.isEmpty())
        assertEquals("sub", cfg.accountHeaderPrincipalClaim)
    }

    @Test
    fun session_alignment_parse() {
        assertEquals(SessionAlignment.ACCOUNT, SessionAlignment.parse(null))
        assertEquals(SessionAlignment.FIXED, SessionAlignment.parse("fixed"))
        assertEquals(SessionAlignment.ACCOUNT, SessionAlignment.parse("l2"))
    }
}
