package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface LogCommandDescriptors {

    @Provides @IntoSet
    fun insertLog(cmd: Lazy<InsertLogCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(InsertLogCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getRecentLogs(cmd: Lazy<GetRecentLogsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetRecentLogsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun searchLogs(cmd: Lazy<SearchLogsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SearchLogsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getLogsBySeverity(cmd: Lazy<GetLogsBySeverityCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetLogsBySeverityCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getLogsByTag(cmd: Lazy<GetLogsByTagCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetLogsByTagCommand.COMMAND_ID) { cmd.value }
}
