package com.sphereon.oid.fed.server.admin.middlewares

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.services.AccountService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

class AccountMiddleware(
    private val accountService: AccountService
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(AccountMiddleware::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return request.requestURI.endsWith("/status")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val accountUsername = request.getHeader(Constants.ACCOUNT_HEADER) ?: "root"

            log.debug(
                "Processing request with account details - URI: {}, Method: {}, Username: {}, Headers: {}",
                request.requestURI,
                request.method,
                accountUsername,
                request.headerNames.toList().associateWith { request.getHeader(it) }
            )

            try {
                val account = accountService.getAccountByUsername(accountUsername)
                log.debug("Retrieved account details: {}", account)

                val accountIdentifier = accountService.getAccountIdentifierByAccount(account)
                log.debug("Retrieved account identifier details: {}", accountIdentifier)
                request.setAttribute(Constants.ACCOUNT_ATTRIBUTE, account)
                request.setAttribute(Constants.ACCOUNT_IDENTIFIER_ATTRIBUTE, accountIdentifier)

            } catch (e: NotFoundException) {
                log.debug("NotFoundException: {}", e.message)
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found: $accountUsername")
                return
            }

            filterChain.doFilter(request, response)
        } catch (e: Exception) {
            log.error("Unexpected error: {}", e.message, e)
            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Error processing account: ${e.message}"
            )
        }
    }
}

fun getAccountFromRequest(request: HttpServletRequest): Account {
    val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) ?: throw NotFoundException("Account not found")
    return account as Account
}
