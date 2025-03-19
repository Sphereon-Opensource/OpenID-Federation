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
    enum class DetailLevel {
        BASIC,
        STANDARD,
        DETAILED
    }

    @Value("\${monitoring.memory.warning-threshold-percent:80}")
    private var memoryWarningThresholdPercent: Int = 80

    // TODO: This does very little, as load is relative to the amount of cores/processors. So on a multicore system a load of 0.8 could be totally fine
    @Value("\${monitoring.load.warning-threshold:0.8}")
    private var loadWarningThreshold: Double = 0.8

    @Value("\${monitoring.detail-level:STANDARD}")
    private var detailLevelString: String = "STANDARD"

    private val logger = Logger.tag("AdminServerMonitoring")
    private val startTime: Instant = Instant.now()
    private val runtime = Runtime.getRuntime()
    private val memoryMBean = ManagementFactory.getMemoryMXBean()
    private val threadMBean = ManagementFactory.getThreadMXBean()

    private val detailLevel: DetailLevel
        get() = try {
            DetailLevel.valueOf(detailLevelString.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid monitoring detail level: $detailLevelString, using STANDARD instead")
            DetailLevel.STANDARD
        }

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

        val contextMap = mutableMapOf(
            "timestamp" to currentTime.toString(),
            "uptime_seconds" to uptime.seconds.toString(),
            "memory_used_mb" to (usedMemory / 1024 / 1024).toString(),
            "memory_total_mb" to (totalMemory / 1024 / 1024).toString(),
            "memory_usage_percent" to memoryUsagePercent.toString()
        )

        if (detailLevel.ordinal >= DetailLevel.STANDARD.ordinal) {
            contextMap.putAll(
                mapOf(
                    "uptime_hours" to uptime.toHours().toString(),
                    "uptime_minutes" to uptime.toMinutesPart().toString(),

                    "memory_max_mb" to (runtime.maxMemory() / 1024 / 1024).toString(),
                    "heap_used_mb" to (memoryMBean.heapMemoryUsage.used / 1024 / 1024).toString(),
                    "heap_committed_mb" to (memoryMBean.heapMemoryUsage.committed / 1024 / 1024).toString(),

                    "processors" to runtime.availableProcessors().toString(),

                    "thread_count" to threadMBean.threadCount.toString(),
                    "thread_peak" to threadMBean.peakThreadCount.toString(),
                    "thread_daemon" to threadMBean.daemonThreadCount.toString()
                )
            )

            val systemLoad = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
            if (systemLoad >= 0) {
                contextMap["system_load"] = String.format("%.2f", systemLoad)
            }

            contextMap["jvm_name"] = System.getProperty("java.vm.name")
            contextMap["java_version"] = System.getProperty("java.version")

            dbMetrics.forEach { (key, value) ->
                contextMap["db_${key.lowercase().replace(' ', '_')}"] = value.toString()
            }
        }

        if (detailLevel.ordinal >= DetailLevel.DETAILED.ordinal) {
            contextMap.putAll(
                mapOf(
                    "heap_max_mb" to (memoryMBean.heapMemoryUsage.max / 1024 / 1024).toString(),
                    "non_heap_used_mb" to (memoryMBean.nonHeapMemoryUsage.used / 1024 / 1024).toString(),
                    "thread_started_total" to threadMBean.totalStartedThreadCount.toString(),
                    "jvm_version" to System.getProperty("java.vm.version")
                )
            )

            try {
                contextMap["hostname"] = java.net.InetAddress.getLocalHost().hostName
                contextMap["process_id"] = ManagementFactory.getRuntimeMXBean().name.split("@")[0]
            } catch (e: Exception) {
                logger.debug("Could not obtain system identifiers: ${e.message}")
            }

            ManagementFactory.getMemoryPoolMXBeans().forEach { pool ->
                val usage = pool.usage
                contextMap["memory_pool_${pool.name.lowercase().replace(' ', '_')}_used_mb"] =
                    (usage.used / 1024 / 1024).toString()
                if (usage.max > 0) {
                    contextMap["memory_pool_${pool.name.lowercase().replace(' ', '_')}_max_mb"] =
                        (usage.max / 1024 / 1024).toString()
                }
            }

            ManagementFactory.getGarbageCollectorMXBeans().forEach { gc ->
                contextMap["gc_${gc.name.lowercase().replace(' ', '_')}_count"] = gc.collectionCount.toString()
                contextMap["gc_${gc.name.lowercase().replace(' ', '_')}_time_ms"] = gc.collectionTime.toString()
            }
        }

        logger.info(
            message = "system health check (detail level: $detailLevelString)",
            metadata = contextMap
        )

        if (severity == Severity.Warn) {
            logger.warn("Memory usage is high: $memoryUsagePercent% (threshold: $memoryWarningThresholdPercent%)")
        }
    }

    @Scheduled(fixedRateString = "\${monitoring.load.interval:300000}")
    fun checkSystemLoad() {
        if (detailLevel.ordinal < DetailLevel.STANDARD.ordinal) {
            return
        }

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
