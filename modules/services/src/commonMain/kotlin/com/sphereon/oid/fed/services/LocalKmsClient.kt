package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.kms.local.LocalKms
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonObject

class LocalKmsClient : KmsClient {

    private val localKms = LocalKms()

    override fun generateKeyPair(keyId: String) {
        return localKms.generateKey(keyId)
    }

    override fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        return localKms.sign(header, payload, keyId)
    }

    override fun verify(token: String, jwk: Jwk): Boolean {
        return localKms.verify(token, jwk)
    }
}