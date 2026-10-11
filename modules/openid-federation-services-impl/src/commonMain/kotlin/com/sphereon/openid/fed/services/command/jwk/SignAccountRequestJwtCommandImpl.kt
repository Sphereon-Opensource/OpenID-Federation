package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SignAccountRequestJwtCommand>())
class SignAccountRequestJwtCommandImpl internal constructor(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwtService: JwtService,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
) : TypedServiceCommandAdapter<SignAccountRequestJwtArgs, String, FederationError>(
    commandId = SignAccountRequestJwtCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<SignAccountRequestJwtArgs>(),
    outputTypeToken = typeToken<String>(),
), SignAccountRequestJwtCommand {
    private val logger = execution.federationLogger("SignAccountRequestJwtCommand")

    @Inject
    constructor(
        execution: SessionExecution,
        tenantContextResolver: TenantContextResolver,
        jwtService: JwtService,
        resolveSigningKey: ResolveAccountSigningKeyCommand,
    ) : this(execution, tenantContextResolver, jwtService, resolveSigningKey,
        Persistence.accountQueries, Persistence.jwkQueries)

    override suspend fun doExecute(
        args: SignAccountRequestJwtArgs,
        applyDuring: (SignAccountRequestJwtArgs) -> SignAccountRequestJwtArgs,
    ): IdkResult<String, FederationError> {
        val applied = applyDuring(args)
        if (!REQUEST_TYPE.matches(applied.typ) || applied.typ in RESERVED_TYPES) {
            return federationErr(InvalidRequestError("Invalid or reserved request JWT type"))
        }
        return try {
            val identifier = tenantContextResolver.resolveIdentifier(applied.tenantId)
                ?: return federationErr(TenantNotFoundError(applied.tenantId))
            if (applied.kid == null) {
                val resolved = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(applied.tenantId, identifier))
                if (resolved.isErr) return resolved.error.asErrorResult()
                val key = resolved.value
                return jwtService.signPayload(applied.claims,
                    JwtHeader(typ = applied.typ, kid = key.kid, alg = key.alg),
                    key.kid, key.kmsKeyRef, key.kms)
            }

            val key = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(applied.tenantId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(applied.tenantId))
                jwkQueries.findByAccountId(account.id).executeAsList().singleOrNull { it.kid == applied.kid }
                    ?: throw FederationErrorException(InvalidRequestError("kid must name an active key of this account"))
            }
            val kid = key.kid?.takeIf { it.isNotBlank() }
                ?: return federationErr(InvalidRequestError("Signing key has no kid"))
            val alg = key.alg?.takeIf { it.isNotBlank() }
                ?: return federationErr(InvalidRequestError("Signing key has no algorithm"))
            if (key.kms.isBlank() || key.kms_key_ref.isBlank()) {
                return federationErr(InvalidRequestError("Signing key has no explicit KMS route"))
            }
            jwtService.signPayload(applied.claims, JwtHeader(typ = applied.typ, kid = kid, alg = alg),
                kid, key.kms_key_ref, key.kms)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            logger.error("Failed to sign account request JWT", failure)
            federationErr(ServerError("Failed to sign account request JWT", failure.message, failure))
        }
    }

    companion object {
        private val REQUEST_TYPE = Regex("^[a-z0-9][a-z0-9.-]*\\+jwt$")
        private val RESERVED_TYPES = setOf(
            "entity-statement+jwt",
            "trust-mark+jwt",
            "trust-mark-delegation+jwt",
            "resolve-response+jwt",
            "jwk-set+jwt",
            "trust-mark-status-response+jwt",
            "explicit-registration-response+jwt",
            "entity-events-statement+jwt",
        )
    }
}
