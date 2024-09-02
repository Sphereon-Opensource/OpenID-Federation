package com.sphereon.oid.fed.kms.local.encryption

import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val KEY_SIZE = 32
private const val ALGORITHM = "AES"

class AesEncryption {

    private val secretKey: SecretKeySpec =
        SecretKeySpec(System.getenv("APP_KEY").padEnd(KEY_SIZE, '0').toByteArray(Charsets.UTF_8), ALGORITHM)

    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val encryptedValue = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encryptedValue)
    }

    fun decrypt(data: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)

        val decodedValue = Base64.getDecoder().decode(data)
        val decryptedValue = cipher.doFinal(decodedValue)
        return String(decryptedValue, Charsets.UTF_8)
    }
}
