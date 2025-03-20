package com.sphereon.oid.fed.services.mappers

import kotlinx.serialization.json.Json

val jsonSerialization = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}