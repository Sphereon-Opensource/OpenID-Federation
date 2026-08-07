package com.sphereon.openid.fed.core.tenant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityModeTest {

    @Test
    fun parse_defaults_to_legacy_when_null_or_blank() {
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse(null))
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse(""))
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse("   "))
    }

    @Test
    fun parse_legacy_aliases() {
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse("legacy"))
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse("LEGACY"))
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse("account"))
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse("accounts"))
    }

    @Test
    fun parse_platform_aliases() {
        assertEquals(IdentityMode.PLATFORM, IdentityMode.parse("platform"))
        assertEquals(IdentityMode.PLATFORM, IdentityMode.parse("PLATFORM"))
        assertEquals(IdentityMode.PLATFORM, IdentityMode.parse("idk"))
        assertEquals(IdentityMode.PLATFORM, IdentityMode.parse("session"))
    }

    @Test
    fun parse_unknown_falls_back_to_legacy() {
        assertEquals(IdentityMode.LEGACY, IdentityMode.parse("banana"))
    }

    @Test
    fun identity_config_flags() {
        val legacy = IdentityConfig(mode = IdentityMode.LEGACY)
        assertTrue(legacy.isLegacy)
        assertFalse(legacy.isPlatform)
        assertTrue(legacy.isSessionAccountAligned)

        val platform = IdentityConfig(mode = IdentityMode.PLATFORM, platformRootTenantId = "default")
        assertTrue(platform.isPlatform)
        assertFalse(platform.isLegacy)
        assertEquals("default", platform.platformRootTenantId)
    }

    @Test
    fun session_alignment_parse() {
        assertEquals(SessionAlignment.ACCOUNT, SessionAlignment.parse(null))
        assertEquals(SessionAlignment.ACCOUNT, SessionAlignment.parse("account"))
        assertEquals(SessionAlignment.ACCOUNT, SessionAlignment.parse("l2"))
        assertEquals(SessionAlignment.FIXED, SessionAlignment.parse("fixed"))
        assertEquals(SessionAlignment.FIXED, SessionAlignment.parse("l1"))
    }

    @Test
    fun session_alignment_fixed_disables_account_align_flag() {
        val cfg = IdentityConfig(
            mode = IdentityMode.LEGACY,
            sessionAlignment = SessionAlignment.FIXED,
        )
        assertFalse(cfg.isSessionAccountAligned)
    }
}
