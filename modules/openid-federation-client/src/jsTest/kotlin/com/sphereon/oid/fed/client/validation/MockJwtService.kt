package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.common.jwt.IJwtServiceJS
import com.sphereon.oid.fed.common.jwt.JwtSignInput
import com.sphereon.oid.fed.common.jwt.JwtVerifyInput
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlin.js.JSON.stringify
import kotlin.js.Promise

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

        fun sign(key: Any?, signOptions: Any?): Promise<String> {
            definedExternally
        }
    }

    fun generateKeyPair(alg: String, options: dynamic = definedExternally): dynamic
    fun jwtVerify(jwt: String, key: Any, options: dynamic = definedExternally): dynamic
    fun exportJWK(key: dynamic): dynamic
    fun importJWK(jwk: dynamic, alg: String, options: dynamic = definedExternally): dynamic
}

fun convertToJwk(keyPair: dynamic): Jwk {
    val privateJWK = Jose.exportJWK(keyPair.privateKey)
    val publicJWK = Jose.exportJWK(keyPair.publicKey)
    return Jwk(
        crv = privateJWK.crv,
        d = privateJWK.d,
        kty = privateJWK.kty,
        x = privateJWK.x,
        y = privateJWK.y,
        alg = publicJWK.alg,
        kid = publicJWK.kid,
        use = publicJWK.use,
        x5c = publicJWK.x5c,
        x5t = publicJWK.x5t,
        x5tS256 = privateJWK.x5tS256,
        x5u = publicJWK.x5u,
        dp = privateJWK.dp,
        dq = privateJWK.dq,
        e = privateJWK.e,
        n = privateJWK.n,
        p = privateJWK.p,
        q = privateJWK.q,
        qi = privateJWK.qi
    )
}

class MockJwtService: IJwtServiceJS {

    override fun sign(input: JwtSignInput): Promise<String> {
        return Jose.SignJWT(JSON.parse<Any>(stringify(input.payload)))
            .setProtectedHeader(JSON.parse<Any>(stringify(input.header)))
            .sign(key = input.key, null)
    }

    override fun verify(input: JwtVerifyInput): Promise<Boolean> {
        val publicKey = Jose.importJWK(input.key, alg = input.key.alg ?: "RS256")
        return Promise.resolve(Jose.jwtVerify(input.jwt, publicKey) !== undefined)
    }
}
