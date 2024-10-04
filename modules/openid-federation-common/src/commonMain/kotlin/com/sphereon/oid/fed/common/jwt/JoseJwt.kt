package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonObject
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@ExperimentalJsExport
@JsExport
data class JwtSignInput (
    val payload: JsonObject,
    val header: JWTHeader,
    val key: Jwk
)

@ExperimentalJsExport
@JsExport
data class JwtVerifyInput (
    val jwt: String,
    val key: Jwk
)

@ExperimentalJsExport
@JsExport
interface JwtService {
    fun sign(input: JwtSignInput): String
    fun verify(input: JwtVerifyInput): Boolean
}
