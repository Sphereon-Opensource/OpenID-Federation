package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.openid.fed.core.cache.CacheStatistics
import kotlinx.serialization.Serializable

// ==================== Cache Stats Response Models ====================

/**
 * Response model for cache statistics.
 */
@Serializable
data class CacheStatsResponse(
    val namespaces: List<NamespaceCacheStats>,
    val totalHits: Long,
    val totalMisses: Long,
    val overallHitRate: Double
)

/**
 * Statistics for a single cache namespace.
 */
@Serializable
data class NamespaceCacheStats(
    val namespace: String,
    val hits: Long,
    val misses: Long,
    val hitRate: Double,
    val size: Long,
    val maxSize: Long,
    val utilization: Double,
    val evictions: Long = 0,
    val expirations: Long = 0
) {
    companion object {
        fun fromCacheStatistics(stats: CacheStatistics): NamespaceCacheStats {
            return NamespaceCacheStats(
                namespace = stats.namespace,
                hits = stats.hits,
                misses = stats.misses,
                hitRate = stats.hitRate,
                size = stats.size,
                maxSize = stats.maxSize,
                utilization = stats.utilization,
                evictions = stats.evictions,
                expirations = stats.expirations
            )
        }
    }
}

// ==================== Get Cache Stats Endpoint ====================

interface GetCacheStatsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.get-cache-stats"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/cache/stats",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getCacheStats",
            tags = setOf("cache"),
            summary = "Get cache statistics for all managed caches"
        )
    }
}

// ==================== Clear Cache Endpoint ====================

interface ClearCacheEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.clear-cache"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/cache/clear",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "clearCache",
            tags = setOf("cache"),
            summary = "Clear all caches or a specific namespace"
        )
    }
}
