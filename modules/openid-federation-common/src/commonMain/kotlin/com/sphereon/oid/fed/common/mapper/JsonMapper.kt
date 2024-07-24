package com.sphereon.oid.fed.common.mapper

import com.sphereon.oid.fed.openapi.models.EntityStatement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

class JsonMapper {

    /*
    * Used for mapping JWT token to EntityStatement object
     */
    fun mapEntityStatement(jwtToken: String): EntityStatement? {
        val data = decodeJWTComponents(jwtToken)
        return if (data.second != null) {
            Json.decodeFromJsonElement(data.second!!)
        } else {
            null
        }
    }
}