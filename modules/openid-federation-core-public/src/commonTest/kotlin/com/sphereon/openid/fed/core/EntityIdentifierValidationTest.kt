/*
 * (c) 2026 Sphereon International B.V.
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.core

import com.sphereon.openid.fed.core.isValidEntityIdentifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityIdentifierValidationTest {
    @Test
    fun signedPortIsInvalidThroughExistingIdentifierEntryPoint() {
        assertFalse(isValidEntityIdentifier("https://issuer.example:+443/Tenant"))
    }

    @Test
    fun illegalRawHostIsInvalidThroughExistingIdentifierEntryPoint() {
        assertFalse(isValidEntityIdentifier("https://bad|host.example/Tenant"))
    }

    @Test
    fun illegalRawPathAndNonAsciiComponentsAreInvalidThroughExistingIdentifierEntryPoint() {
        for (invalid in listOf("https://issuer.example/Tenant|Case", "https://éxample.test/Tenant", "https://issuer.example/Ténant")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun malformedIpv6GroupsAndEmbeddedIpv4AreInvalidThroughExistingIdentifierEntryPoint() {
        for (invalid in listOf("https://[1::2::3]/Tenant", "https://[1:2:3:4:5:6:7]/Tenant", "https://[::ffff:192.00.2.128]/Tenant", "https://[::ffff:256.0.2.128]/Tenant")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun ipvFutureVersionAndAddressRequireAsciiGrammarThroughExistingIdentifierEntryPoint() {
        for (invalid in listOf("https://[vG.address]/Tenant", "https://[VＦ.address]/Tenant", "https://[v1.address|bad]/Tenant", "https://[v1.]/Tenant")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun exactEmptyZeroPaddedPortAndUppercaseIpvFutureRemainIdentifierSyntaxControls() {
        for (identifier in listOf(
            "https://issuer.example:/Tenant", "https://issuer.example:000443/Tenant",
            "https://issuer.example:0/Tenant", "https://[Vf.Future:Address]/Tenant",
            "https://[2001:db8:0:0:0:0:192.0.2.128]/Tenant",
        )) assertTrue(isValidEntityIdentifier(identifier), identifier)
    }

    @Test
    fun validIdentifiersKeepTheirExactCasePathEncodingAndPorts() {
        for (identifier in listOf(
            "https://issuer.example/Tenant/Case%2FSensitive",
            "https://issuer.example/Tenant/",
            "https://issuer.example:443/Tenant",
            "https://issuer.example:0/Tenant",
            "https://issuer.example:8443/Tenant",
            "https://issuer.example:65535/Tenant",
            "HTTPS://issuer.example/Tenant/CaseSensitive",
            "https://issuer.example/Tenant/@administrator",
        )) {
            assertTrue(isValidEntityIdentifier(identifier), identifier)
        }
    }

    @Test
    fun localAndIpIdentifiersAreNotRejectedAsOutboundNetworkTargets() {
        for (identifier in listOf(
            "https://localhost/entity",
            "https://127.0.0.1/entity",
            "https://[2001:db8::1]/entity",
        )) {
            assertTrue(isValidEntityIdentifier(identifier), identifier)
        }
    }

    @Test
    fun blankPaddingRawWhitespaceAndControlCharactersAreInvalid() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/entity"))
        for (invalid in listOf(
            "",
            " ",
            " https://issuer.example/entity",
            "https://issuer.example/entity ",
            "https://issuer.example/with space",
            "https://issuer.example/entity\n",
            "https://issuer.example/entity\t",
        )) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun httpAndRelativeIdentifiersAreInvalid() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/entity"))
        for (invalid in listOf("http://issuer.example/entity", "issuer.example/entity", "/entity", "//issuer.example/entity")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun missingOrEmptyAuthorityIsInvalid() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/entity"))
        for (invalid in listOf("https://", "https:///entity", "https://:8443/entity")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun malformedPortIsInvalidRatherThanThrowing() {
        assertTrue(isValidEntityIdentifier("https://issuer.example:8443/entity"))
        for (invalid in listOf("https://issuer.example:not-a-port/entity", "https://issuer.example:70000/entity")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun queryAndFragmentAreInvalidEvenWhenEmpty() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/entity"))
        for (invalid in listOf(
            "https://issuer.example/entity?query=one",
            "https://issuer.example/entity?",
            "https://issuer.example/entity#fragment",
            "https://issuer.example/entity#",
        )) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun malformedPercentEscapesAreInvalidButEncodedSeparatorIsValid() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/Case%2FSensitive"))
        for (invalid in listOf("https://issuer.example/%", "https://issuer.example/%G0")) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun authorityUserinfoIsInvalidWhileLiteralAtInPathIsValid() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/@contact"))
        for (invalid in listOf(
            "https://user@issuer.example/entity",
            "https://user:password@issuer.example/entity",
            "https://@issuer.example/entity",
        )) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }

    @Test
    fun rawBackslashIsInvalidInAuthorityOrPath() {
        assertTrue(isValidEntityIdentifier("https://issuer.example/entity"))
        for (invalid in listOf(
            "https://\\issuer.example/entity",
            "https://issuer.example/other\\entity",
        )) {
            assertFalse(isValidEntityIdentifier(invalid), invalid)
        }
    }
}
