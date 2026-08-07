package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand


data class FindSubordinatesByAccountAsArrayArgs(val tenantId: String)

interface FindSubordinatesByAccountAsArrayCommand : ServiceCommand<FindSubordinatesByAccountAsArrayArgs, Array<String>, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.subordinate.find-by-account-as-array"
    }
}
