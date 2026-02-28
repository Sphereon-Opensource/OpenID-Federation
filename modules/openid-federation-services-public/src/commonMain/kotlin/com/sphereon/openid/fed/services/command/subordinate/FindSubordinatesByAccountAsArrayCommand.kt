package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account

data class FindSubordinatesByAccountAsArrayArgs(val account: Account)

interface FindSubordinatesByAccountAsArrayCommand : ServiceCommand<FindSubordinatesByAccountAsArrayArgs, Array<String>> {
    companion object {
        const val COMMAND_ID = "fed.subordinate.find-by-account-as-array"
    }
}
