package com.sphereon.openid.fed.services.mappers

import kotlinx.serialization.json.Json

val jsonSerialization = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
