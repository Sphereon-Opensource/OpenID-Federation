package com.sphereon.openid.fed.admin

import com.sphereon.openid.fed.persistence.Persistence
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64

/** Exact signing-selection, key material and lifecycle fence, read again under the account lock at commit. */
internal fun adminKeyState(accountId: String,
    keys: com.sphereon.openid.fed.persistence.models.JwkQueries = Persistence.jwkQueries,
    selections: com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries = Persistence.accountSigningKeyQueries,
    documents: com.sphereon.openid.fed.persistence.models.AdminDocumentQueries = Persistence.adminDocumentQueries,
): String {
    val selection = selections.findByAccountId(accountId).executeAsOneOrNull()
    val snapshot = buildJsonObject {
        selection?.let { put("selected", it.jwk_id); put("revision", it.revision) }
        put("keys", JsonArray(keys.findAllByAccountId(accountId).executeAsList().sortedBy { it.id }.map { key ->
            buildJsonObject {
                put("id", key.id); put("kid", key.kid); put("alg", key.alg); put("kms", key.kms); put("ref", key.kms_key_ref)
                put("public", key.key); put("revoked", key.revoked_at?.toString()); put("expires", key.expires_at?.toString())
            }
        }))
        put("states", JsonArray(documents.list(accountId, "key-state").executeAsList().map { state ->
            buildJsonObject { put("id", state.resource_id); put("etag", state.etag) }
        }))
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray()))
}
