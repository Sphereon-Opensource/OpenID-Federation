package com.sphereon.openid.fed.server.admin.ktor.renewal

import com.sphereon.openid.fed.core.config.StatementRenewalConfig
import com.sphereon.openid.fed.services.command.renewal.RenewedStatementKind
import com.sphereon.openid.fed.services.command.renewal.StatementRenewalOutcome
import com.sphereon.openid.fed.services.command.renewal.StatementRenewalReport
import com.sphereon.openid.fed.services.command.renewal.StatementRenewalStatus
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StatementRenewalSchedulerTest {
    private val schedule = StatementRenewalConfig.Resolved.Active(lifetimeSeconds = 3_600, renewBeforeSeconds = 1_800, intervalSeconds = 60)

    private fun report(vararg statuses: StatementRenewalStatus) = StatementRenewalReport(0, statuses.map {
        StatementRenewalOutcome(RenewedStatementKind.ENTITY_CONFIGURATION, "account", null, it, 10)
    })

    @Test
    fun checksImmediatelyAndThenEveryIntervalWithTheConfiguredMargin() = runTest {
        val margins = mutableListOf<Long>()
        val scheduler = StatementRenewalScheduler(schedule, StatementRenewalRunner { margin ->
            margins += margin
            listOf(report(StatementRenewalStatus.RENEWED))
        })
        val job = scheduler.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(1_800L), margins, "The first check runs at startup")
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, margins.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, margins.size, "The next check runs one interval later")
        job.cancel()
        assertEquals(2L, scheduler.metrics().renewed)
    }

    @Test
    fun aFailingCycleIsCountedAndTheNextCycleStillRuns() = runTest {
        var calls = 0
        val scheduler = StatementRenewalScheduler(schedule, StatementRenewalRunner {
            calls++
            if (calls == 1) error("database unavailable")
            listOf(report(StatementRenewalStatus.RENEWED, StatementRenewalStatus.FAILED, StatementRenewalStatus.ALREADY_RENEWED,
                StatementRenewalStatus.SIGNING_KEY_CHANGED, StatementRenewalStatus.NO_LONGER_PUBLISHED))
        })
        scheduler.runOnce()
        assertEquals(1L, scheduler.metrics().failedCycles)
        assertEquals("database unavailable", scheduler.metrics().lastCycleError)
        scheduler.runOnce()
        val metrics = scheduler.metrics()
        assertEquals(2L, metrics.cycles)
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L), listOf(metrics.renewed, metrics.failed, metrics.alreadyRenewed,
            metrics.signingKeyChanged, metrics.noLongerPublished))
        assertEquals(null, metrics.lastCycleError)
    }

    @Test
    fun theApplicationStartsRenewalAfterStartupAndStopsItOnShutdown() {
        val cycles = AtomicInteger()
        val firstCycle = CompletableDeferred<Unit>()
        val scheduler = StatementRenewalScheduler(schedule, StatementRenewalRunner {
            cycles.incrementAndGet()
            firstCycle.complete(Unit)
            emptyList()
        })
        testApplication {
            application { installStatementRenewal(scheduler) }
            startApplication()
            withTimeout(10_000) { firstCycle.await() }
        }
        val afterStop = cycles.get()
        assertTrue(afterStop >= 1)
        Thread.sleep(200)
        assertEquals(afterStop, cycles.get(), "No cycle runs after the application stopped")
    }

    @Test
    fun renewalScheduleDefaultsAndValidation() {
        assertEquals(StatementRenewalConfig.Resolved.Active(3_600, 1_800, 60), StatementRenewalConfig().resolve(3_600))
        assertEquals(StatementRenewalConfig.Resolved.Active(120, 60, 15), StatementRenewalConfig().resolve(120))
        assertEquals(StatementRenewalConfig.Resolved.Active(3_600, 900, 30),
            StatementRenewalConfig(renewBeforeSeconds = 900, intervalSeconds = 30).resolve(3_600))
        assertTrue(StatementRenewalConfig(enabled = false).resolve(3_600) is StatementRenewalConfig.Resolved.Disabled)
        assertTrue(StatementRenewalConfig().resolve(null) is StatementRenewalConfig.Resolved.Disabled)
        for (invalid in listOf(
            StatementRenewalConfig(renewBeforeSeconds = 3_600),
            StatementRenewalConfig(renewBeforeSeconds = -1),
            StatementRenewalConfig(renewBeforeSeconds = 600, intervalSeconds = 600),
            StatementRenewalConfig(intervalSeconds = -1),
        )) {
            assertTrue(invalid.resolve(3_600) is StatementRenewalConfig.Resolved.Invalid, invalid.toString())
        }
    }
}
