package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Log

data class GetRecentLogsArgs(
    val limit: Long = 100L
)

interface GetRecentLogsCommand : ServiceCommand<GetRecentLogsArgs, List<Log>> {
    companion object {
        const val COMMAND_ID = "fed.log.get-recent"
    }
}
