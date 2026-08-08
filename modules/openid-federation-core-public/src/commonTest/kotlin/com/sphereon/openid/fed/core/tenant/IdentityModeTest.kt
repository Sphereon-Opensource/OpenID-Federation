package com.sphereon.openid.fed.core.tenant

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityModeTest {

    @AfterTest
    fun tearDown() {
        IdentityInstallState.resetForTests()
    }

    @Test
    fun parse_blank_uses_auto_defaults() {
        // Blank parse goes through IdentityModeDefaults (no upgrade, classpath-dependent)
        val blank = IdentityMode.parse(null)
        assertTrue(blank == IdentityMode.ACCOUNT || blank == IdentityMode.EXTERNAL)
        assertNull(IdentityMode.parseExplicit(null))
        assertNull(IdentityMode.parseExplicit(""))
        assertNull(IdentityMode.parseExplicit("   "))
    }

    @Test
    fun parse_unknown_non_blank_is_account() {
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parse("banana"))
        assertEquals(IdentityMode.ACCOUNT, IdentityMode.parseExplicit("banana"))
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
    fun auto_default_upgrade_is_account() {
        assertEquals(
            IdentityMode.ACCOUNT,
            IdentityModeDefaults.resolve(
                explicitMode = null,
                accountModulesPresent = false,
                isExistingDatabase = true,
            ),
        )
    }

    @Test
    fun auto_default_greenfield_with_account_modules_is_account() {
        assertEquals(
            IdentityMode.ACCOUNT,
            IdentityModeDefaults.resolve(
                explicitMode = null,
                accountModulesPresent = true,
                isExistingDatabase = false,
            ),
        )
    }

    @Test
    fun auto_default_greenfield_without_account_modules_is_external() {
        assertEquals(
            IdentityMode.EXTERNAL,
            IdentityModeDefaults.resolve(
                explicitMode = null,
                accountModulesPresent = false,
                isExistingDatabase = false,
            ),
        )
    }

    @Test
    fun explicit_external_wins_over_upgrade() {
        assertEquals(
            IdentityMode.EXTERNAL,
            IdentityModeDefaults.resolve(
                explicitMode = "external",
                accountModulesPresent = true,
                isExistingDatabase = true,
            ),
        )
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
