package com.sphereon.openid.fed.core.tenant

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BearerTokenSupportTest {

    @Test
    fun extract_bearer_token() {
        assertEquals("abc.def.ghi", BearerTokenSupport.extractAccessToken("Bearer abc.def.ghi"))
        assertEquals("tok", BearerTokenSupport.extractAccessToken("DPoP tok"))
        assertNull(BearerTokenSupport.extractAccessToken("Basic xyz"))
        assertNull(BearerTokenSupport.extractAccessToken(null))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun platform_tenant_from_authorization_header() {
        val payload = """{"sub":"u1","tenant_id":"ten-9"}"""
        val b64 = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
        val jwt = "eyJhbGciOiJub25lIn0.$b64.sig"
        val tid = BearerTokenSupport.platformTenantFromAuthorizationHeader("Bearer $jwt")
        assertEquals("ten-9", tid)
    }

    @Test
    fun missing_tenant_claim_returns_null() {
        assertNull(BearerTokenSupport.platformTenantFromAuthorizationHeader("Bearer not-a-jwt"))
    }
}
