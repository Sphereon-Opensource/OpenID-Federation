package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Log

data class GetLogsByTagArgs(
    val tag: String,
    val limit: Long = 100L
)

interface GetLogsByTagCommand : ServiceCommand<GetLogsByTagArgs, List<Log>> {
    companion object {
        const val COMMAND_ID = "fed.log.get-by-tag"
    }
}
