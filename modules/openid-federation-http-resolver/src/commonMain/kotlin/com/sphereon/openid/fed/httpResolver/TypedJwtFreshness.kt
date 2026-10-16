package com.sphereon.openid.fed.httpResolver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun currentEpochSeconds(): Long = Clock.System.now().epochSeconds

/**
 * The last epoch second (exclusive) a typed federation JWT may be served from cache: the earlier of its signed `exp`
 * and the HTTP freshness lifetime (`Cache-Control: max-age` less `Age`). Null when it must not be cached: no numeric
 * `exp`, an unparsable payload, or `no-store`/`no-cache`.
 */
internal fun typedJwtFreshUntil(jwt: String, cacheControl: String?, age: String?, now: Long): Long? {
    val exp = jwtExpirySeconds(jwt) ?: return null
    val directives = cacheControl.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (directives.any { it == "no-store" || it == "no-cache" || it.startsWith("no-cache=") }) return null
    val maxAge = directives.firstNotNullOfOrNull { directive ->
        directive.takeIf { it.startsWith("max-age=") }?.substringAfter('=')?.trim('"')?.toLongOrNull()
    }
    val httpBound = maxAge?.let { now + (it - (age?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)) }
    return if (httpBound == null) exp else minOf(exp, httpBound)
}

/** Signed `exp` of a compact JWS, floored so a fractional NumericDate never extends the lifetime. */
@OptIn(ExperimentalEncodingApi::class)
internal fun jwtExpirySeconds(jwt: String): Long? {
    val parts = jwt.trim().split('.')
    if (parts.size != 3 || parts[1].isEmpty()) return null
    return try {
        val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
        val payload = Json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()) as? JsonObject ?: return null
        val exp = payload["exp"] as? JsonPrimitive ?: return null
        if (exp.isString) return null
        exp.content.toLongOrNull() ?: exp.doubleOrNull?.takeIf { it.isFinite() && it < 9.2e18 && it > -9.2e18 }
            ?.let { floor(it).toLong() }
    } catch (_: Exception) {
        null
    }
}
