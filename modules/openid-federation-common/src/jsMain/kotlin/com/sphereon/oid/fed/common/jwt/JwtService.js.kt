package com.sphereon.oid.fed.common.jwt

import kotlinx.coroutines.await
import kotlin.js.Promise

@ExperimentalJsExport
@JsExport
interface IJwtServiceJS {
    fun sign(input: JwtSignInput): Promise<String>
    fun verify(input: JwtVerifyInput): Promise<Boolean>
}

@ExperimentalJsExport
@JsExport
interface JwtCallbackServiceJS: ICallbackService<IJwtServiceJS>, IJwtServiceJS

@ExperimentalJsExport
@JsExport
object JwtServiceObjectJS: JwtCallbackServiceJS {

    private lateinit var platformCallback: IJwtServiceJS

    private var disabled = false;

    override fun disable(): IJwtServiceJS {
        this.disabled = true
        return this
    }

    override fun enable(): IJwtServiceJS {
        this.disabled = false
        return this
    }

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun register(platformCallback: IJwtServiceJS): JwtCallbackServiceJS {
        this.platformCallback = platformCallback
        return this
    }


    override fun sign(input: JwtSignInput): Promise<String> {
        if (!isEnabled()) {
            JwtConst.LOG.info("JWT sign has been disabled")
            throw IllegalStateException("JWT sign is disabled cannot sign")
        } else if (!this::platformCallback.isInitialized) {
            JwtConst.LOG.info("JWT callback (JS) is not registered")
            throw IllegalStateException("JWT have not been initialized. Please register your JWTCallbackJS, or a default implementation")
        }
        return platformCallback.sign(input)
    }

    override fun verify(input: JwtVerifyInput): Promise<Boolean> {
        if (!isEnabled()) {
            JwtConst.LOG.info("JWT verify has been disabled")
            throw IllegalStateException("JWT verify is disabled cannot sign")
        } else if (!this::platformCallback.isInitialized) {
            JwtConst.LOG.info("JWT callback (JS) is not registered")
            throw IllegalStateException("JWT have not been initialized. Please register your JWTCallbackJS, or a default implementation")
        }
        return platformCallback.verify(input)
    }
}

open class JwtServiceJSAdapter @OptIn(ExperimentalJsExport::class) constructor(val jwtcallbackJS: JwtServiceObjectJS = JwtServiceObjectJS):
JwtCallbackService {
    @OptIn(ExperimentalJsExport::class)
    override fun disable(): IJwtService {
        this.jwtcallbackJS.disable()
        return this
    }

    @OptIn(ExperimentalJsExport::class)
    override fun enable(): IJwtService {
        this.jwtcallbackJS.enable()
        return this
    }

    @OptIn(ExperimentalJsExport::class)
    override fun isEnabled(): Boolean {
        return this.jwtcallbackJS.isEnabled()
    }

    override fun register(platformCallback: IJwtService): ICallbackService<IJwtService> {
        throw Error("Register function should not be used on the adapter. It depends on the Javascript jwtService object")
    }

    @OptIn(ExperimentalJsExport::class)
    override suspend fun sign(input: JwtSignInput): String {
        JwtConst.LOG.error("Creating JWT sign signature...")
        return try {
            this.jwtcallbackJS.sign(input).await()
        } catch (e: Exception) {
                throw e
        }.also {
            JwtConst.LOG.error("Created JWT sign signature: $it")
        }
    }

    @OptIn(ExperimentalJsExport::class)
    override suspend fun verify(input: JwtVerifyInput): Boolean {
        JwtConst.LOG.error("Verifying JWT signature...")
        return try {
            this.jwtcallbackJS.verify(input).await()
        } catch (e: Exception) {
            throw e
        }.also {
            JwtConst.LOG.error("Verified JWT signature: $it")
        }
    }

}

@OptIn(ExperimentalJsExport::class)
object JWTServiceJSAdapterObject:  JwtServiceJSAdapter(JwtServiceObjectJS)

actual fun jwtService(): JwtCallbackService = JWTServiceJSAdapterObject
