package com.sphereon.oid.fed.server.admin.config

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.logger.Logger.Severity
import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver
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

    @Scheduled(fixedRateString = "\${monitoring.health.interval:60000}")
    fun monitorHealth() {
        val currentTime = Instant.now()
        val uptime = Duration.between(startTime, currentTime)

        val usedMemory = (runtime.totalMemory() - runtime.freeMemory())
        val totalMemory = runtime.totalMemory()
        val memoryUsagePercent = (usedMemory.toDouble() / totalMemory.toDouble() * 100).toInt()
        val severity =
            if (memoryUsagePercent >= memoryWarningThresholdPercent) Severity.Warn else Severity.Info

        val dbMetrics = PlatformSqlDriver.Companion.getConnectionMetrics()

        val message = buildString {
            append("System Health: ")
            append("Uptime=${uptime.toHours()}h${uptime.toMinutesPart()}m, ")
            append("Memory=${usedMemory / 1024 / 1024}MB/${totalMemory / 1024 / 1024}MB($memoryUsagePercent%), ")
            append("Processors=${runtime.availableProcessors()}, ")
            append("Heap=${memoryMBean.heapMemoryUsage.used / 1024 / 1024}MB, ")
            append("Threads=${threadMBean.threadCount}(Peak:${threadMBean.peakThreadCount}, Daemon:${threadMBean.daemonThreadCount}), ")
            append("DB Connections: Active=${dbMetrics["Active Connections"] ?: "N/A"}, ")
            append("Idle=${dbMetrics["Idle Connections"] ?: "N/A"}, ")
            append("Total=${dbMetrics["Total Connections"] ?: "N/A"}")
        }

        val contextMap = mutableMapOf(
            "uptime_hours" to uptime.toHours().toString(),
            "uptime_minutes" to uptime.toMinutesPart().toString(),
            "memory_used_mb" to (usedMemory / 1024 / 1024).toString(),
            "memory_total_mb" to (totalMemory / 1024 / 1024).toString(),
            "memory_usage_percent" to memoryUsagePercent.toString(),
            "processors" to runtime.availableProcessors().toString(),
            "heap_used_mb" to (memoryMBean.heapMemoryUsage.used / 1024 / 1024).toString(),
            "thread_count" to threadMBean.threadCount.toString(),
            "thread_peak" to threadMBean.peakThreadCount.toString(),
            "thread_daemon" to threadMBean.daemonThreadCount.toString()
        )

        // Add DB metrics to context
        dbMetrics.forEach { (key, value) ->
            contextMap["db_${key.lowercase().replace(' ', '_')}"] = value.toString()
        }

        logger.info(
            message = message,
            metadata = contextMap
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
}
