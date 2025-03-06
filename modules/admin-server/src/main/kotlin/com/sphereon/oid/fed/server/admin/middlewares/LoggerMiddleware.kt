package com.sphereon.oid.fed.server.admin.middlewares

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.server.admin.helpers.LoggerHelper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class LoggerMiddleware : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return request.requestURI.endsWith("/status")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val operation = getOperationName(request)
            LoggerHelper.logRequestDetailsDebug(request, operation)

            if (isWriteOperation(request)) {
                val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as? Account
                if (account != null) {
                    val action = "${operation.lowercase()} ${getActionType(request)}"
                    LoggerHelper.logRequestInfo(action, account.username, request)
                }
            }

            filterChain.doFilter(request, response)
        } catch (e: Exception) {
            logger.error("Error in logging middleware: ${e.message}", e)
            filterChain.doFilter(request, response)
        }
    }

    private fun getOperationName(request: HttpServletRequest): String {
        val baseResource = request.requestURI.split("/")[1]
        return when (request.method.uppercase()) {
            "GET" -> "$baseResource Retrieval"
            "POST" -> "$baseResource Creation"
            "PUT" -> "$baseResource Update"
            "DELETE" -> "$baseResource Deletion"
            else -> baseResource
        }
    }

    private fun getActionType(request: HttpServletRequest): String {
        return request.requestURI.split("/")[1].removeSuffix("s")
    }

    private fun isWriteOperation(request: HttpServletRequest): Boolean {
        val method = request.method.uppercase()
        return method == "POST" || method == "PUT" || method == "DELETE"
    }
}
