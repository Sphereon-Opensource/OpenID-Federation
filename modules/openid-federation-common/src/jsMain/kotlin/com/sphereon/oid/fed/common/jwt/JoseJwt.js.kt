package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@JsModule("jose")
@JsNonModule
external object Jose {
    class SignJWT {
        constructor(payload: dynamic) {
            definedExternally
        }

        fun setProtectedHeader(protectedHeader: dynamic): SignJWT {
            definedExternally
        }

        fun sign(key: Any?, signOptions: Any?): String {
            definedExternally
        }
    }

    fun generateKeyPair(alg: String, options: dynamic = definedExternally): dynamic
    fun jwtVerify(jwt: String, key: Any, options: dynamic = definedExternally): dynamic
}

@ExperimentalJsExport
@JsExport
actual fun sign(
    payload: JsonObject, header: JWTHeader, opts: Map<String, Any>
): String {
    val privateKey = opts["privateKey"] ?: throw IllegalArgumentException("JWK private key is required")

    return Jose.SignJWT(JSON.parse<Any>(Json.encodeToString(payload)))
        .setProtectedHeader(JSON.parse<Any>(Json.encodeToString(header)))
        .sign(key = privateKey, signOptions = opts)
}

@ExperimentalJsExport
@JsExport
actual fun verify(
    jwt: String, key: Any, opts: Map<String, Any>
): Boolean {
    return Jose.jwtVerify(jwt, key, opts)
}
