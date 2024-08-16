package com.sphereon.oid.fed.services.extensions

import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM = "AES"
private const val KEY_SIZE = 32

actual fun aesEncrypt(data: String, key: String): String {
    val secretKey = SecretKeySpec(key.padEnd(KEY_SIZE, '0').toByteArray(Charsets.UTF_8), ALGORITHM)

    val cipher = Cipher.getInstance(ALGORITHM)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)

    val encryptedValue = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(encryptedValue)
}

actual fun aesDecrypt(data: String, key: String): String {
    val secretKey = SecretKeySpec(key.padEnd(KEY_SIZE, '0').toByteArray(Charsets.UTF_8), ALGORITHM)

    val cipher = Cipher.getInstance(ALGORITHM)
    cipher.init(Cipher.DECRYPT_MODE, secretKey)

    val decodedValue = Base64.getDecoder().decode(data)
    val decryptedValue = cipher.doFinal(decodedValue)
    return String(decryptedValue, Charsets.UTF_8)
}
