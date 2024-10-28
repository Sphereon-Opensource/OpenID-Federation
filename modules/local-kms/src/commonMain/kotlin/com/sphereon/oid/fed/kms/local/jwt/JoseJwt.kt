package com.sphereon.oid.fed.kms.local.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import kotlinx.serialization.json.JsonObject

expect fun sign(payload: JsonObject, header: JWTHeader, key: JwkWithPrivateKey): String
expect fun verify(jwt: String, key: Jwk): Boolean
