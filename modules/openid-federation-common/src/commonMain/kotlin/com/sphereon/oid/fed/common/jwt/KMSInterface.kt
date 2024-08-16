package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.json.JsonObject

interface KMSInterface {

    fun createJWT(header: JWTHeader, payload: JsonObject): String

}
