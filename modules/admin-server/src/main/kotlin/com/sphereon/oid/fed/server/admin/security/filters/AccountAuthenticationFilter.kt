package com.sphereon.oid.fed.server.admin.security.filters

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.services.AccountService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class AccountAuthenticationFilter(private val accountService: AccountService) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication != null && authentication.isAuthenticated) {
            val username = request.getHeader(Constants.ACCOUNT_HEADER) ?: Constants.DEFAULT_ROOT_USERNAME

            try {
                val account = accountService.getAccountByUsername(username)
                val accountIdentifier = accountService.getAccountIdentifier(username)

                request.setAttribute(Constants.ACCOUNT_ATTRIBUTE, account)
                request.setAttribute(Constants.ACCOUNT_IDENTIFIER_ATTRIBUTE, accountIdentifier)

            } catch (e: NotFoundException) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Account not found: $username")
                return
            } catch (e: Exception) {
                response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error processing account data: ${e.message}"
                )
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}
