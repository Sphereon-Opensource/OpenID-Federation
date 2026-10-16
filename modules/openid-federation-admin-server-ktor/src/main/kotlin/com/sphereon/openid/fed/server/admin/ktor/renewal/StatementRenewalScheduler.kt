package com.sphereon.openid.fed.server.admin.ktor.renewal

import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.DefaultPrincipalInputString
import com.sphereon.core.defaults.context.DefaultTenantInputString
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.StatementRenewalConfig
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerBindings
import com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs
import com.sphereon.openid.fed.services.command.renewal.StatementRenewalReport
import com.sphereon.openid.fed.services.command.renewal.StatementRenewalStatus
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val logger = Log.app().withTag("StatementRenewalScheduler")

/** Counters since process start; logged after every cycle that found due statements or failed. */
data class StatementRenewalMetrics(
    val cycles: Long,
    val failedCycles: Long,
    val renewed: Long,
    val alreadyRenewed: Long,
    val signingKeyChanged: Long,
    val noLongerPublished: Long,
    val failed: Long,
    val lastCycleAtEpochSeconds: Long?,
    val lastCycleError: String?,
)

/** One renewal pass over every account with due statements. */
fun interface StatementRenewalRunner {
    suspend fun runCycle(renewBeforeSeconds: Long): List<StatementRenewalReport>
}

/**
 * Periodically republishes stored statements before they expire. Every instance may run it: the renewal command's
 * compare-and-set under the issuing account's row lock stores at most one renewal per due statement, and the
 * schedule state is the stored statements themselves, so restarts and additional instances need no coordination.
 */
class StatementRenewalScheduler(
    private val schedule: StatementRenewalConfig.Resolved.Active,
    private val runner: StatementRenewalRunner,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val cycles = AtomicLong()
    private val failedCycles = AtomicLong()
    private val renewed = AtomicLong()
    private val alreadyRenewed = AtomicLong()
    private val signingKeyChanged = AtomicLong()
    private val noLongerPublished = AtomicLong()
    private val failed = AtomicLong()
    private val lastCycleAt = AtomicReference<Long?>(null)
    private val lastCycleError = AtomicReference<String?>(null)

    fun metrics() = StatementRenewalMetrics(
        cycles.get(), failedCycles.get(), renewed.get(), alreadyRenewed.get(), signingKeyChanged.get(),
        noLongerPublished.get(), failed.get(), lastCycleAt.get(), lastCycleError.get(),
    )

    /** Runs one cycle now and then every interval until [scope] or the returned job is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info(
            "Statement renewal active: lifetime=${schedule.lifetimeSeconds}s, renew before=${schedule.renewBeforeSeconds}s, " +
                "check interval=${schedule.intervalSeconds}s"
        )
        while (isActive) {
            runOnce()
            delay(schedule.intervalSeconds.seconds)
        }
    }

    /** One cycle; failures are counted and logged, never propagated, so the next cycle retries. */
    suspend fun runOnce() {
        cycles.incrementAndGet()
        lastCycleAt.set(clock())
        try {
            val reports = runner.runCycle(schedule.renewBeforeSeconds)
            val outcomes = reports.flatMap { it.outcomes }
            renewed.addAndGet(outcomes.count { it.status == StatementRenewalStatus.RENEWED }.toLong())
            alreadyRenewed.addAndGet(outcomes.count { it.status == StatementRenewalStatus.ALREADY_RENEWED }.toLong())
            signingKeyChanged.addAndGet(outcomes.count { it.status == StatementRenewalStatus.SIGNING_KEY_CHANGED }.toLong())
            noLongerPublished.addAndGet(outcomes.count { it.status == StatementRenewalStatus.NO_LONGER_PUBLISHED }.toLong())
            val cycleFailures = outcomes.count { it.status == StatementRenewalStatus.FAILED }
            failed.addAndGet(cycleFailures.toLong())
            lastCycleError.set(null)
            if (outcomes.isNotEmpty()) {
                val message = "Statement renewal cycle: due=${outcomes.size} failed=$cycleFailures; totals ${metrics()}"
                if (cycleFailures > 0) logger.warn(message) else logger.info(message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            failedCycles.incrementAndGet()
            lastCycleError.set(e.message ?: e::class.simpleName)
            logger.error("Statement renewal cycle failed; retrying in ${schedule.intervalSeconds}s", e)
        }
    }
}

/**
 * Runs renewal the way an admin request for the issuing account would: a listing in an anonymous session finds the
 * accounts with due statements, then each account renews in its own short-lived session bound to that account's
 * tenant, so the account's signing keys resolve exactly as for its operators.
 */
class SessionStatementRenewalRunner(
    private val appGraph: AdminServerBindings,
    private val configBinder: OidfConfigBinder,
) : StatementRenewalRunner {
    override suspend fun runCycle(renewBeforeSeconds: Long): List<StatementRenewalReport> {
        val listing = withSession(
            appGraph.userContextManager.getAnonymous().sessionContextManager
                .createOrGetFromId(sessionId(), makeActive = false, principalType = PrincipalType.ANONYMOUS)
        ) { it.execute(RenewDueStatementsArgs(renewBeforeSeconds, listOnly = true)) }
        val accounts = listing.outcomes.map { it.accountId }.distinct()
        val identity = configBinder.getIdentityConfig()
        return accounts.map { accountId ->
            val tenant = if (identity.isSessionAccountAligned) accountId else identity.sessionFixedTenantId.ifBlank { "default" }
            val user = appGraph.userContextManager.createOrGetFromInputs(
                DefaultTenantInputString(tenant), DefaultPrincipalInputString(RENEWAL_PRINCIPAL), makeActive = false,
            )
            withSession(
                user.sessionContextManager.createOrGetFromId(sessionId(), makeActive = false, principalType = PrincipalType.USER)
            ) { it.execute(RenewDueStatementsArgs(renewBeforeSeconds, accountId = accountId)) }
        }
    }

    private suspend fun withSession(
        session: SessionInstance,
        block: suspend (com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsCommand) ->
            com.sphereon.core.api.IdkResult<StatementRenewalReport, com.sphereon.openid.fed.core.error.FederationError>,
    ): StatementRenewalReport = try {
        val result = block((session.graph as StatementRenewalSessionGraph).renewDueStatementsCommand)
        if (result.isErr) throw IllegalStateException("Statement renewal failed: ${result.error.message.defaultMessage}")
        result.value
    } finally {
        session.destroy()
    }

    private fun sessionId() = "oidf-statement-renewal-" + UUID.randomUUID()

    companion object {
        const val RENEWAL_PRINCIPAL = "oidf-statement-renewal"
    }
}

/**
 * Starts [scheduler] when the application has started and stops it when the application stops, so no renewal runs
 * while routes or the DI graph are unavailable.
 */
fun Application.installStatementRenewal(scheduler: StatementRenewalScheduler) {
    val job = AtomicReference<Job?>(null)
    monitor.subscribe(ApplicationStarted) { application -> job.set(scheduler.start(application)) }
    monitor.subscribe(ApplicationStopping) { job.getAndSet(null)?.cancel() }
}
