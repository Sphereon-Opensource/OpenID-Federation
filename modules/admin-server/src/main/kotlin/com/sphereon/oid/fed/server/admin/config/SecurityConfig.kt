package com.sphereon.oid.fed.server.admin.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Value("\${app.dev-mode:false}")
    private var devMode: Boolean = false

    @Value("\${app.cors.allowed-origins}")
    private lateinit var allowedOrigins: String

    @Value("\${app.cors.allowed-methods}")
    private lateinit var allowedMethods: String

    @Value("\${app.cors.allowed-headers}")
    private lateinit var allowedHeaders: String

    @Value("\${app.cors.max-age:3600}")
    private var maxAge: Long = 3600

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        if (devMode) {
            return http
                .authorizeHttpRequests { auth ->
                    auth.anyRequest().permitAll()
                }
                .csrf { it.disable() }
                .oauth2ResourceServer { it.disable() }
                .cors { }
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
            cors { }
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

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = allowedOrigins.split(",")
        configuration.allowedMethods = allowedMethods.split(",")
        configuration.allowedHeaders = allowedHeaders.split(",")
        configuration.maxAge = maxAge

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}