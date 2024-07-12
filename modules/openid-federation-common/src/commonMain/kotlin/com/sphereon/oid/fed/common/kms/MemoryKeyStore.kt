package com.sphereon.oid.fed.kms

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.concurrent.ConcurrentHashMap

class MemoryKeyStore : AbstractKeyStore {

    private val keyStore = ConcurrentHashMap<String, JWK>()

    override fun importKey(key: JWK): Boolean {
        if (key.keyID == null) throw IllegalArgumentException("Key ID cannot be null")
        keyStore[key.keyID] = key
        return keyStore.containsKey(key.keyID)
    }

    override fun getKey(kid: String): JWK? {
        return keyStore[kid]
    }

    override fun deleteKey(kid: String): Boolean {
        return keyStore.remove(kid) != null
    }

    override fun listKeys(vararg kid: String): List<JWK> {
        if (kid.isNotEmpty()) {
            return kid.mapNotNull { keyStore[it] }
        }
        return keyStore.values.toList()
    }

    override fun sign(kid: String, payload: String): String {
        val privateKey = (this.getKey(kid) as RSAKey).toRSAPrivateKey()

        val claims = JWTClaimsSet.parse(payload)

        val signer = RSASSASigner(privateKey)
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
            claims
        )
        jwt.sign(signer)
        return jwt.serialize()
    }
}
