package com.sphereon.openid.fed.services

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Structural compact-JWT payload reads for persistence metadata (e.g. `exp`).
 * This is not JOSE signature verification.
 */
object CompactEntityStatementJwt {
    @OptIn(ExperimentalEncodingApi::class)
    fun expEpochSeconds(compact: String): Long? {
        val parts = compact.split('.')
        if (parts.size < 2 || parts[1].isBlank()) return null
        return try {
            val json = Json.parseToJsonElement(
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(parts[1]).decodeToString(),
            ) as? JsonObject ?: return null
            val primitive = json["exp"] as? JsonPrimitive ?: return null
            primitive.longOrNull
                ?: primitive.doubleOrNull?.toLong()
                ?: primitive.contentOrNull?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
