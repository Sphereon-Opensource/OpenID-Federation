package com.sphereon.openid.fed.server.admin.helpers

import com.sphereon.core.api.log.Log
import io.ktor.server.application.*
import io.ktor.server.request.*

class LoggerHelper {
    companion object {
        private val logger = Log.app().withTag("AdminServerRequestLogger")
        private val sensitiveHeaders = setOf(
            "authorization",
            "x-api-key",
            "api-key",
            "token",
            "password",
            "secret",
            "credential"
        ).map { it.lowercase() }

        private fun shouldLogHeader(headerName: String): Boolean {
            return !sensitiveHeaders.contains(headerName.lowercase())
        }

        fun logRequestDetailsDebug(call: ApplicationCall, operation: String) {
            val request = call.request
            val metadata = mutableMapOf<String, String>()

            metadata["operation"] = operation
            metadata["method"] = request.httpMethod.value
            metadata["uri"] = request.uri
            metadata["remote_addr"] = request.local.remoteAddress

            // Add headers as metadata
            request.headers.names().forEach { headerName ->
                val headerKey = "header_${headerName.lowercase().replace('-', '_')}"
                val headerValue = if (shouldLogHeader(headerName)) {
                    request.headers[headerName] ?: ""
                } else {
                    "[REDACTED]"
                }
                metadata[headerKey] = headerValue
            }

            // Add parameters as metadata
            request.queryParameters.names().forEach { paramName ->
                metadata["param_${paramName}"] = request.queryParameters[paramName] ?: ""
            }

            logger.debug(
                message = "Received $operation request",
                metadata = metadata.toMap()
            )
        }

        fun logRequestInfo(operation: String, username: String, call: ApplicationCall) {
            val request = call.request
            val metadata = mapOf(
                "operation" to operation,
                "username" to username,
                "remote_addr" to request.local.remoteAddress,
                "user_agent" to (request.headers["User-Agent"] ?: "")
            )

            logger.info(
                message = "Received $operation request",
                metadata = metadata
            )
        }
    }
}
