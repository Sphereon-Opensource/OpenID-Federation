package com.sphereon.oid.fed.kms.local.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonObject

expect fun sign(payload: JsonObject, header: JWTHeader, key: Jwk): String
expect fun verify(jwt: String, key: Any, opts: Map<String, Any>): Boolean
