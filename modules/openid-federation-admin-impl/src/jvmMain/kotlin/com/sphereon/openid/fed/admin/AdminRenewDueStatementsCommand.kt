package com.sphereon.openid.fed.admin

import com.sphereon.openid.fed.client.command.trustMark.TrustMarkValidation
import kotlin.math.floor

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.command.renewal.*
import dev.zacsweers.metro.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Managed documents renew through their owning CAS store; legacy accounts retain the existing command. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RenewDueStatementsCommand>(), replaces = [RenewDueStatementsCommandImpl::class])
class AdminRenewDueStatementsCommand internal constructor(
    execution: SessionExecution,
    private val legacy: RenewDueStatementsCommandImpl,
    private val store: AdminDocumentStore,
    private val protocol: AdminProtocolPort,
    private val database: com.sphereon.openid.fed.persistence.Database?,
    private val batchSize: Long = 128,
) : TypedServiceCommandAdapter<RenewDueStatementsArgs, StatementRenewalReport, FederationError>(
    commandId = RenewDueStatementsCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<RenewDueStatementsArgs>(), outputTypeToken = typeToken<StatementRenewalReport>(),
), RenewDueStatementsCommand {
    @Inject constructor(execution: SessionExecution, legacy: RenewDueStatementsCommandImpl, store: AdminDocumentStore, protocol: AdminProtocolPort) :
        this(execution, legacy, store, protocol, null)
    private val queries get() = database?.adminDocumentQueries ?: Persistence.adminDocumentQueries
    override suspend fun doExecute(args: RenewDueStatementsArgs, applyDuring: (RenewDueStatementsArgs) -> RenewDueStatementsArgs): IdkResult<StatementRenewalReport, FederationError> {
        val applied = applyDuring(args)
        if (applied.renewBeforeSeconds <= 0) return federationErr(InvalidRequestError("renewBeforeSeconds must be positive"))
        val now = applied.nowEpochSeconds ?: System.currentTimeMillis() / 1000
        val threshold = now + applied.renewBeforeSeconds
        val requestedAccount = applied.accountId
        val managed = (if (applied.keysOnly && requestedAccount != null) listOfNotNull(queries.find(requestedAccount, "configuration", "", "").executeAsOneOrNull())
            else if (applied.keysOnly) queries.keyMaintenanceNodes(batchSize.coerceIn(1, 128)).executeAsList() else queries.listManaged().executeAsList())
            .filter { applied.accountId == null || applied.accountId == it.account_id }
        if (applied.keysOnly) {
            val work = mutableListOf<StatementRenewalOutcome>()
            for (node in managed) {
                if (applied.listOnly) work += StatementRenewalOutcome(RenewedStatementKind.ENTITY_CONFIGURATION, node.account_id, null, StatementRenewalStatus.DUE, now)
                else {
                    val document = Json.parseToJsonElement(node.document).jsonObject
                    // Claim before potentially failing signing/CAS work so failing accounts cannot starve others.
                    // This changes queue order only; document, revision and committed artifact remain untouched.
                    queries.touchPendingKeySchedule(System.currentTimeMillis(), node.account_id)
                    protocol.node(AdminNodeScope(node.account_id, (document["entity_id"] as? JsonPrimitive)?.content))
                }
            }
            return IdkResult.ok(StatementRenewalReport(now, work))
        }
        val managedIds = managed.map { it.account_id }.toSet()
        val outcomes = mutableListOf<StatementRenewalOutcome>()
        if (applied.listOnly) {
            val legacyReport = legacy.execute(applied)
            if (legacyReport.isErr) return legacyReport
            outcomes += legacyReport.value.outcomes.filter { it.accountId !in managedIds }
        } else if (applied.accountId != null && applied.accountId !in managedIds) return legacy.execute(applied)
        else if (applied.accountId == null) {
            val due = legacy.execute(applied.copy(listOnly = true))
            if (due.isErr) return due
            for (accountId in due.value.outcomes.map { it.accountId }.distinct().filter { it !in managedIds }) {
                val result = legacy.execute(applied.copy(accountId = accountId))
                if (result.isErr) return result
                outcomes += result.value.outcomes
            }
        }
        for (node in managed) {
            val nodeDocument = Json.parseToJsonElement(node.document).jsonObject
            val scope = AdminNodeScope(node.account_id, (nodeDocument["entity_id"] as? JsonPrimitive)?.content)
            if (!applied.listOnly) protocol.node(scope) // Persistent rollover switches are also resumed after restart.
            val rows = listOfNotNull(store.readCommitted(scope, AdminDocumentKey("configuration"))) + store.listCommitted(scope, "subordinate")
            var dueCount = 0
            for (row in rows) {
                val oldJwt = row.artifact ?: continue
                val signedExpiry = TrustMarkValidation.numericDate(payload(oldJwt)["exp"]) ?: continue
                val expires = floor(signedExpiry).toLong() // Whole-second report field, not the scheduling comparison.
                if (signedExpiry > threshold.toDouble()) continue
                val kind = if (row.key.collection == "configuration") RenewedStatementKind.ENTITY_CONFIGURATION else RenewedStatementKind.SUBORDINATE_STATEMENT
                val subordinate = row.key.resourceId.takeIf { kind == RenewedStatementKind.SUBORDINATE_STATEMENT }
                dueCount++
                if (applied.listOnly) {
                    outcomes += StatementRenewalOutcome(kind, node.account_id, subordinate, StatementRenewalStatus.DUE, expires)
                    continue
                }
                try {
                    val lifetimePolicy = protocol.lifetimePolicy(scope, row.key)?.copy(explicitLifetimeWrite = false)
                    val lifetime = applyAdminLifetimePolicy(row.key, row, row.document, lifetimePolicy,
                        store.readLifetimeAcknowledgement(scope, row.key), publishing = true)
                    val prepared = protocol.prepare(scope, row.key, lifetime.document)
                    val jwt = prepared.artifact
                    val nextExpiry = TrustMarkValidation.numericDate(payload(jwt)["exp"])?.let { floor(it).toLong() }
                        ?: error("Prepared artifact has no exp")
                    val committed = store.commit(scope, listOf(AdminMutation(row.key, row.etag, lifetime.document, jwt, expectedKeyState = prepared.keyState,
                        lifetimePolicy = lifetimePolicy, acknowledgeLifetimePolicy = lifetime.acknowledge)))
                    outcomes += StatementRenewalOutcome(kind, node.account_id, subordinate,
                        if (committed == null) StatementRenewalStatus.ALREADY_RENEWED else StatementRenewalStatus.RENEWED, expires, nextExpiry)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    outcomes += StatementRenewalOutcome(kind, node.account_id, subordinate, StatementRenewalStatus.FAILED, expires, detail = failure.message)
                }
            }
            // Listing must schedule accounts with persisted due rollover work even when statements are not near expiry.
            if (applied.listOnly && dueCount == 0 && store.listCommitted(scope, "key-state").any {
                    (it.document["switch_at"] as? JsonPrimitive)?.longOrNull?.let { due -> due <= now } == true &&
                        (it.document["switched"] as? JsonPrimitive)?.booleanOrNull != true
                }) outcomes += StatementRenewalOutcome(RenewedStatementKind.ENTITY_CONFIGURATION, node.account_id, null, StatementRenewalStatus.DUE, now)
        }
        return IdkResult.ok(StatementRenewalReport(now, outcomes))
    }
}
