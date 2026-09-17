package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account

/**
 * Persistence port for federation Account rows. EDK must not call `Persistence.*`.
 */
interface AccountRepository {
    suspend fun findById(id: String): FederationResult<Account?>
    suspend fun findByUsername(username: String): FederationResult<Account?>
    suspend fun findByIdentifier(identifier: String): FederationResult<List<Account>>
    suspend fun create(username: String, identifier: String?): FederationResult<Account>
}
