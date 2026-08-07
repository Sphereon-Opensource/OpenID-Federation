package com.sphereon.openid.fed.services.command.log

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.log.LogLevel
import com.sphereon.core.api.service.ServiceCommand

data class InsertLogArgs(
    val level: LogLevel,
    val message: String,
    val tag: String,
    val timestamp: Long,
    val throwable: Throwable?,
    val metadata: Map<String, String>
)

interface InsertLogCommand : ServiceCommand<InsertLogArgs, Unit, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.log.insert"
    }
}
