package com.sphereon.openid.fed.client.helpers

import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.core.api.http.util.validateHttpsUrlSyntax
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock

fun getEntityConfigurationEndpoint(iss: String): String {
    return "${if (iss.endsWith("/")) iss.dropLast(1) else iss}/.well-known/openid-federation"
}

/**
 * The fetch request URL for [sub] (OpenID Federation 1.1 sections 5.1.1 and 8.1.1). The endpoint MAY carry a query
 * component, which is kept; `sub` is form-encoded and replaces a `sub` parameter the endpoint already has, as a
 * `source_endpoint` published as the full fetch URL of a statement does.
 */
fun getSubordinateStatementEndpoint(fetchEndpoint: String, sub: String): String {
    val base = fetchEndpoint.substringBefore('?')
    val kept = fetchEndpoint.substringAfter('?', "").split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != "sub" }
    return (kept + "sub=${sub.encodeURLParameter()}").joinToString("&", prefix = "$base?")
}

/**
 * Whether [url] may be used as a federation endpoint received from another entity: an https URL that MAY have a port,
 * path and query but MUST NOT have a fragment (OpenID Federation 1.1 section 5.1.1). Anything else is refused, never
 * repaired.
 */
fun isFederationEndpointUrl(url: String): Boolean =
    '#' !in url && validateHttpsUrlSyntax(url, allowQuery = true).isOk

fun findKeyInJwks(keys: Array<Jwk>, kid: String): Jwk? {
    // OpenID Federation 1.1 section 16: compare code point by code point, without trimming.
    val key = keys.firstOrNull { it.kid == kid }

    return key
}

fun checkKidInJwks(keys: List<Jwk>, kid: String): Boolean {
    for (key in keys) {
        if (key.kid == kid) {
            return true
        }
    }
    return false
}

fun getCurrentEpochTimeSeconds(): Long {
    return Clock.System.now().epochSeconds
}
