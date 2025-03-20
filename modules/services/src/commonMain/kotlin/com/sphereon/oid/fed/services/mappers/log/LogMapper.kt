package com.sphereon.oid.fed.services.mappers.log

import com.sphereon.oid.fed.openapi.models.Log
import com.sphereon.oid.fed.persistence.models.Log as LogEntity

fun LogEntity.toDTO(): Log = Log(
    id = this.id,
    severity = this.severity.toLogSeverity(),
    message = this.message,
    tag = this.tag,
    timestamp = this.timestamp,
    throwableMessage = this.throwable_message,
    throwableStacktrace = this.throwable_stacktrace
)

fun String.toLogSeverity(): Log.Severity {
    return when (this.uppercase()) {
        "VERBOSE" -> Log.Severity.Verbose
        "DEBUG" -> Log.Severity.Debug
        "INFO" -> Log.Severity.Info
        "WARN" -> Log.Severity.Warn
        "ERROR" -> Log.Severity.Error
        "ASSERT" -> Log.Severity.Assert
        else -> Log.Severity.Info
    }
}
