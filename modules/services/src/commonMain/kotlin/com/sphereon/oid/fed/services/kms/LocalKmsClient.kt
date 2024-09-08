package com.sphereon.oid.fed.services.kms

import com.sphereon.oid.fed.kms.local.LocalKms
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO


import kotlinx.serialization.json.JsonObject

class LocalKmsClient : KmsClient {

    private val localKms = LocalKms()

    override fun generateKeyPair(): JwkAdminDTO {
        return localKms.generateKey()
    }

    override fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        return localKms.sign(header, payload, keyId)
    }

    override fun verify(token: String): Boolean {
        return com.sphereon.oid.fed.common.crypto.verify(token)
    }
}
