package com.sphereon.oid.fed.server.admin.security.middlewares

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
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

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val accountUsername = request.getHeader(Constants.ACCOUNT_HEADER) ?: "root"

            log.debug("Using account username: {}", accountUsername)
            log.debug("Attempting to get account for username: {}", accountUsername)

            try {
                val account = accountService.getAccountByUsername(accountUsername)

                log.debug("Found account: {}", account)

                val accountIdentifier = accountService.getAccountIdentifierByAccount(account)

                log.debug("Found account identifier: {}", accountIdentifier)

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
