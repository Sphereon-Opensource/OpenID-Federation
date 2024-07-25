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

    /*
     * Used for mapping trust chain
     */
    fun mapTrustChain(jwtTokenList: List<String>): List<EntityStatement?> {
        val list: MutableList<EntityStatement?> = mutableListOf()
        jwtTokenList.forEach { jwtToken ->
            list.add(mapEntityStatement(jwtToken))
        }
        return list
    }
}