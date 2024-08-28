package com.sphereon.oid.fed.kms.local

import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.KmsClient

class LocalKmsClient(private val database: LocalKmsDatabaseConnection) : KmsClient {

    override fun generateKeyPair(keyId: String): Jwk {
        TODO("Not yet implemented")
    }

    override fun sign(data: String, keyId: String): String {
        TODO("Not yet implemented")
    }

    override fun verify(token: String, keyId: String): Boolean {
        TODO("Not yet implemented")
    }
}