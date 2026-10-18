package com.sphereon.openid.fed.admin

import com.sphereon.core.api.service.ServiceCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class ClearAdminCacheArgs(val namespace: String? = null)
interface ReadAdminCacheStatsCommand : ServiceCommand<Unit, JsonObject, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-cache.stats" }
}
interface ClearAdminCacheCommand : ServiceCommand<ClearAdminCacheArgs, JsonObject, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-cache.clear" }
}
