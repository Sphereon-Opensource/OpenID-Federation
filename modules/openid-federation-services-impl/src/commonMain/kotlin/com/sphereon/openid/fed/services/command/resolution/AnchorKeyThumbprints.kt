package com.sphereon.openid.fed.services.command.resolution

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** RFC 7638 uses required key members in lexicographic order, excluding kid and alg. */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun publishedKeyThumbprints(configuration: JsonObject?): List<String> {
    val keys = (configuration?.get("jwks") as? JsonObject)?.get("keys") as? JsonArray ?: return emptyList()
    val thumbprints = mutableSetOf<String>()
    for (element in keys) {
        val key = element as? JsonObject ?: continue
        val members = when (key.string("kty")) {
            "RSA" -> listOf("e", "kty", "n")
            "EC" -> listOf("crv", "kty", "x", "y")
            "OKP" -> listOf("crv", "kty", "x")
            "oct" -> listOf("k", "kty")
            else -> continue
        }
        if (members.any { (key[it] as? JsonPrimitive)?.isString != true }) continue
        val canonical = JsonObject(members.associateWith { key.getValue(it) }).toString().encodeToByteArray()
        val digest = CryptographyProvider.Default.get(SHA256).hasher().hash(canonical)
        thumbprints += Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
    }
    return thumbprints.sorted()
}
