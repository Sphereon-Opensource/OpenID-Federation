package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO
import kotlinx.serialization.json.Json

class KeyService {
    private val kmsClient = KmsService.getKmsClient()
    private val accountQueries = Persistence.accountQueries
    private val keyQueries = Persistence.keyQueries

    fun create(accountId: Int): JwkAdminDTO {
        val account =
            accountQueries.findById(accountId).executeAsOne()

        val jwk = kmsClient.generateKeyPair()

        keyQueries.create(
            account_id = account.id,
            kid = jwk.kid,
            key = Json.encodeToString(JwkAdminDTO.serializer(), jwk),
        ).executeAsOne()

        return jwk
    }

    fun getKeys(accountId: Int): Array<JwkAdminDTO> {
        val account =
            accountQueries.findById(accountId).executeAsOneOrNull() ?: throw NotFoundException(
                Constants.ACCOUNT_NOT_FOUND
            )
        return keyQueries.findByAccountId(account.id).executeAsList().map { it.toJwkAdminDTO() }.toTypedArray()
    }

    fun revokeKey(accountId: Int, keyId: Int, reason: String?): JwkAdminDTO {
        val account = accountQueries.findById(accountId).executeAsOne()

        var key = keyQueries.findById(keyId).executeAsOne()

        if (key.account_id != account.id) {
            throw NotFoundException(Constants.KEY_NOT_FOUND)
        }

        if (key.revoked_at != null) {
            throw IllegalStateException(Constants.KEY_ALREADY_REVOKED)
        }

        keyQueries.revoke(reason, keyId)

        key = keyQueries.findById(keyId).executeAsOne()

        return key.toJwkAdminDTO()
    }
}
