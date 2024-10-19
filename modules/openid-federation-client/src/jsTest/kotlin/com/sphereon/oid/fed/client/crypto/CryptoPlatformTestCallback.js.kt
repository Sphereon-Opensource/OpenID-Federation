package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.Promise

class CryptoPlatformCallback : ICryptoServiceCallbackJS {
    override fun verify(jwt: String, key: Jwk): Promise<Boolean> {
        return try {
            val decodedJwt = decodeJWTComponents(jwt)

            Jose.importJWK(
                JSON.parse<dynamic>(Json.encodeToString(key)), alg = decodedJwt.header.alg ?: "RS256"
            ).then { publicKey: dynamic ->
                val options: dynamic = js("({})")
                options["currentDate"] = js("new Date(Date.parse(\"Oct 14, 2024 01:00:00\"))")

                Jose.jwtVerify(jwt, publicKey, options).then { verification: dynamic ->
                    verification != undefined
                }.catch {
                    false
                }
            }.catch {
                false
            }
        } catch (e: Throwable) {
            Promise.resolve(false)
        }
    }
}
