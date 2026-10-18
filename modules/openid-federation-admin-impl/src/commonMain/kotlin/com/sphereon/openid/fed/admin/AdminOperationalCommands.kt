package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.*
import kotlinx.serialization.json.*

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminCacheStatsCommand>())
class ReadAdminCacheStatsCommandImpl(execution: SessionExecution, private val cache: CacheManager) :
    TypedServiceCommandAdapter<Unit, JsonObject, AdminError>(ReadAdminCacheStatsCommand.COMMAND_ID, execution,
        inputTypeToken = typeToken<Unit>(), outputTypeToken = typeToken<JsonObject>()), ReadAdminCacheStatsCommand {
    override suspend fun doExecute(args: Unit, applyDuring: (Unit) -> Unit): IdkResult<JsonObject, AdminError> {
        applyDuring(args)
        val statistics = cache.aggregateStats()
        val namespaces = statistics.map { (name, stat) ->
            val size = if (stat.size > 0) stat.size else runCatching { cache.getCache<Any, Any>(name)?.size() ?: 0L }.getOrDefault(0L)
            buildJsonObject {
                put("namespace", name); put("hits", stat.hits); put("misses", stat.misses); put("hitRate", stat.hitRate)
                put("size", size); put("maxSize", stat.maxSize); put("utilization", stat.utilization)
                put("evictions", stat.evictions); put("expirations", stat.expirations)
            }
        }
        val hits = statistics.values.sumOf { it.hits }; val misses = statistics.values.sumOf { it.misses }
        return IdkResult.ok(buildJsonObject {
            put("namespaces", JsonArray(namespaces)); put("totalHits", hits); put("totalMisses", misses)
            put("overallHitRate", if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0)
        })
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ClearAdminCacheCommand>())
class ClearAdminCacheCommandImpl(execution: SessionExecution, private val cache: CacheManager) :
    TypedServiceCommandAdapter<ClearAdminCacheArgs, JsonObject, AdminError>(ClearAdminCacheCommand.COMMAND_ID, execution,
        inputTypeToken = typeToken<ClearAdminCacheArgs>(), outputTypeToken = typeToken<JsonObject>()), ClearAdminCacheCommand {
    override suspend fun doExecute(args: ClearAdminCacheArgs, applyDuring: (ClearAdminCacheArgs) -> ClearAdminCacheArgs): IdkResult<JsonObject, AdminError> {
        val namespace = applyDuring(args).namespace
        if (namespace == null) cache.clearAll() else cache.getCache<Any, Any>(namespace)?.clear()
        return IdkResult.ok(buildJsonObject {
            put("success", true); put("message", if (namespace == null) "All caches cleared" else "Cache '$namespace' cleared")
        })
    }
}
