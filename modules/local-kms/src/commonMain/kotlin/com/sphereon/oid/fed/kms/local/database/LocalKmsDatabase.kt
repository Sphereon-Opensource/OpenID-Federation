package com.sphereon.oid.fed.kms.local.database

import com.sphereon.oid.fed.kms.local.models.Keys

expect class LocalKmsDatabase() {
    fun getKey(keyId: String): Keys
    fun insertKey(keyId: String, privateKey: String)
    fun deleteKey(keyId: String)
}

class KeyNotFoundException(message: String) : Exception(message)