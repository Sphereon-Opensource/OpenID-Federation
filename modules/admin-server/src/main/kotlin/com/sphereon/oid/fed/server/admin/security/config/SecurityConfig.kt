package com.sphereon.oid.fed.server.admin.security.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Value("\${app.dev-mode:false}")
    private var devMode: Boolean = false

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        if (devMode) {
            return http
                .authorizeHttpRequests { auth ->
                    auth.anyRequest().permitAll()
                }
                .csrf { it.disable() }
                .oauth2ResourceServer { it.disable() }
                .build()
        }

        http {
            authorizeRequests {
                authorize("/status", permitAll)
                authorize("/**", hasRole("admin"))
            }
            oauth2ResourceServer {
                jwt {
                    jwtAuthenticationConverter = jwtAuthenticationConverter()
                }
            }
            csrf { disable() }
        }

        return http.build()
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val grantedAuthoritiesConverter = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("roles") // Matches the claim name in Keycloak
            setAuthorityPrefix("ROLE_")      // Prefix to align with Spring Security expectations
        }
        val jwtAuthenticationConverter = JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter)
        }
        return jwtAuthenticationConverter
    }
}