package com.sphereon.openid.fed.server.admin.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.http.jsonResponse
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.CacheStatistics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
        const val COMMAND_ID = "fed.admin.cache.stats.get"

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

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetCacheStatsEndpointCommand::class)
class GetCacheStatsEndpointCommandImpl(
    execution: SessionExecution,
    private val cacheManager: CacheManager,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetCacheStatsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetCacheStatsEndpointCommand.ENDPOINT
), GetCacheStatsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val stats = cacheManager.aggregateStats()

        val namespaceStats = stats.map { (_, stat) ->
            NamespaceCacheStats.fromCacheStatistics(stat)
        }

        val totalHits = stats.values.sumOf { it.hits }
        val totalMisses = stats.values.sumOf { it.misses }
        val totalLookups = totalHits + totalMisses
        val overallHitRate = if (totalLookups > 0) totalHits.toDouble() / totalLookups else 0.0

        val response = CacheStatsResponse(
            namespaces = namespaceStats,
            totalHits = totalHits,
            totalMisses = totalMisses,
            overallHitRate = overallHitRate
        )

        return Ok(jsonResponse(200, json.encodeToString(response)))
    }
}

// ==================== Clear Cache Endpoint ====================

interface ClearCacheEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.cache.clear"

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

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ClearCacheEndpointCommand::class)
class ClearCacheEndpointCommandImpl(
    execution: SessionExecution,
    private val cacheManager: CacheManager,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ClearCacheEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ClearCacheEndpointCommand.ENDPOINT
), ClearCacheEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val namespace = request.queryParameters["namespace"]

        if (namespace != null) {
            cacheManager.clear(namespace)
        } else {
            cacheManager.clearAll()
        }

        val response = mapOf(
            "success" to true,
            "message" to if (namespace != null) "Cache '$namespace' cleared" else "All caches cleared"
        )

        return Ok(jsonResponse(200, json.encodeToString(response)))
    }
}
