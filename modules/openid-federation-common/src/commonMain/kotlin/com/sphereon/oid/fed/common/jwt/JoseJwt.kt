package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.json.JsonObject

expect fun sign(payload: JsonObject, header: JWTHeader, opts: Map<String, Any>): String
expect fun verify(jwt: String, key: Any, opts: Map<String, Any>): Boolean
