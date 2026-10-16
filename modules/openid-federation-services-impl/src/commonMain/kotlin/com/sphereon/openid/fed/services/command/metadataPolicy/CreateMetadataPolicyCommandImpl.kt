package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataPolicyAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.JsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the CreateMetadataPolicyCommand.
 * Creates a new metadata policy entry for an account.
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateMetadataPolicyCommand>())
class CreateMetadataPolicyCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: com.sphereon.openid.fed.persistence.models.AccountQueries,
    private val metadataPolicyQueries: com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries,
    private val revisionQueries: com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries,
) : TypedServiceCommandAdapter<CreateMetadataPolicyArgs, MetadataPolicy, FederationError>(
    commandId = CreateMetadataPolicyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateMetadataPolicyArgs>(),
    outputTypeToken = typeToken<MetadataPolicy>()
), CreateMetadataPolicyCommand {

    private val logger = execution.federationLogger("CreateMetadataPolicyCommand")
    @Inject
    constructor(execution: SessionExecution) : this(execution, Persistence.accountQueries, Persistence.metadataPolicyQueries, Persistence.accountMetadataPolicyRevisionQueries)

    override suspend fun doExecute(
        args: CreateMetadataPolicyArgs,
        applyDuring: (CreateMetadataPolicyArgs) -> CreateMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, FederationError> {
        return try {
            val applied = applyDuring(args)
            val tenantId = policyAccountId(applied.tenantId)
            val key = applied.key
            val policy = applied.policy as? JsonObject
                ?: return federationErr(InvalidRequestError("A metadata policy must be an object of claim operators"))
            val frozen = kotlinx.serialization.json.Json.parseToJsonElement(policy.toString()) as JsonObject
            validateRolePolicy(key, frozen)
            val created = accountQueries.transactionWithResult {
                lockPolicyAccount(accountQueries, tenantId)
                if (metadataPolicyQueries.findByAccountIdAndKey(tenantId, key).executeAsList().isNotEmpty()) {
                    throw com.sphereon.openid.fed.core.error.FederationErrorException(MetadataPolicyAlreadyExistsError(tenantId, key))
                }
                val row = metadataPolicyQueries.create(tenantId, key, frozen.toString()).executeAsOne()
                advanceLegacyPolicyRevision(revisionQueries, tenantId, key)
                row.toDTO()
            }
            IdkResult.ok(created)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (refused: com.sphereon.openid.fed.core.error.FederationErrorException) {
            federationErr(refused.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Failed to create metadata policy", failure.message, failure))
        }
    }
}
