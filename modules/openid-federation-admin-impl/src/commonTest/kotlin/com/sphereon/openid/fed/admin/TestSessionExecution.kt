/*
 * (c) 2026 Sphereon International B.V.
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.conf.*
import com.sphereon.core.api.context.*
import com.sphereon.core.api.error.IdkErrorType
import com.sphereon.core.api.log.*
import com.sphereon.di.context.*
import com.sphereon.di.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun testSessionExecution(
    tenantId: String = "tenant-a",
    principalId: String = "principal-a",
    anonymous: Boolean = false,
): SessionExecution {
    val tenant = object : TenantContextData { override val tenantId = tenantId }
    val user = object : UserContext {
        override val id = principalId
        override val secureDetails: SecuredTenantContextDetails? = null
        override val tenant: TenantContextData = tenant
        override val principal: Any = principalId
    }
    val session = object : SessionContext {
        override val sessionId = "oidfed-test-session"
        override val correlationId = "oidfed-test-correlation"
        override val context: UserContext = user
        override fun isAnonymous() = anonymous
    }
    return object : SessionExecution {
        override val sessionContext = session
        override val sessionContextManager: SessionContextManager = TestSessionManager
        override val log: SessionLogService = TestSessionLog(session)
        override val conf: ContextConfig = TestContextConfig
    }
}

private object TestSessionManager : SessionContextManager {
    override val activeInstance: StateFlow<SessionInstance?> = MutableStateFlow(null)
    override fun getActive(): SessionInstance = error("not used")
    override fun hasActive() = false
    override fun getById(sessionId: String, makeActive: Boolean): SessionInstance? = null
    override fun hasById(sessionId: String) = false
    override fun activateById(sessionId: String) = false
    override fun listIds(): Set<String> = emptySet()
    override fun createOrGetFromCallbacks(sessionContextProvider: () -> SessionContext): SessionInstance = error("not used")
    override fun createOrGetFromId(sessionId: String, correlationId: String, makeActive: Boolean, secureDetails: SecuredTenantContextDetails?, principalType: PrincipalType): SessionInstance = error("not used")
    override fun destroyById(sessionId: String) = Unit
    override fun destroyAll() = Unit
    override fun getOrCreateBackgroundService(makeActive: Boolean): SessionInstance = error("not used")
    override fun getAnonymous(makeActive: Boolean): SessionInstance = error("not used")
    override fun getBackgroundServiceId() = "background"
}

private class TestSessionLog(override val sessionContext: SessionContext) : SessionLogService {
    override val id = "oidfed-test-log"
    override val isEnabled = false
    override val scope = IdkScope.SESSION
    override val logManager: SessionLogManager = object : SessionLogManager {
        override suspend fun setGlobalConfig(config: LoggerConfig): LogManager = this
        override suspend fun getGlobalConfig(): LoggerConfig = LoggerConfig.Default
        override fun withTagAsync(tag: String, config: LoggerConfig?): AsyncLogService = throw NotImplementedError("not used")
        override fun withTag(tag: String, config: LoggerConfig?): LogService = this@TestSessionLog
    }
    override suspend fun setConfig(config: LoggerConfig): LogService = this
    override fun executeAsync(message: LogMessage): IdkResult<Unit, IdkErrorType> = Ok(Unit)
    override fun toAsync(): AsyncLogService = throw NotImplementedError("not used")
}

private object TestContextConfig : ContextConfig {
    override val app: AppConfigService get() = error("not used")
    override val tenant: TenantConfigService get() = error("not used")
    override val principal: PrincipalConfigService get() = error("not used")
    override fun conf(level: ConfigLevel): ConfigService = error("not used")
}
