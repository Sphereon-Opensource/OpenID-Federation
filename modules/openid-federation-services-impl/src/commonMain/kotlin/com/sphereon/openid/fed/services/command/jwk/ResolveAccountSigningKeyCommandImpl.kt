package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.JwkQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveAccountSigningKeyCommand>())
class ResolveAccountSigningKeyCommandImpl internal constructor(
    execution: SessionExecution,
    private val findSelection: FindAccountSigningKeySelectionCommand,
    private val jwkQueries: JwkQueries,
) : TypedServiceCommandAdapter<ResolveAccountSigningKeyArgs, AccountSigningKey, FederationError>(
    commandId = ResolveAccountSigningKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ResolveAccountSigningKeyArgs>(),
    outputTypeToken = typeToken<AccountSigningKey>(),
), ResolveAccountSigningKeyCommand {
    @Inject
    constructor(execution: SessionExecution, findSelection: FindAccountSigningKeySelectionCommand) :
        this(execution, findSelection, Persistence.jwkQueries)

    override suspend fun doExecute(
        args: ResolveAccountSigningKeyArgs,
        applyDuring: (ResolveAccountSigningKeyArgs) -> ResolveAccountSigningKeyArgs,
    ): IdkResult<AccountSigningKey, FederationError> {
        val applied = applyDuring(args)
        val selection = findSelection.execute(FindAccountSigningKeySelectionArgs(applied.accountId, applied.expectedEntityIdentifier))
        if (selection.isErr) return selection.error.asErrorResult()
        val selected = selection.value
        val keyId = selected.selectedKeyId
            ?: return federationErr(InvalidRequestError("No signing key is selected for this account"))
        val key = jwkQueries.findById(keyId).executeAsOneOrNull()
            ?: return federationErr(InvalidRequestError("Selected signing key does not exist"))
        if (key.account_id != selected.accountId || key.revoked_at != null) {
            return federationErr(InvalidRequestError("Selected signing key is not active for this account"))
        }
        val kid = key.kid?.takeIf { it.isNotBlank() }
            ?: return federationErr(InvalidRequestError("Selected signing key has no kid"))
        val alg = key.alg?.takeIf { it.isNotBlank() }
            ?: return federationErr(InvalidRequestError("Selected signing key has no algorithm"))
        if (key.kms.isBlank() || key.kms_key_ref.isBlank()) {
            return federationErr(InvalidRequestError("Selected signing key has no explicit KMS route"))
        }
        return IdkResult.ok(AccountSigningKey(selected.accountId, selected.entityIdentifier, key.id, kid, alg,
            key.kms_key_ref, key.kms, selected.revision))
    }
}
