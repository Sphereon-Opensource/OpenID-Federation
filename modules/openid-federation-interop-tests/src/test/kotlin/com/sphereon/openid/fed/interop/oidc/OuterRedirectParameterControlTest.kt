package com.sphereon.openid.fed.interop.oidc

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OuterRedirectParameterControlTest {
    @Test
    fun `replaces existing unsigned callback and preserves signed request bytes`() {
        val request = "eyJhbGciOiJFUzUxMiJ9.eyJzdWIiOiJvZmZhIn0.signature"
        val encodedRequest = "eyJhbGciOiJFUzUxMiJ9.eyJzdWIiOiJvZmZhIn0.signature"
        val source = "https://sphereon.test/authorize?client_id=https%3A%2F%2Foffa.test&request=$encodedRequest&redirect_uri=https%3A%2F%2Foffa.test%2Fredirect&response_type=code"

        val changed = OuterRedirectParameterControl.replace(
            source,
            expectedOriginal = "https://offa.test/redirect",
            replacement = "https://attacker.test/callback",
        )
        val before = URI(source).rawQuery!!.split('&')
        val after = URI(changed).rawQuery!!.split('&')
        assertEquals(before.size, after.size)
        assertEquals(before.single { it.startsWith("request=") }, after.single { it.startsWith("request=") })
        assertEquals(before.single { it.startsWith("client_id=") }, after.single { it.startsWith("client_id=") })
        assertEquals(before.single { it.startsWith("response_type=") }, after.single { it.startsWith("response_type=") })
        assertEquals("https://attacker.test/callback", URLDecoder.decode(after.single { it.startsWith("redirect_uri=") }.substringAfter('='), UTF_8))
        assertEquals(3, request.split('.').size)
    }

    @Test
    fun `rejects absent or duplicate outer callback and duplicate signed request`() {
        val request = "request=eyJhbGci.eyJzdWI.sig"
        val noCallback = "https://sphereon.test/authorize?$request"
        val duplicateCallback = "https://sphereon.test/authorize?$request&redirect_uri=https%3A%2F%2Foffa.test%2Fredirect&redirect_uri=https%3A%2F%2Foffa.test%2Fredirect"
        val duplicateRequest = "https://sphereon.test/authorize?$request&request=eyJhbGci.eyJzdWI.sig&redirect_uri=https%3A%2F%2Foffa.test%2Fredirect"
        for (url in listOf(noCallback, duplicateCallback, duplicateRequest)) {
            assertFailsWith<IllegalArgumentException> {
                OuterRedirectParameterControl.replace(url, "https://offa.test/redirect", "https://attacker.test/callback")
            }
        }
    }

    @Test
    fun `requires exact expected callback and HTTPS Sphereon authorize endpoint`() {
        val query = "request=eyJhbGci.eyJzdWI.sig&redirect_uri=https%3A%2F%2Foffa.test%2Fredirect"
        assertFailsWith<IllegalArgumentException> {
            OuterRedirectParameterControl.replace("https://sphereon.test/authorize?$query", "https://different.test/callback", "https://attacker.test/callback")
        }
        assertFailsWith<IllegalArgumentException> {
            OuterRedirectParameterControl.replace("http://sphereon.test/authorize?$query", "https://offa.test/redirect", "https://attacker.test/callback")
        }
    }
}
