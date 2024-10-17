package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.types.ICallbackService
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise

@JsExport
@JsName("ICallbackCryptoService")
external interface ICallbackCryptoServiceJS<PlatformCallbackType> {
    /**
     * Register the platform specific callback that implements the verification
     *
     * External developers use this as an entry point for their platform code
     */
    @JsName("register")
    fun register(platformCallback: PlatformCallbackType?): ICallbackCryptoServiceJS<PlatformCallbackType>
}

@JsExport
external interface ICryptoServiceCallbackJS {
    @JsName("verify")
    fun verify(
        jwt: String,
    ): Promise<Boolean>
}

class DefaultPlatformCallbackJS : ICryptoServiceCallbackJS {
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
                Jose.jwtVerify(jwt, publicKey).then { verification: dynamic ->
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

@JsExport
object CryptoServiceJS : ICallbackCryptoServiceJS<ICryptoServiceCallbackJS>, ICryptoServiceCallbackJS {
    private lateinit var platformCallback: ICryptoServiceCallbackJS

    override fun register(platformCallback: ICryptoServiceCallbackJS?): CryptoServiceJS {
        this.platformCallback = (platformCallback ?: DefaultPlatformCallbackJS())
        return this
    }

    override fun verify(jwt: String): Promise<Boolean> {
        return platformCallback.verify(jwt)
    }
}

open class CryptoServiceJSAdapter(private val cryptoServiceCallbackJS: CryptoServiceJS = CryptoServiceJS) :
    ICryptoCallbackService {
    override fun register(platformCallback: ICryptoService?): ICallbackService<ICryptoService> {
        throw Error("Register function should not be used on the adapter. It depends on the Javascript CryptoService object")
    }

    override suspend fun verify(jwt: String): Boolean {
        return cryptoServiceCallbackJS.verify(jwt).await()
    }
}

object CryptoServiceJSAdapterObject : CryptoServiceJSAdapter(CryptoServiceJS)

actual fun cryptoService(): ICryptoCallbackService = CryptoServiceJSAdapterObject

@JsModule("jose")
@JsNonModule
external object Jose {
    fun importJWK(jwk: Jwk, alg: String, options: dynamic = definedExternally): Promise<dynamic>
    fun jwtVerify(jwt: String, key: Any, options: dynamic = definedExternally): Promise<Boolean>
}
