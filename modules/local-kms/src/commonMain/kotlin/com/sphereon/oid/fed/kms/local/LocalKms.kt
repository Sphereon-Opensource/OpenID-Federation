package com.sphereon.oid.fed.kms.local

import com.sphereon.oid.fed.kms.local.database.LocalKmsDatabase
import com.sphereon.oid.fed.kms.local.encryption.AesEncryption
import com.sphereon.oid.fed.kms.local.jwk.generateKeyPair
import com.sphereon.oid.fed.kms.local.jwt.sign
import com.sphereon.oid.fed.kms.local.jwt.verify
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import com.sphereon.oid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class LocalKms {

    private val database: LocalKmsDatabase = LocalKmsDatabase()
    private val aesEncryption: AesEncryption = AesEncryption()

    fun generateKey(): JwkWithPrivateKey {
        val jwk = generateKeyPair()

        database.insertKey(
            keyId = jwk.kid!!,
            key = aesEncryption.encrypt(Json.encodeToString(JwkWithPrivateKey.serializer(), jwk))
        )

        return jwk
    }

    fun sign(header: JwtHeader, payload: JsonObject, keyId: String): String {
        val jwk = database.getKey(keyId)

        val jwkObject: JwkWithPrivateKey = Json.decodeFromString(aesEncryption.decrypt(jwk.key))

        val mHeader = header.copy(alg = jwkObject.alg, kid = jwkObject.kid!!)

        return sign(header = mHeader, payload = payload, key = jwkObject)
    }

    fun verify(token: String, jwk: Jwk): Boolean {
        return verify(jwt = token, key = jwk)
    }
}
