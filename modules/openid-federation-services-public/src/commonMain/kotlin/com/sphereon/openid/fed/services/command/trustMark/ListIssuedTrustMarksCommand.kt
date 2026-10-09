package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

data class ListIssuedTrustMarksArgs(val tenantId: String)

/** A Trust Mark the account issued and has not revoked. */
@Serializable
data class IssuedTrustMark(
    val id: String,
    val trustMarkType: String,
    val sub: String,
    val trustMark: String,
    val iat: Long,
    val exp: Long? = null,
)

interface ListIssuedTrustMarksCommand : ServiceCommand<ListIssuedTrustMarksArgs, List<IssuedTrustMark>, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.list-issued"
    }
}
