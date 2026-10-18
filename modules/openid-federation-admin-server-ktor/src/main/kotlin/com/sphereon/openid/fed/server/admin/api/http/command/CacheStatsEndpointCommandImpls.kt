package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.openid.fed.admin.*
import com.sphereon.core.api.http.response.errorResponse
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.di.session.SessionScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== Get Cache Stats Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetCacheStatsEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(GetCacheStatsEndpointCommand.COMMAND_ID)
class GetCacheStatsEndpointCommandImpl(
    execution: SessionExecution,
    private val cacheStats: ReadAdminCacheStatsCommand,
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
        val result = cacheStats.execute(Unit)
        return if (result.isOk) Ok(jsonResponse(200, result.value.toString()))
            else Ok(errorResponse(result.error.status, result.error.detail))
    }
}

// ==================== Clear Cache Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ClearCacheEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(ClearCacheEndpointCommand.COMMAND_ID)
class ClearCacheEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val clearCache: ClearAdminCacheCommand,
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

        val result = clearCache.execute(ClearAdminCacheArgs(namespace))
        return if (result.isOk) Ok(jsonResponse(200, result.value.toString()))
            else Ok(errorResponse(result.error.status, result.error.detail))
    }
}
