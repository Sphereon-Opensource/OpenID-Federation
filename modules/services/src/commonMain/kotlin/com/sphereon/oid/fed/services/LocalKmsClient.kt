package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.kms.local.database.LocalKmsDatabase
import com.sphereon.oid.fed.openapi.models.Jwk

class LocalKmsClient : KmsClient {

    private val database: LocalKmsDatabase = LocalKmsDatabase()

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