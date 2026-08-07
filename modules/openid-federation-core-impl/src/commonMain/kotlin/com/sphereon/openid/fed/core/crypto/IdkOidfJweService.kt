package com.sphereon.openid.fed.core.crypto

import com.sphereon.crypto.jose.jwe.CreateJweCompactArgs
import com.sphereon.crypto.jose.jwe.DecryptJweArgs
import com.sphereon.crypto.jose.jwe.JweCompact
import com.sphereon.crypto.jose.jwe.JweService
import com.sphereon.crypto.jose.jwe.PrepareJweArgs
import com.sphereon.crypto.resolution.managed.ManagedIdentifierOptsOrResult

/**
 * Thin adapter from IDK [JweService] to [OidfJweService].
 *
 * ## Boundary
 * OIDF does not reimplement JOSE crypto. Hosts that need encrypted payloads
 * (optional nested JWT / private metadata) inject IDK [JweService] + recipient/decryptor
 * identifiers and wrap with this class.
 *
 * Not auto-bound in DI: construct when `oidf.jwe.enabled=true` and keys are available.
 */
class IdkOidfJweService(
    private val jweService: JweService,
    private val recipient: ManagedIdentifierOptsOrResult,
    private val decryptor: ManagedIdentifierOptsOrResult,
    private val keyEncryptionAlg: String = "RSA-OAEP",
    private val contentEncryptionAlg: String = "A256GCM",
) : OidfJweService {

    override suspend fun encryptCompact(plaintext: String): Result<String> {
        val prepared = jweService.prepareJwe(
            PrepareJweArgs(
                plaintext = plaintext.encodeToByteArray(),
                recipient = recipient,
                keyEncryptionAlg = keyEncryptionAlg,
                contentEncryptionAlg = contentEncryptionAlg,
            )
        )
        if (prepared.isErr) {
            return Result.failure(IllegalStateException(prepared.error.toString()))
        }
        val created = jweService.createJweCompact(
            CreateJweCompactArgs(preparedJwe = prepared.value)
        )
        if (created.isErr) {
            return Result.failure(IllegalStateException(created.error.toString()))
        }
        return Result.success(created.value.serialize())
    }

    override suspend fun decryptCompact(jweCompact: String): Result<String> {
        val parsed = runCatching { JweCompact.parse(jweCompact) }.getOrElse {
            return Result.failure(it)
        }
        val decrypted = jweService.decryptJwe(
            DecryptJweArgs(jwe = parsed, decryptor = decryptor)
        )
        if (decrypted.isErr) {
            return Result.failure(IllegalStateException(decrypted.error.toString()))
        }
        val bytes = decrypted.value.plaintext
            ?: return Result.failure(IllegalStateException("JWE decrypt produced empty plaintext"))
        return Result.success(bytes.decodeToString())
    }
}
