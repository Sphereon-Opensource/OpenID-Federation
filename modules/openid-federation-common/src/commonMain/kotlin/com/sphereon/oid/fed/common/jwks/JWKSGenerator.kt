package com.sphereon.oid.fed.jwks

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sphereon.oid.fed.kms.AbstractKeyStore
import java.util.*

class JWKSGenerator (
    val kms: AbstractKeyStore
) {
    fun generateJWKS(kid: String? = null): JWK {
        val jwk = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .keyID(kid ?: UUID.randomUUID().toString())
            .generate()
        kms.importKey(jwk)
        return jwk.toPublicJWK()
    }

    fun getJWKSet(vararg kid: String): JWKSet {
        val keys = kms.listKeys(*kid)
        return JWKSet(keys.map { it.toPublicJWK() })
    }

    fun sign(kid: String, payload: String): String {
        return kms.sign(kid, payload)
    }
}
