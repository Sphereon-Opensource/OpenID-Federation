package com.sphereon.openid.fed.services

import com.sphereon.crypto.core.generic.KeyOperations
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.JwaAlgorithm
import com.sphereon.crypto.core.jose.JwkUse
import com.sphereon.openid.fed.openapi.models.CreateKey

/**
 * Arguments for creating a new key.
 */
data class CreateKeyArgs(
    val providerId: String? = null,
    val alias: String? = null,
    val use: JwkUse = JwkUse.sig,
    val keyOperations: Array<out KeyOperations> = arrayOf(KeyOperations.SIGN, KeyOperations.VERIFY),
    val alg: SignatureAlgorithm = SignatureAlgorithm.ECDSA_SHA256
) {
    companion object {
        fun fromModel(model: CreateKey) = with(model) {
            CreateKeyArgs(
                providerId = kms,
                alias = kmsKeyRef,
                alg = SignatureAlgorithm.fromJose(
                    JwaAlgorithm.fromValue(signatureAlgorithm?.value) ?: JwaAlgorithm.ES256
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CreateKeyArgs

        if (providerId != other.providerId) return false
        if (alias != other.alias) return false
        if (use != other.use) return false
        if (!keyOperations.contentEquals(other.keyOperations)) return false
        if (alg != other.alg) return false

        return true
    }

    override fun hashCode(): Int {
        var result = providerId?.hashCode() ?: 0
        result = 31 * result + (alias?.hashCode() ?: 0)
        result = 31 * result + use.hashCode()
        result = 31 * result + keyOperations.contentHashCode()
        result = 31 * result + alg.hashCode()
        return result
    }
}
