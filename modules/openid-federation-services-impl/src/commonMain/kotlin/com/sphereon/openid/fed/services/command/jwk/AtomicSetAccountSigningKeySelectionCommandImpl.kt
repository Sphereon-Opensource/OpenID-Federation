package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.core.api.http.query.QueryParamUtils
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.SelectedSigningKeyConflictError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AtomicSetAccountSigningKeySelectionCommand>())
class AtomicSetAccountSigningKeySelectionCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
    private val accountSigningKeyQueries: AccountSigningKeyQueries,
    private val tenantServiceConfig: TenantServiceConfig = TenantServiceConfig(""),
) : TypedServiceCommandAdapter<
    AtomicSetAccountSigningKeySelectionArgs,
    AccountSigningKeySelection,
    FederationError,
>(
    commandId = AtomicSetAccountSigningKeySelectionCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<AtomicSetAccountSigningKeySelectionArgs>(),
    outputTypeToken = typeToken<AccountSigningKeySelection>(),
), AtomicSetAccountSigningKeySelectionCommand {
    @Inject
    constructor(execution: SessionExecution, tenantServiceConfig: TenantServiceConfig) : this(
        execution,
        Persistence.accountQueries,
        Persistence.jwkQueries,
        Persistence.accountSigningKeyQueries,
        tenantServiceConfig,
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun doExecute(
        args: AtomicSetAccountSigningKeySelectionArgs,
        applyDuring: (AtomicSetAccountSigningKeySelectionArgs) -> AtomicSetAccountSigningKeySelectionArgs,
    ): IdkResult<AccountSigningKeySelection, FederationError> {
        // Capture the full request on the canonical server; callers never supply a digest.
        val request = Json.encodeToString(AtomicSetAccountSigningKeySelectionArgs.serializer(), applyDuring(args))
        val applied = Json.decodeFromString(AtomicSetAccountSigningKeySelectionArgs.serializer(), request)
        val accountId = QueryParamUtils.parseUuid(applied.accountId)?.toString()
            ?: return invalid("Account ID must be a UUID")
        if (applied.expectedEntityIdentifier.isBlank()) return invalid("Expected Entity Identifier must not be blank")
        if (applied.expectedRevision < 0L) return invalid("Expected selection revision must not be negative")
        if (applied.operationGeneration <= 0L || applied.operationToken.isBlank() || applied.operationToken.length > 256) {
            return invalid("Invalid signing-key operation coordinates")
        }
        val selectedKeyId = applied.selectedKeyId?.let { QueryParamUtils.parseUuid(it)?.toString() }
        if (applied.selectedKeyId != null && selectedKeyId == null) {
            return invalid("Selected key ID must be a UUID")
        }

        return try {
            val selection = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(accountId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(accountId))
                val accountIdentifier = effectiveSigningSelectionIdentifier(
                    account.username, account.identifier, tenantServiceConfig.rootIdentifier,
                )
                if (accountIdentifier == null || accountIdentifier != applied.expectedEntityIdentifier) {
                    throw FederationErrorException(
                        InvalidRequestError("Account identifier does not match expectedEntityIdentifier")
                    )
                }

                val existing = accountSigningKeyQueries.findByAccountId(account.id).executeAsOneOrNull()
                val currentRevision = existing?.revision ?: 0L
                val selectedKey = selectedKeyId?.let { id ->
                    val key = jwkQueries.findActiveById(id).executeAsOneOrNull()
                    if (key == null || key.account_id != account.id || key.revoked_at != null) {
                        throw FederationErrorException(KeyNotFoundError(applied.selectedKeyId!!))
                    }
                    key
                }
                val generation = existing?.last_operation_generation
                if (existing != null && generation != null && applied.operationGeneration <= generation) {
                    val retained = existing.last_request_snapshot
                    val latestRetry = applied.expectedRevision < Long.MAX_VALUE &&
                        applied.operationGeneration == generation && existing.last_operation_token == applied.operationToken &&
                        retained != null && Json.parseToJsonElement(retained) == Json.parseToJsonElement(request) &&
                        existing.revision == applied.expectedRevision + 1L && existing.jwk_id == selectedKey?.id
                    if (!latestRetry) throw FederationErrorException(SelectedSigningKeyConflictError(account.id))
                    return@transactionWithResult AccountSigningKeySelection(account.id, accountIdentifier, existing.jwk_id, existing.revision)
                }
                if (currentRevision != applied.expectedRevision || currentRevision == Long.MAX_VALUE) {
                    throw FederationErrorException(SelectedSigningKeyConflictError(account.id))
                }
                val stored = accountSigningKeyQueries.upsertAtomic(
                    account.id, selectedKey?.id, currentRevision + 1L,
                    applied.operationGeneration, applied.operationToken, request,
                ).executeAsOne()
                check(stored.last_operation_generation == applied.operationGeneration &&
                    stored.last_operation_token == applied.operationToken && stored.last_request_snapshot == request
                ) { "Persisted selected signing-key operation ownership differs from the request" }
                check(stored.account_id == account.id && stored.jwk_id == selectedKey?.id &&
                    stored.revision == currentRevision + 1L
                ) { "Persisted selected signing-key binding differs from the requested transition" }
                AccountSigningKeySelection(
                    accountId = stored.account_id,
                    entityIdentifier = accountIdentifier,
                    selectedKeyId = stored.jwk_id,
                    revision = stored.revision,
                )
            }
            IdkResult.ok(selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Selected signing-key transition failed", failure.message, failure))
        }
    }

    private fun invalid(reason: String): IdkResult<AccountSigningKeySelection, FederationError> =
        federationErr(InvalidRequestError(reason))
}
