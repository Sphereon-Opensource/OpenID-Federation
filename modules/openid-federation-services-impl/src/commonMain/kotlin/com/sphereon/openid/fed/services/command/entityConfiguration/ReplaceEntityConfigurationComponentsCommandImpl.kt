package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReplaceEntityConfigurationComponentsCommand>())
class ReplaceEntityConfigurationComponentsCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val metadataQueries: MetadataQueries,
    private val authorityHintQueries: AuthorityHintQueries,
) : TypedServiceCommandAdapter<
    ReplaceEntityConfigurationComponentsArgs,
    ReplacedEntityConfigurationComponents,
    FederationError,
>(
    commandId = ReplaceEntityConfigurationComponentsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ReplaceEntityConfigurationComponentsArgs>(),
    outputTypeToken = typeToken<ReplacedEntityConfigurationComponents>(),
), ReplaceEntityConfigurationComponentsCommand {
    @Inject
    constructor(execution: SessionExecution) : this(
        execution,
        Persistence.accountQueries,
        Persistence.metadataQueries,
        Persistence.authorityHintQueries,
    )

    override suspend fun doExecute(
        args: ReplaceEntityConfigurationComponentsArgs,
        applyDuring: (ReplaceEntityConfigurationComponentsArgs) -> ReplaceEntityConfigurationComponentsArgs,
    ): IdkResult<ReplacedEntityConfigurationComponents, FederationError> {
        val applied = applyDuring(args)
        val desiredMetadata = applied.metadata.toMap()
        val desiredHints = applied.authorityHints.toList()
        if (applied.accountId.isBlank() || applied.expectedEntityIdentifier.isBlank() ||
            desiredMetadata.keys.any { it.isBlank() } || desiredHints.any { it.isBlank() } ||
            desiredHints.size != desiredHints.toSet().size
        ) {
            return federationErr(InvalidRequestError("Invalid exact account component replacement inputs"))
        }

        return try {
            // All three generated query objects are constructed from one driver. Throwing out of
            // transactionWithResult rolls back soft deletes and inserts before any Err is returned.
            val persisted = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(applied.accountId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(applied.accountId))
                if (account.identifier != applied.expectedEntityIdentifier) {
                    throw FederationErrorException(
                        InvalidRequestError("Account identifier does not match expectedEntityIdentifier")
                    )
                }

                metadataQueries.findByAccountId(account.id).executeAsList().forEach { row ->
                    metadataQueries.delete(row.id).executeAsOne()
                }
                authorityHintQueries.findByAccountId(account.id).executeAsList().forEach { row ->
                    authorityHintQueries.delete(row.id).executeAsOne()
                }
                desiredMetadata.forEach { (role, body) ->
                    metadataQueries.create(account.id, role, body.toString()).executeAsOne()
                }
                desiredHints.forEach { hint ->
                    authorityHintQueries.create(account.id, hint).executeAsOne()
                }

                val metadataRows = metadataQueries.findByAccountId(account.id).executeAsList()
                val storedMetadata = metadataRows.associate { row ->
                    row.key to Json.parseToJsonElement(row.metadata).jsonObject
                }
                val hintRows = authorityHintQueries.findByAccountId(account.id).executeAsList()
                val storedHints = hintRows.map { it.identifier }
                check(metadataRows.size == desiredMetadata.size && storedMetadata == desiredMetadata) {
                    "Stored metadata differs from the complete replacement"
                }
                check(storedHints.size == desiredHints.size && storedHints.toSet() == desiredHints.toSet()) {
                    "Stored authority hints differ from the complete replacement"
                }
                ReplacedEntityConfigurationComponents(
                    accountId = account.id,
                    entityIdentifier = applied.expectedEntityIdentifier,
                    metadata = storedMetadata,
                    authorityHints = storedHints,
                )
            }
            IdkResult.ok(persisted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Atomic Entity Configuration component replacement failed", exception = failure))
        }
    }
}
