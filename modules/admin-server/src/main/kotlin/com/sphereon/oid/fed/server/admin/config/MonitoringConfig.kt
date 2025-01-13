package com.sphereon.oid.fed.server.admin.config

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.logger.Logger.Severity
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant

@Configuration
class MonitoringConfig {
    @Value("\${monitoring.memory.warning-threshold-percent:80}")
    private var memoryWarningThresholdPercent: Int = 80

    @Value("\${monitoring.load.warning-threshold:0.8}")
    private var loadWarningThreshold: Double = 0.8

    private val logger = Logger.tag("AdminServerMonitoring")
    private val startTime: Instant = Instant.now()
    private val runtime = Runtime.getRuntime()
    private val memoryMBean = ManagementFactory.getMemoryMXBean()
    private val threadMBean = ManagementFactory.getThreadMXBean()
    private val gcMBeans = ManagementFactory.getGarbageCollectorMXBeans()

    @Scheduled(fixedRateString = "\${monitoring.health.interval:60000}")
    fun monitorHealth() {
        val currentTime = Instant.now()
        val uptime = Duration.between(startTime, currentTime)

        val usedMemory = (runtime.totalMemory() - runtime.freeMemory())
        val totalMemory = runtime.totalMemory()
        val memoryUsagePercent = (usedMemory.toDouble() / totalMemory.toDouble() * 100).toInt()
        val severity =
            if (memoryUsagePercent >= memoryWarningThresholdPercent) Severity.Warn else Severity.Info

        logger.info(
            "System Health: " +
                    "Uptime=${uptime.toHours()}h${uptime.toMinutesPart()}m, " +
                    "Memory=${usedMemory / 1024 / 1024}MB/${totalMemory / 1024 / 1024}MB($memoryUsagePercent%), " +
                    "Processors=${runtime.availableProcessors()}, " +
                    "Heap=${memoryMBean.heapMemoryUsage.used / 1024 / 1024}MB, " +
                    "Threads=${threadMBean.threadCount}(Peak:${threadMBean.peakThreadCount}, Daemon:${threadMBean.daemonThreadCount})"
        )

        if (severity == Severity.Warn) {
            logger.warn("Memory usage is high: $memoryUsagePercent% (threshold: $memoryWarningThresholdPercent%)")
        }
    }

    @Scheduled(fixedRateString = "\${monitoring.load.interval:300000}")
    fun checkSystemLoad() {
        val systemLoad = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        if (systemLoad > 0) {
            val severity = if (systemLoad >= loadWarningThreshold) Severity.Warn else Severity.Info
            val message = "System Load Average: ${"%.2f".format(systemLoad)}"

            if (severity == Severity.Warn) {
                logger.warn("$message (exceeds threshold: $loadWarningThreshold)")
            } else {
                logger.info(message)
            }
        }
    }

    private fun getGCStats(): String {
        return buildString {
            append("Garbage Collection:")
            gcMBeans.forEach { gc ->
                append("\n  - ${gc.name}: Count=${gc.collectionCount}, Time=${gc.collectionTime}ms")
            }
        }
    }
}
