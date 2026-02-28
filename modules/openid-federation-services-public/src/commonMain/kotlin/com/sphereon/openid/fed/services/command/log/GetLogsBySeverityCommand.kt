package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Log

data class GetLogsBySeverityArgs(
    val severity: String,
    val limit: Long = 100L
)

interface GetLogsBySeverityCommand : ServiceCommand<GetLogsBySeverityArgs, List<Log>> {
    companion object {
        const val COMMAND_ID = "fed.log.get-by-severity"
    }
}
