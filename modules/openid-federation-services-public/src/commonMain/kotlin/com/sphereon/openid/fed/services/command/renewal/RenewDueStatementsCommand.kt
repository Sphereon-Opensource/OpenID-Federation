package com.sphereon.openid.fed.services.command.renewal

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/**
 * Internal maintenance command. Republishes every published Entity Configuration and Subordinate Statement whose
 * latest stored statement expires at or before `now + renewBeforeSeconds`, so public endpoints keep serving
 * unexpired statements (OpenID Federation 1.1 §3: `exp` is required and statements past it are invalid).
 *
 * Each statement is rebuilt from the current persisted state and signed with the issuing account's currently selected
 * signing key, exactly as an operator publish would. Only statements that were published at least once are renewed;
 * deleted accounts and subordinates are skipped. Persisting is a compare-and-set under the issuing account's row
 * lock: a statement another instance or operator renewed meanwhile, or one signed with a key that is no longer
 * selected, is discarded rather than stored, so concurrent instances never publish twice or with a stale key.
 */
@Serializable
data class RenewDueStatementsArgs(
    /** Renew statements whose stored `exp` is at or before `now + renewBeforeSeconds`. Must be positive. */
    val renewBeforeSeconds: Long,
    /** Evaluation time in epoch seconds; the current time when null. */
    val nowEpochSeconds: Long? = null,
    /** Only statements issued by this account; every account when null. */
    val accountId: String? = null,
    /** Report the due statements as [StatementRenewalStatus.DUE] without signing or storing anything. */
    val listOnly: Boolean = false,
)

@Serializable
enum class RenewedStatementKind { ENTITY_CONFIGURATION, SUBORDINATE_STATEMENT }

@Serializable
enum class StatementRenewalStatus {
    /** Due for renewal; reported by a [RenewDueStatementsArgs.listOnly] evaluation. */
    DUE,

    /** A renewed statement was stored and is now served. */
    RENEWED,

    /** Another renewal or publish stored a statement that is no longer due; the signed candidate was discarded. */
    ALREADY_RENEWED,

    /** The account's selected signing key changed while signing; the candidate was discarded and is retried later. */
    SIGNING_KEY_CHANGED,

    /** The issuer or subordinate was deleted while renewing; nothing was stored. */
    NO_LONGER_PUBLISHED,

    /** Building, signing or storing failed; the previous statement stays in place and is retried later. */
    FAILED,
}

@Serializable
data class StatementRenewalOutcome(
    val kind: RenewedStatementKind,
    val accountId: String,
    val subordinateId: String? = null,
    val status: StatementRenewalStatus,
    /** `exp` of the statement that was due. */
    val previousExpiresAt: Long,
    /** `exp` of the stored renewed statement when [status] is [StatementRenewalStatus.RENEWED]. */
    val expiresAt: Long? = null,
    val detail: String? = null,
)

@Serializable
data class StatementRenewalReport(
    val evaluatedAtEpochSeconds: Long,
    val outcomes: List<StatementRenewalOutcome>,
) {
    fun count(status: StatementRenewalStatus): Int = outcomes.count { it.status == status }
}

interface RenewDueStatementsCommand : ServiceCommand<RenewDueStatementsArgs, StatementRenewalReport, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.statement.renew-due"
    }
}
