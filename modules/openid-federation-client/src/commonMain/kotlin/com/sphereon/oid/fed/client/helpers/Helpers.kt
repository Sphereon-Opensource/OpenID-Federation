package com.sphereon.oid.fed.client.helpers

import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

fun getEntityConfigurationEndpoint(iss: String): String {
    return "${if (iss.endsWith("/")) iss.dropLast(1) else iss}/.well-known/openid-federation"
}

fun getSubordinateStatementEndpoint(fetchEndpoint: String, sub: String): String {
    return "${fetchEndpoint}?sub=$sub"
}

fun findKeyInJwks(keys: Array<Jwk>, kid: String, json: Json): Jwk? {
    val key = keys.firstOrNull { it.kid?.trim() == kid.trim() }

    return key
}

fun checkKidInJwks(keys: Array<Jwk>, kid: String): Boolean {
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