package com.sphereon.openid.fed.admin

import kotlinx.serialization.json.JsonObject

/**
 * Host-owned key authority beneath the shared document and publication workflow.
 * Preparation must not insert a federation key or publish anything. The descriptor is persisted
 * only by the owning document transaction; its opaque KMS coordinates must survive a restart.
 */
interface AdminKeyMaterialPort {
    suspend fun prepare(scope: AdminNodeScope, keyId: String, algorithm: String): AdminPreparedKey

    /** Reuse only a signing route derived from an existing canonical key; never generate or delete material. */
    suspend fun prepareExisting(scope: AdminNodeScope, key: AdminPreparedKey, sourceKeyId: String): AdminPreparedKey = key

    /** Sign the already validated claims using exactly the persisted descriptor. */
    suspend fun sign(scope: AdminNodeScope, key: AdminPreparedKey, header: JsonObject, claims: JsonObject): String

    /** Only material prepared by this invocation and never committed may be discarded. */
    suspend fun discard(scope: AdminNodeScope, keyId: String): Boolean
}
