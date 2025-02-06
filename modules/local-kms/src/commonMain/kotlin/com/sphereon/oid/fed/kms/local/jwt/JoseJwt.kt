package com.sphereon.oid.fed.kms.local.jwt

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import com.sphereon.oid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.JsonObject

expect fun sign(payload: JsonObject, header: JwtHeader, key: JwkWithPrivateKey): String
expect fun verify(jwt: String, key: Jwk): Boolean
