package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO
import kotlinx.serialization.json.Json

class KeyService {
    private val kmsClient = KmsService.getKmsClient()
    private val accountQueries = Persistence.accountQueries
    private val keyQueries = Persistence.keyQueries

    fun create(accountUsername: String): JwkAdminDTO {
        val account =
            accountQueries.findByUsername(accountUsername).executeAsOne()

        val jwk = kmsClient.generateKeyPair()

        keyQueries.create(
            account_id = account.id,
            kid = jwk.kid!!,
            key = Json.encodeToString(JwkAdminDTO.serializer(), jwk),
        ).executeAsOne()

        return jwk
    }

    fun getKeys(accountUsername: String): Array<JwkAdminDTO> {
        val account =
            accountQueries.findByUsername(accountUsername).executeAsOne()
        return keyQueries.findByAccountId(account.id).executeAsList().map { it.toJwkAdminDTO() }.toTypedArray()
    }

    fun revokeKey(accountUsername: String, keyId: Int, reason: String?): JwkAdminDTO {
        val account =
            accountQueries.findByUsername(accountUsername).executeAsOne()

        var key = keyQueries.findById(keyId).executeAsOne()

        if (key.account_id != account.id) {
            throw IllegalArgumentException(Constants.KEY_NOT_FOUND)
        }

        if (key.revoked_at != null) {
            throw IllegalArgumentException(Constants.KEY_ALREADY_REVOKED)
        }

        keyQueries.revoke(reason, keyId)

        key = keyQueries.findById(keyId).executeAsOne()

        return key.toJwkAdminDTO()
    }
}
