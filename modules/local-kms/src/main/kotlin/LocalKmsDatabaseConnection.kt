package com.sphereon.oid.fed.kms.local

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.stereotype.Component

@Component
class LocalKmsDatabaseConnection @Autowired constructor(private val keyRepository: KeyRepository) {

    fun insertKey(keyId: String, privateKey: ByteArray, publicKey: ByteArray, algorithm: String) {
        val key = Key(keyId, privateKey, publicKey, algorithm)
        keyRepository.save(key)
    }

    fun getKey(keyId: String): Key {
        return keyRepository.findByKeyId(keyId) ?: throw KeyNotFoundException("Key with ID $keyId not found")
    }

    fun updateKey(keyId: String, privateKey: ByteArray, publicKey: ByteArray, algorithm: String) {
        val existingKey = keyRepository.findByKeyId(keyId) ?: throw KeyNotFoundException("Key with ID $keyId not found")
        val updatedKey = existingKey.copy(privateKey = privateKey, publicKey = publicKey, algorithm = algorithm)
        keyRepository.save(updatedKey)
    }

    fun deleteKey(keyId: String) {
        try {
            keyRepository.deleteById(keyId)
        } catch (e: EmptyResultDataAccessException) {
            throw KeyNotFoundException("Key with ID $keyId not found")
        }
    }
}

class KeyNotFoundException(message: String) : Exception(message)