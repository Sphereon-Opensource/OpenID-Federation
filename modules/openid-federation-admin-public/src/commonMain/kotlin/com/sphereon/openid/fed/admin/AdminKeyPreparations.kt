@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.sphereon.openid.fed.admin

import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** Private authority records are never editable administration documents or signed claims. */
fun adminKeyPreparationKey(keyId: String) = AdminDocumentKey("key-preparation", keyId)

data class AdminStoredKeyMaterial(val key: AdminPreparedKey, val signingCapable: Boolean)

suspend fun adminNodeIncarnation(store: AdminDocumentStore, scope: AdminNodeScope): String {
    val key = AdminDocumentKey("node-incarnation")
    store.readCommitted(scope, key)?.let { return it.document.getValue("id").jsonPrimitive.content }
    val document = buildJsonObject { put("id", Uuid.random().toString()) }
    val created = store.commit(scope, listOf(AdminMutation(key, null, document)))?.single()
        ?: store.readCommitted(scope, key) ?: adminFail("precondition_failed", 412, "Node incarnation changed")
    return created.document.getValue("id").jsonPrimitive.content
}
