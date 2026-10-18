package com.sphereon.openid.fed.services.command.renewal

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationArgs
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationCommand
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementArgs
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementCommand
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * Renews due statements with the regular publish commands in dry-run mode (current state, selected key), then stores
 * each candidate with a compare-and-set under the issuing account's row lock. See [RenewDueStatementsCommand].
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RenewDueStatementsCommand>())
@ExposeImplBinding
class RenewDueStatementsCommandImpl internal constructor(
    execution: SessionExecution,
    private val publishEntityConfiguration: PublishEntityConfigurationCommand,
    private val publishSubordinateStatement: PublishSubordinateStatementCommand,
    private val cacheInvalidator: PublishedStatementCacheInvalidator,
    private val accountQueries: AccountQueries,
    private val entityConfigurationQueries: EntityConfigurationStatementQueries,
    private val subordinateQueries: SubordinateQueries,
    private val subordinateStatementQueries: SubordinateStatementQueries,
    private val selectionQueries: AccountSigningKeyQueries,
    private val jwkQueries: JwkQueries,
    private val clock: () -> Long,
) : TypedServiceCommandAdapter<RenewDueStatementsArgs, StatementRenewalReport, FederationError>(
    commandId = RenewDueStatementsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<RenewDueStatementsArgs>(),
    outputTypeToken = typeToken<StatementRenewalReport>(),
), RenewDueStatementsCommand {
    private val logger = execution.federationLogger("RenewDueStatementsCommand")

    @Inject
    constructor(
        execution: SessionExecution,
        publishEntityConfiguration: PublishEntityConfigurationCommand,
        publishSubordinateStatement: PublishSubordinateStatementCommand,
        cacheInvalidator: PublishedStatementCacheInvalidator,
    ) : this(
        execution, publishEntityConfiguration, publishSubordinateStatement, cacheInvalidator,
        Persistence.accountQueries, Persistence.entityConfigurationStatementQueries, Persistence.subordinateQueries,
        Persistence.subordinateStatementQueries, Persistence.accountSigningKeyQueries, Persistence.jwkQueries,
        { System.currentTimeMillis() / 1000L },
    )

    private sealed interface Store {
        data class Stored(val expiresAt: Long) : Store
        data class Discarded(val status: StatementRenewalStatus, val detail: String) : Store
    }

    override suspend fun doExecute(
        args: RenewDueStatementsArgs,
        applyDuring: (RenewDueStatementsArgs) -> RenewDueStatementsArgs,
    ): IdkResult<StatementRenewalReport, FederationError> {
        val applied = applyDuring(args)
        if (applied.renewBeforeSeconds <= 0L) {
            return federationErr(InvalidRequestError("renewBeforeSeconds must be positive"))
        }
        val now = applied.nowEpochSeconds ?: clock()
        if (applied.keysOnly) return IdkResult.ok(StatementRenewalReport(now, emptyList()))
        val threshold = now + applied.renewBeforeSeconds
        val outcomes = mutableListOf<StatementRenewalOutcome>()

        val dueConfigurations = entityConfigurationQueries.findRenewalDue(threshold).executeAsList()
            .filter { applied.accountId == null || it.account_id == applied.accountId }
        val dueSubordinateStatements = subordinateStatementQueries.findRenewalDue(threshold).executeAsList()
            .filter { applied.accountId == null || it.account_id == applied.accountId }
        if (applied.listOnly) {
            return IdkResult.ok(StatementRenewalReport(now,
                dueConfigurations.map {
                    StatementRenewalOutcome(RenewedStatementKind.ENTITY_CONFIGURATION, it.account_id, null,
                        StatementRenewalStatus.DUE, it.expires_at)
                } + dueSubordinateStatements.map {
                    StatementRenewalOutcome(RenewedStatementKind.SUBORDINATE_STATEMENT, it.account_id, it.subordinate_id,
                        StatementRenewalStatus.DUE, it.expires_at)
                }))
        }
        for (due in dueConfigurations) {
            outcomes += renew(RenewedStatementKind.ENTITY_CONFIGURATION, due.account_id, null, due.expires_at, threshold)
        }
        for (due in dueSubordinateStatements) {
            outcomes += renew(RenewedStatementKind.SUBORDINATE_STATEMENT, due.account_id, due.subordinate_id, due.expires_at, threshold)
        }
        val report = StatementRenewalReport(now, outcomes)
        if (outcomes.isNotEmpty()) {
            logger.info(
                "Statement renewal at $now (renew before ${applied.renewBeforeSeconds}s): due=${outcomes.size} " +
                    "renewed=${report.count(StatementRenewalStatus.RENEWED)} " +
                    "alreadyRenewed=${report.count(StatementRenewalStatus.ALREADY_RENEWED)} " +
                    "keyChanged=${report.count(StatementRenewalStatus.SIGNING_KEY_CHANGED)} " +
                    "unpublished=${report.count(StatementRenewalStatus.NO_LONGER_PUBLISHED)} " +
                    "failed=${report.count(StatementRenewalStatus.FAILED)}"
            )
        }
        return IdkResult.ok(report)
    }

    private suspend fun renew(
        kind: RenewedStatementKind,
        accountId: String,
        subordinateId: String?,
        previousExpiresAt: Long,
        threshold: Long,
    ): StatementRenewalOutcome {
        fun outcome(status: StatementRenewalStatus, expiresAt: Long? = null, detail: String? = null) =
            StatementRenewalOutcome(kind, accountId, subordinateId, status, previousExpiresAt, expiresAt, detail)
        val subject = subordinateId?.let { "subordinate $it of account $accountId" } ?: "account $accountId"
        return try {
            val signed = if (subordinateId == null) {
                publishEntityConfiguration.execute(PublishEntityConfigurationArgs(tenantId = accountId, dryRun = true))
            } else {
                publishSubordinateStatement.execute(
                    PublishSubordinateStatementArgs(tenantId = accountId, id = subordinateId, dryRun = true)
                )
            }
            if (signed.isErr) {
                val detail = signed.error.message.defaultMessage
                logger.warn("Could not renew the $kind of $subject: $detail")
                return outcome(StatementRenewalStatus.FAILED, detail = detail)
            }
            val jwt = signed.value
            val decoded = decodeJWTComponents(jwt)
            val expiresAt = (decoded.payload["exp"] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.let { it.content.toLongOrNull() ?: it.doubleOrNull?.let { value -> floor(value).toLong() } }
                ?: return outcome(StatementRenewalStatus.FAILED, detail = "The renewed statement has no numeric exp")
                    .also { logger.warn("Could not renew the $kind of $subject: no numeric exp") }
            if (expiresAt <= threshold) {
                val detail = "The statement lifetime does not exceed the renewal margin"
                logger.warn("Could not renew the $kind of $subject: $detail (exp=$expiresAt, threshold=$threshold)")
                return outcome(StatementRenewalStatus.FAILED, detail = detail)
            }
            when (val stored = store(accountId, subordinateId, decoded.header.kid, jwt, expiresAt, threshold)) {
                is Store.Stored -> {
                    cacheInvalidator.published(jwt)
                    logger.info("Renewed the $kind of $subject: exp $previousExpiresAt -> ${stored.expiresAt} (kid ${decoded.header.kid})")
                    outcome(StatementRenewalStatus.RENEWED, expiresAt = stored.expiresAt)
                }
                is Store.Discarded -> {
                    logger.info("Discarded a renewed ${kind} candidate for $subject: ${stored.detail}")
                    outcome(stored.status, detail = stored.detail)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.error("Could not renew the $kind of $subject", e)
            outcome(StatementRenewalStatus.FAILED, detail = e.message ?: e::class.simpleName)
        }
    }

    /**
     * Compare-and-set: under the issuing account's row lock, store only if the latest statement is still due and the
     * candidate's signing key is still the account's selected active key. The lock serializes renewers on every
     * instance, so exactly one of several concurrent candidates is stored.
     */
    private fun store(
        accountId: String,
        subordinateId: String?,
        kid: String,
        jwt: String,
        expiresAt: Long,
        threshold: Long,
    ): Store = accountQueries.transactionWithResult {
        accountQueries.findActiveForUpdate(accountId).executeAsOneOrNull()
            ?: return@transactionWithResult Store.Discarded(StatementRenewalStatus.NO_LONGER_PUBLISHED, "The account was deleted")
        val selectedKid = selectionQueries.findByAccountId(accountId).executeAsOneOrNull()
            ?.jwk_id
            ?.let { jwkQueries.findActiveById(it).executeAsOneOrNull() }
            ?.takeIf { it.account_id == accountId && it.revoked_at == null }
            ?.kid
        if (selectedKid != kid) {
            return@transactionWithResult Store.Discarded(
                StatementRenewalStatus.SIGNING_KEY_CHANGED,
                "The selected signing key changed while renewing",
            )
        }
        if (subordinateId == null) {
            val latest = entityConfigurationQueries.findLatestByAccountId(accountId).executeAsOneOrNull()
            if (latest != null && latest.expires_at > threshold) {
                return@transactionWithResult Store.Discarded(StatementRenewalStatus.ALREADY_RENEWED,
                    "A statement expiring at ${latest.expires_at} was stored meanwhile")
            }
            entityConfigurationQueries.create(account_id = accountId, statement = jwt, expires_at = expiresAt).executeAsOne()
        } else {
            val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
                ?.takeIf { it.account_id == accountId && it.deleted_at == null }
                ?: return@transactionWithResult Store.Discarded(StatementRenewalStatus.NO_LONGER_PUBLISHED,
                    "The subordinate was deleted")
            val latest = subordinateStatementQueries.findLatestBySubordinateId(subordinateId).executeAsOneOrNull()
            if (latest != null && latest.expires_at > threshold) {
                return@transactionWithResult Store.Discarded(StatementRenewalStatus.ALREADY_RENEWED,
                    "A statement expiring at ${latest.expires_at} was stored meanwhile")
            }
            val claims = decodeJWTComponents(jwt).payload
            subordinateStatementQueries.create(
                subordinate_id = subordinate.id,
                iss = (claims["iss"] as JsonPrimitive).content,
                sub = (claims["sub"] as JsonPrimitive).content,
                statement = jwt,
                expires_at = expiresAt,
            ).executeAsOne()
        }
        Store.Stored(expiresAt)
    }
}
