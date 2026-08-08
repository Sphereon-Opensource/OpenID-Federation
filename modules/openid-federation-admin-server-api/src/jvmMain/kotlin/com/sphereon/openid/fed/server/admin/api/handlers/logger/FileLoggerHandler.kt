package com.sphereon.openid.fed.server.admin.api.handlers.logger

/**
 * Removed: the legacy `com.sphereon.openid.fed.logger` package is gone.
 *
 * Use IDK `Log.app().setGlobalConfig(...)` and platform sinks for file output.
 * See `IdentityMode` / config docs — do not revive OIDF-specific logger writers.
 */
@Deprecated("Use IDK Log / SessionLogService")
object FileLoggerHandlerPlaceholder
