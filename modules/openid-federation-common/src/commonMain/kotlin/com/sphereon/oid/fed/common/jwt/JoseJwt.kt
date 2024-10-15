package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonElement
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.jvm.JvmStatic

@ExperimentalJsExport
@JsExport
data class JwtSignInput (
    val payload: Map<String, JsonElement>,
    val header: JWTHeader,
    val key: Jwk
)

@ExperimentalJsExport
@JsExport
data class JwtVerifyInput (
    val jwt: String,
    val key: Jwk
)

interface IJwtService {
    suspend fun sign(input: JwtSignInput): String
    suspend fun verify(input: JwtVerifyInput): Boolean
}

interface JwtCallbackService: ICallbackService<IJwtService>, IJwtService

expect fun jwtService(): JwtCallbackService

object JwtServiceObject: JwtCallbackService {

    @JvmStatic
    private lateinit var platformCallback: IJwtService

    private var disabled = false;

    @OptIn(ExperimentalJsExport::class)
    override suspend fun sign(input: JwtSignInput): String {
        if (!isEnabled()) {
            JwtConst.LOG.info("JWT sign has been disabled")
            throw IllegalStateException("JWT sign is disabled cannot sign")
        } else if (!this::platformCallback.isInitialized) {
            JwtConst.LOG.info("JWT callback (JS) is not registered")
            throw IllegalStateException("JWT have not been initialized. Please register your JWTCallbackJS, or a default implementation")
        }
        return platformCallback.sign(input)
    }

    @OptIn(ExperimentalJsExport::class)
    override suspend fun verify(input: JwtVerifyInput): Boolean {
        if (!isEnabled()) {
            JwtConst.LOG.info("JWT verify has been disabled")
            throw IllegalStateException("JWT verify is disabled cannot sign")
        } else if (!this::platformCallback.isInitialized) {
            JwtConst.LOG.info("JWT callback (JS) is not registered")
            throw IllegalStateException("JWT have not been initialized. Please register your JWTCallbackJS, or a default implementation")
        }
        return platformCallback.verify(input)
    }

    override fun disable(): IJwtService {
        this.disabled = true
        return this
    }

    override fun enable(): IJwtService {
        this.disabled = false
        return this
    }

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun register(platformCallback: IJwtService): JwtCallbackService {
        this.platformCallback = platformCallback
        return this
    }
}
