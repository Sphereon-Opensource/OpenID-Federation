package com.sphereon.openid.fed.core.tenant

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformJwtTenantClaimsTest {

    @Test
    fun prefers_tenant_id_claim() {
        val claims = mapOf(
            "tid" to JsonPrimitive("azure-tid"),
            "tenant_id" to JsonPrimitive("primary-tenant"),
            "sub" to JsonPrimitive("user-1"),
        )
        assertEquals("primary-tenant", PlatformJwtTenantClaims.extractTenantId(claims))
    }

    @Test
    fun falls_back_to_tid() {
        val claims = mapOf("tid" to JsonPrimitive("azure-tid"))
        assertEquals("azure-tid", PlatformJwtTenantClaims.extractTenantId(claims))
    }

    @Test
    fun returns_null_when_no_tenant_claim() {
        val claims = mapOf("sub" to JsonPrimitive("user-1"), "iss" to JsonPrimitive("https://idp"))
        assertNull(PlatformJwtTenantClaims.extractTenantId(claims))
    }

    @Test
    fun ignores_blank_claims() {
        val claims = mapOf(
            "tenant_id" to JsonPrimitive("  "),
            "tenant" to JsonPrimitive("org-a"),
        )
        assertEquals("org-a", PlatformJwtTenantClaims.extractTenantId(claims))
    }
}
