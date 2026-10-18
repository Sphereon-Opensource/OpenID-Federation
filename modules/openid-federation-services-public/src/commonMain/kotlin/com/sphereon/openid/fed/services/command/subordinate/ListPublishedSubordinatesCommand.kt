package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/** Resolved account scope and protocol /list filters; independently executable without HTTP. */
@Serializable
data class ListPublishedSubordinatesArgs(
    val accountId: String,
    val entityType: String? = null,
    val trustMarked: Boolean? = null,
    val trustMarkType: String? = null,
    val intermediate: Boolean? = null,
)
interface ListPublishedSubordinatesCommand : ServiceCommand<ListPublishedSubordinatesArgs, Array<String>, FederationError> {
    companion object { const val COMMAND_ID = "fed.subordinate.list-published" }
}
