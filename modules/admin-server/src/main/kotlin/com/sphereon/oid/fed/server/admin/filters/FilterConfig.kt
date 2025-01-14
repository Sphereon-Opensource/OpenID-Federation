package com.sphereon.oid.fed.server.admin.filters

import com.sphereon.oid.fed.server.admin.middlewares.AccountMiddleware
import com.sphereon.oid.fed.server.admin.middlewares.LoggerMiddleware
import com.sphereon.oid.fed.services.AccountService
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class FilterConfig(
    private val accountService: AccountService,
    private val loggerMiddleware: LoggerMiddleware
) {
    @Bean
    fun accountFilterRegistration(): FilterRegistrationBean<AccountMiddleware> {
        val registration = FilterRegistrationBean<AccountMiddleware>()
        registration.filter = AccountMiddleware(accountService)
        registration.setUrlPatterns(listOf("/*"))
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }

    @Bean
    fun loggerFilterRegistration(): FilterRegistrationBean<LoggerMiddleware> {
        val registration = FilterRegistrationBean<LoggerMiddleware>()
        registration.filter = loggerMiddleware
        registration.setUrlPatterns(listOf("/*"))
        registration.order = Ordered.HIGHEST_PRECEDENCE + 1
        return registration
    }
}