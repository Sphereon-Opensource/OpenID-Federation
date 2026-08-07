package com.sphereon.openid.fed.server.admin.api.handlers.logger

/**
 * Removed: the legacy `com.sphereon.openid.fed.logger` package is gone.
 *
 * Runtime logging uses IDK `com.sphereon.core.api.log.Log` / `SessionLogService`.
 * Federation **audit** history (if needed) remains the domain `Log` table via
 * `com.sphereon.openid.fed.services.LogService` — not a second logging framework.
 *
 * Do not reintroduce parallel log writers here; extend IDK logging sinks instead.
 */
@Deprecated("Use IDK Log / SessionLogService; keep domain LogService for audit only")
object DatabaseLoggerHandlerPlaceholder
