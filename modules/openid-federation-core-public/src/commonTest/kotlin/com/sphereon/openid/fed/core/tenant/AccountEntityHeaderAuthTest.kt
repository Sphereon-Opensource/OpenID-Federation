package com.sphereon.openid.fed.core.tenant

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountEntityHeaderAuthTest {

    private val anyAuth = IdentityConfig(
        mode = IdentityMode.ACCOUNT,
        accountHeaderAllowedPrincipals = listOf("*"),
    )

    private val allowOpsOnly = IdentityConfig(
        mode = IdentityMode.ACCOUNT,
        accountHeaderPrincipalClaim = "sub",
        accountHeaderAllowedPrincipals = listOf("ops-admin", "service-bot"),
    )

    @Test
    fun empty_allow_list_denies_header_use() {
        val denyAll = IdentityConfig(mode = IdentityMode.ACCOUNT)
        val claims = mapOf("sub" to JsonPrimitive("anyone"))
        assertFalse(AccountEntityHeaderAuth.mayUseEntityHeader(claims, denyAll))
        assertNotNull(
            AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(
                claims,
                { if (it.equals("X-Account-Username", true)) "tenant-a" else null },
                denyAll,
            ),
        )
    }

    @Test
    fun wildcard_allows_any_authenticated_principal() {
        val claims = mapOf("sub" to JsonPrimitive("legacy-e2e-user"))
        assertTrue(AccountEntityHeaderAuth.mayUseEntityHeader(claims, anyAuth))
        assertNull(
            AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(
                claims,
                { if (it.equals("X-Account-Username", true)) "tenant-a" else null },
                anyAuth,
            ),
        )
    }

    @Test
    fun allow_list_rejects_unknown_principal() {
        val claims = mapOf("sub" to JsonPrimitive("alice"))
        assertFalse(AccountEntityHeaderAuth.mayUseEntityHeader(claims, allowOpsOnly))
        val reason =
            AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(
                claims,
                { if (it.equals("X-Account-Username", true)) "tenant-a" else null },
                allowOpsOnly,
            )
        assertNotNull(reason)
        assertTrue(reason!!.contains("alice"))
    }

    @Test
    fun allow_list_accepts_configured_principal() {
        val claims = mapOf("sub" to JsonPrimitive("ops-admin"))
        assertTrue(AccountEntityHeaderAuth.mayUseEntityHeader(claims, allowOpsOnly))
        assertNull(
            AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(
                claims,
                { if (it.equals("X-Account-Username", true)) "tenant-a" else null },
                allowOpsOnly,
            ),
        )
    }

    @Test
    fun external_mode_never_allows_header() {
        val claims = mapOf("sub" to JsonPrimitive("anyone"))
        val external = IdentityConfig(mode = IdentityMode.EXTERNAL)
        assertFalse(AccountEntityHeaderAuth.mayUseEntityHeader(claims, external))
        assertNotNull(
            AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(
                claims,
                { if (it.equals("X-Account-Username", true)) "x" else null },
                external,
            ),
        )
    }

    @Test
    fun no_header_never_denied() {
        val claims = mapOf("sub" to JsonPrimitive("alice"))
        assertNull(
            AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(claims, { null }, allowOpsOnly),
        )
    }

    @Test
    fun custom_claim_name() {
        val cfg = IdentityConfig(
            mode = IdentityMode.ACCOUNT,
            accountHeaderPrincipalClaim = "preferred_username",
            accountHeaderAllowedPrincipals = listOf("root-ops"),
        )
        val claims = mapOf(
            "sub" to JsonPrimitive("uuid-1"),
            "preferred_username" to JsonPrimitive("root-ops"),
        )
        assertTrue(AccountEntityHeaderAuth.mayUseEntityHeader(claims, cfg))
    }
}
