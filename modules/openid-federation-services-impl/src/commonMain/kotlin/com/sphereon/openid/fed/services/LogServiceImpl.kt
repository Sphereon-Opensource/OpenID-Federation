package com.sphereon.openid.fed.services

import com.sphereon.core.api.log.LogLevel
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Log
import com.sphereon.openid.fed.services.command.log.GetLogsBySeverityCommand
import com.sphereon.openid.fed.services.command.log.GetLogsByTagCommand
import com.sphereon.openid.fed.services.command.log.GetRecentLogsCommand
import com.sphereon.openid.fed.services.command.log.InsertLogCommand
import com.sphereon.openid.fed.services.command.log.SearchLogsCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
@ContributesBinding(SessionScope::class, boundType = LogService::class)
class LogServiceImpl(
    private val insertLogCommand: InsertLogCommand,
    private val getRecentLogsCommand: GetRecentLogsCommand,
    private val searchLogsCommand: SearchLogsCommand,
    private val getLogsBySeverityCommand: GetLogsBySeverityCommand,
    private val getLogsByTagCommand: GetLogsByTagCommand
) : LogService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : LogService.Commands {
        override val insertLog: InsertLogCommand
            get() = this@LogServiceImpl.insertLogCommand

        override val getRecentLogs: GetRecentLogsCommand
            get() = this@LogServiceImpl.getRecentLogsCommand

        override val searchLogs: SearchLogsCommand
            get() = this@LogServiceImpl.searchLogsCommand

        override val getLogsBySeverity: GetLogsBySeverityCommand
            get() = this@LogServiceImpl.getLogsBySeverityCommand

        override val getLogsByTag: GetLogsByTagCommand
            get() = this@LogServiceImpl.getLogsByTagCommand
    }

    override val commands: LogService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun insertLog(
        level: LogLevel,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ): FederationResult<Unit> =
        insertLogCommand.insertLog(level, message, tag, timestamp, throwable, metadata)

    override suspend fun getRecentLogs(limit: Long): FederationResult<List<Log>> =
        getRecentLogsCommand.getRecentLogs(limit)

    override suspend fun searchLogs(searchTerm: String, limit: Long): FederationResult<List<Log>> =
        searchLogsCommand.searchLogs(searchTerm, limit)

    override suspend fun getLogsBySeverity(severity: String, limit: Long): FederationResult<List<Log>> =
        getLogsBySeverityCommand.getLogsBySeverity(severity, limit)

    override suspend fun getLogsByTag(tag: String, limit: Long): FederationResult<List<Log>> =
        getLogsByTagCommand.getLogsByTag(tag, limit)
}
