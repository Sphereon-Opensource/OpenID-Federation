package com.sphereon.oid.fed.services.kms

import com.sphereon.oid.fed.kms.local.AmazonKms
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import kotlinx.serialization.json.JsonObject

class AmazonKmsClient : KmsClient {

    private val amazonKms = AmazonKms()

    override fun generateKeyPair(): JwkAdminDTO {
        return amazonKms.generateKey()
    }

    override fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        return amazonKms.sign(header, payload, keyId)
    }

    override fun verify(token: String, keyId: String?, jwk: Jwk?): Boolean {
        return amazonKms.verify(token, keyId!!)
    }
}