package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Date
import kotlin.js.Promise

class CryptoPlatformCallback : ICryptoServiceCallbackJS {
    override fun verify(jwt: String): Promise<Boolean> {
        return try {
            val decodedJwt = decodeJWTComponents(jwt)
            val kid = decodedJwt.header.kid

            val jwk = decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
                ?.firstOrNull { it.jsonObject["kid"]?.jsonPrimitive?.content == kid }
                ?: throw Exception("JWK not found")

            Jose.importJWK(
                JSON.parse<dynamic>(Json.encodeToString(jwk)), alg = decodedJwt.header.alg ?: "RS256"
            ).then { publicKey: dynamic ->
                Jose.jwtVerify(jwt, publicKey, {
                    val currentDate = Date.parse("2021-01-01T00:00:00Z")
                    val maxTokenAge = 0
                }).then { verification: dynamic ->
                    println("Verification result: $verification")
                    verification != undefined
                }.catch { error ->
                    println("Error during JWT verification: $error")
                    false
                }
            }.catch { error ->
                println("Error importing JWK: $error")
                false
            }
        } catch (e: Throwable) {
            println("Error: $e")
            Promise.resolve(false)
        }
    }
}
