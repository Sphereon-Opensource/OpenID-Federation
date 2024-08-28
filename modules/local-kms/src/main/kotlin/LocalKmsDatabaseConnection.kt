package com.sphereon.oid.fed.kms.local

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class LocalKmsDatabaseConnection @Autowired constructor(private val keyRepository: KeyRepository) {

    fun insertKey(keyId: String, privateKey: ByteArray, publicKey: ByteArray, algorithm: String) {
        val key = Key(keyId, privateKey, publicKey, algorithm)
        keyRepository.save(key)
    }

    fun getKey(keyId: String): Key {
        return keyRepository.findByKeyId(keyId) ?: throw Exception("Key not found")
    }

    // ... (Implement other methods like updateKey, deleteKey as needed)
}