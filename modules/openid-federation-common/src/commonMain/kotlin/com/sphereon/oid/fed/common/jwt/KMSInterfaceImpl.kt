package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.json.JsonObject

class KMSInterfaceImpl: KMSInterface {

    override fun createJWT(header: JWTHeader, payload: JsonObject):String {

        // TODO get key and pass it
        val jwt = sign(payload = payload, header = header, opts = mapOf())
        return jwt
    }
}