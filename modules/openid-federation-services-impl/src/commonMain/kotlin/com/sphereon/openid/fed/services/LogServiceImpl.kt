package com.sphereon.openid.fed.services

import com.sphereon.core.api.log.LogLevel
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Log
import com.sphereon.openid.fed.services.command.log.GetLogsBySeverityArgs
import com.sphereon.openid.fed.services.command.log.GetLogsBySeverityCommand
import com.sphereon.openid.fed.services.command.log.GetLogsByTagArgs
import com.sphereon.openid.fed.services.command.log.GetLogsByTagCommand
import com.sphereon.openid.fed.services.command.log.GetRecentLogsArgs
import com.sphereon.openid.fed.services.command.log.GetRecentLogsCommand
import com.sphereon.openid.fed.services.command.log.InsertLogArgs
import com.sphereon.openid.fed.services.command.log.InsertLogCommand
import com.sphereon.openid.fed.services.command.log.SearchLogsArgs
import com.sphereon.openid.fed.services.command.log.SearchLogsCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of LogService as a command aggregator.
 *
 * This service aggregates all log-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<LogService>())
class LogServiceImpl(
    private val insertLogCommand: InsertLogCommand,
    private val getRecentLogsCommand: GetRecentLogsCommand,
    private val searchLogsCommand: SearchLogsCommand,
    private val getLogsBySeverityCommand: GetLogsBySeverityCommand,
    private val getLogsByTagCommand: GetLogsByTagCommand
) : LogService {

    override suspend fun insertLog(
        level: LogLevel,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ): FederationResult<Unit> =
        insertLogCommand.execute(InsertLogArgs(level, message, tag, timestamp, throwable, metadata)).toFederationResult()

    override suspend fun getRecentLogs(limit: Long): FederationResult<List<Log>> =
        getRecentLogsCommand.execute(GetRecentLogsArgs(limit)).toFederationResult()

    override suspend fun searchLogs(searchTerm: String, limit: Long): FederationResult<List<Log>> =
        searchLogsCommand.execute(SearchLogsArgs(searchTerm, limit)).toFederationResult()

    override suspend fun getLogsBySeverity(severity: String, limit: Long): FederationResult<List<Log>> =
        getLogsBySeverityCommand.execute(GetLogsBySeverityArgs(severity, limit)).toFederationResult()

    override suspend fun getLogsByTag(tag: String, limit: Long): FederationResult<List<Log>> =
        getLogsByTagCommand.execute(GetLogsByTagArgs(tag, limit)).toFederationResult()
}
