package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.di.session.SessionScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== Get Cache Stats Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetCacheStatsEndpointCommand>())
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
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val stats = cacheManager.aggregateStats()

        val namespaceStats = stats.map { (namespace, stat) ->
            // Prefer live size() when stats.size is still 0 on some IDK backends
            val size = if (stat.size > 0L) {
                stat.size
            } else {
                runCatching {
                    cacheManager.getCache<Any, Any>(namespace)?.size() ?: 0L
                }.getOrDefault(0L)
            }
            NamespaceCacheStats.fromCacheStatistics(namespace, stat).copy(size = size)
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

// ==================== Clear Cache Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ClearCacheEndpointCommand>())
class ClearCacheEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val cacheManager: CacheManager,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ClearCacheEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ClearCacheEndpointCommand.ENDPOINT
), ClearCacheEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        // PLATFORM: reject anonymous admin mutations (no-op in LEGACY)
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)
        val namespace = request.queryParameters["namespace"]

        if (namespace != null) {
            cacheManager.getCache<Any, Any>(namespace)?.clear()
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
