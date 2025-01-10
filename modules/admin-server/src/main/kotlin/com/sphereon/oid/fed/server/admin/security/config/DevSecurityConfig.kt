package com.sphereon.oid.fed.server.admin.security.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
@Profile("dev")
class DevSecurityConfig {
    @Bean
    fun devFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }
            .csrf { it.disable() }
            .oauth2ResourceServer { it.disable() }
            .build()
    }
}
