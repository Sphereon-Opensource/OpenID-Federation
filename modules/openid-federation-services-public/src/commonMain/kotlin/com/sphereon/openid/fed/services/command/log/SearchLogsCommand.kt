package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Log

data class SearchLogsArgs(
    val searchTerm: String,
    val limit: Long = 100L
)

interface SearchLogsCommand : ServiceCommand<SearchLogsArgs, List<Log>> {
    companion object {
        const val COMMAND_ID = "fed.log.search"
    }
}
