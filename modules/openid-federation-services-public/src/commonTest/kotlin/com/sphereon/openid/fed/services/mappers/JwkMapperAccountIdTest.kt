package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.persistence.models.Jwk as JwkEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class JwkMapperAccountIdTest {
    @Test
    fun persistedAccountIdIsCopiedToTenantJwkOwner() {
        val persisted = JwkEntity(
            id = "123e4567-e89b-12d3-a456-426614174000",
            account_id = "223e4567-e89b-12d3-a456-426614174000",
            alg = "ES256",
            kid = "mapper-account-id-key",
            kms = "memory",
            kms_key_ref = "mapper-account-id-key-ref",
            key = """{"kty":"EC","crv":"P-256","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0","kid":"mapper-account-id-key","use":"sig","alg":"ES256"}""",
            created_at = null,
            revoked_at = null,
            revoked_reason = null,
        )

        val mapped = persisted.toDTO()

        assertEquals("223e4567-e89b-12d3-a456-426614174000", mapped.accountId)
        assertEquals(persisted.id, mapped.id)
        assertEquals("mapper-account-id-key", mapped.kid)
    }
}
