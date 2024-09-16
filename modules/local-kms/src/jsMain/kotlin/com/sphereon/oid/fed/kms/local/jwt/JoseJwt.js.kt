package com.sphereon.oid.fed.kms.local.jwt

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    payload: JsonObject, header: JWTHeader, key: Jwk
): String {
    val privateKey = key.privateKey ?: throw IllegalArgumentException("JWK private key is required")

    return Jose.SignJWT(JSON.parse<Any>(Json.encodeToString(payload)))
        .setProtectedHeader(JSON.parse<Any>(Json.encodeToString(header)))
        .sign(key = privateKey, signOptions = opts)
}

@ExperimentalJsExport
@JsExport
actual fun verify(
    jwt: String,
    key: Any,
    opts: Map<String, Any>
): Boolean {
    return Jose.jwtVerify(jwt, key, opts)
}
