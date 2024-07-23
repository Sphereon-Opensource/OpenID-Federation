package com.sphereon.oid.fed.common.jwt

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

@JsModule("uuid")
@JsNonModule
external object Uuid {
    fun v4(): String
}

@ExperimentalJsExport
@JsExport
actual fun sign(
    payload: String,
    opts: Map<String, Any>
): String {
    val privateKey = opts["privateKey"] ?: throw IllegalArgumentException("JWK private key is required")
    val header = opts["jwtHeader"] as String? ?: "{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"${Uuid.v4()}\"}"
    return Jose.SignJWT(JSON.parse<Any>(payload).asDynamic())
        .setProtectedHeader(JSON.parse<Any>(header).asDynamic())
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
