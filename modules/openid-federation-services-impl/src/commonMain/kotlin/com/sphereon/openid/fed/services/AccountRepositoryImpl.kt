package com.sphereon.openid.fed.services

import com.sphereon.core.api.IdkResult
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.account.mappers.toDTO
import com.sphereon.openid.fed.core.error.AccountAlreadyExistsError
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AccountRepository>())
class AccountRepositoryImpl : AccountRepository {
    private val accountQueries = Persistence.accountQueries

    override suspend fun findById(id: String): FederationResult<Account?> =
        runCatching { accountQueries.findById(id).executeAsOneOrNull()?.toDTO() }
            .fold(
                onSuccess = { IdkResult.ok(it) },
                onFailure = { federationErr(ServerError("Failed to find account by id", it.message, it)) },
            )

    override suspend fun findByUsername(username: String): FederationResult<Account?> =
        runCatching { accountQueries.findByUsername(username).executeAsOneOrNull()?.toDTO() }
            .fold(
                onSuccess = { IdkResult.ok(it) },
                onFailure = { federationErr(ServerError("Failed to find account by username", it.message, it)) },
            )

    override suspend fun findByIdentifier(identifier: String): FederationResult<List<Account>> =
        runCatching { accountQueries.findByIdentifier(identifier).executeAsList().map { it.toDTO() } }
            .fold(
                onSuccess = { IdkResult.ok(it) },
                onFailure = { federationErr(ServerError("Failed to find accounts by identifier", it.message, it)) },
            )

    override suspend fun create(username: String, identifier: String?): FederationResult<Account> {
        val existing = accountQueries.findByUsername(username).executeAsOneOrNull()
        if (existing != null) {
            return federationErr(AccountAlreadyExistsError(username))
        }
        identifier?.let {
            if (!it.startsWith("https://")) {
                return federationErr(InvalidRequestError("Identifier must start with https://"))
            }
        }
        return try {
            val created = accountQueries.create(username = username, identifier = identifier).executeAsOne()
            IdkResult.ok(created.toDTO())
        } catch (e: Exception) {
            federationErr(ServerError("Failed to create account", e.message, e))
        }
    }
}
