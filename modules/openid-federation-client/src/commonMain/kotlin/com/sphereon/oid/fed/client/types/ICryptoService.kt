package com.sphereon.oid.fed.client.types

import com.sphereon.oid.fed.openapi.models.Jwk
import kotlin.js.JsExport

interface ICryptoService {
    suspend fun verify(
        jwt: String,
        key: Jwk
    ): Boolean
}
