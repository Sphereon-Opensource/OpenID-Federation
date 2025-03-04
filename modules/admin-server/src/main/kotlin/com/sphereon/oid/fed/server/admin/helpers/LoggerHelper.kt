package com.sphereon.oid.fed.server.admin.helpers

import com.sphereon.oid.fed.logger.Logger
import jakarta.servlet.http.HttpServletRequest

class LoggerHelper {
    companion object {
        private val logger = Logger.tag("RequestLogger")
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

        fun logRequestDetailsDebug(request: HttpServletRequest, operation: String) {
            val metadata = buildMap {
                put("operation", operation)
                put("method", request.method)
                put("uri", request.requestURI)
                put("remote_addr", request.remoteAddr)
                put("protocol", request.protocol)

                // Add headers as metadata
                request.headerNames.asIterator().forEach { headerName ->
                    val headerKey = "header_${headerName.lowercase().replace('-', '_')}"
                    val headerValue = if (shouldLogHeader(headerName)) {
                        request.getHeader(headerName)
                    } else {
                        "[REDACTED]"
                    }
                    put(headerKey, headerValue)
                }

                // Add parameters as metadata
                request.parameterNames.asIterator().forEach { paramName ->
                    put("param_${paramName}", request.getParameter(paramName))
                }
            }


            logger.debug(
                message = "Received $operation request",
                metadata = metadata
            )
        }

        fun logRequestInfo(operation: String, username: String, request: HttpServletRequest) {
            val metadata = buildMap {
                put("operation", operation)
                put("username", username)
                put("remote_addr", request.remoteAddr)
                put("user_agent", request.getHeader("User-Agent"))
            }

            logger.info(
                message = "Received $operation request",
                metadata = metadata
            )
        }
    }
}
