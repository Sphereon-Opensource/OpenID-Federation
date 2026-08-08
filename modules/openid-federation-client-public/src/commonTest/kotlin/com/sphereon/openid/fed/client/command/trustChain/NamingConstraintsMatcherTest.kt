package com.sphereon.openid.fed.client.command.trustChain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamingConstraintsMatcherTest {

    @Test
    fun extractHostFromHttpsUrl() {
        assertEquals("op.example.com", NamingConstraintsMatcher.extractHost("https://op.example.com"))
        assertEquals("op.example.com", NamingConstraintsMatcher.extractHost("https://op.example.com/path"))
        assertEquals("op.example.com", NamingConstraintsMatcher.extractHost("https://op.example.com:8443/a"))
    }

    @Test
    fun domainConstraintLeadingDot() {
        // ".example.com" matches subdomains, not the apex
        assertTrue(NamingConstraintsMatcher.matches("https://host.example.com", ".example.com"))
        assertTrue(NamingConstraintsMatcher.matches("https://a.b.example.com/rp", ".example.com"))
        assertFalse(NamingConstraintsMatcher.matches("https://example.com", ".example.com"))
        assertFalse(NamingConstraintsMatcher.matches("https://example.org", ".example.com"))
    }

    @Test
    fun hostConstraintExact() {
        assertTrue(NamingConstraintsMatcher.matches("https://host.example.com/path", "host.example.com"))
        assertFalse(NamingConstraintsMatcher.matches("https://other.example.com", "host.example.com"))
        assertFalse(NamingConstraintsMatcher.matches("https://sub.host.example.com", "host.example.com"))
    }

    @Test
    fun excludedBeatsPermitted() {
        assertFalse(
            NamingConstraintsMatcher.isAllowed(
                entityIdentifier = "https://bad.example.com",
                permitted = listOf(".example.com"),
                excluded = listOf("bad.example.com")
            )
        )
        assertTrue(
            NamingConstraintsMatcher.isAllowed(
                entityIdentifier = "https://good.example.com",
                permitted = listOf(".example.com"),
                excluded = listOf("bad.example.com")
            )
        )
    }

    @Test
    fun emptyPermittedMeansAllExceptExcluded() {
        assertTrue(
            NamingConstraintsMatcher.isAllowed(
                "https://anywhere.example",
                permitted = null,
                excluded = null
            )
        )
        assertFalse(
            NamingConstraintsMatcher.isAllowed(
                "https://blocked.example",
                permitted = emptyList(),
                excluded = listOf("blocked.example")
            )
        )
    }

    @Test
    fun uriPrefixPatternsStillSupported() {
        assertTrue(
            NamingConstraintsMatcher.matches(
                "https://fed.example.com/org/leaf",
                "https://fed.example.com/org"
            )
        )
        assertFalse(
            NamingConstraintsMatcher.matches(
                "https://fed.example.com/other",
                "https://fed.example.com/org"
            )
        )
    }
}
