package com.sphereon.oid.fed.kms.local

import com.sphereon.oid.fed.kms.local.models.Keys

expect class LocalKmsDatabase {
    fun getKey(keyId: String): Keys
    fun insertKey(keyId: String, privateKey: ByteArray, publicKey: ByteArray, algorithm: String)
    fun deleteKey(keyId: String)
}

class KeyNotFoundException(message: String) : Exception(message)