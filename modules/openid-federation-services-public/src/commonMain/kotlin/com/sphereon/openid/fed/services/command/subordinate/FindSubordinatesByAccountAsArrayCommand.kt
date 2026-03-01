package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.service.ServiceCommand


data class FindSubordinatesByAccountAsArrayArgs(val tenantId: String)

interface FindSubordinatesByAccountAsArrayCommand : ServiceCommand<FindSubordinatesByAccountAsArrayArgs, Array<String>> {
    companion object {
        const val COMMAND_ID = "fed.subordinate.find-by-account-as-array"
    }
}
