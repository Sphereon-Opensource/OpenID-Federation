package com.sphereon.openid.fed.server.admin

import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import com.sphereon.core.api.log.Log
import com.sphereon.openid.fed.server.admin.di.AdminServerAppComponent
import com.sphereon.openid.fed.server.admin.di.AdminServerConfig
import com.sphereon.openid.fed.server.admin.di.create
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

private val logger = Log.app().withTag("KtorAdminServer")

/**
 * Main entry point for the Ktor-based Admin Server.
 *
 * Configuration is loaded via IDK's OidfConfigBinder which supports:
 * - IDK-normalized environment variables (OIDF_FEDERATION_ROOT_IDENTIFIER)
 * - Legacy environment variables (ROOT_IDENTIFIER, ADMIN_SERVER_PORT, etc.)
 * - reference.conf defaults
 */
fun main() {
    logger.info("Starting OpenID Federation Admin Server (Ktor)...")

    // Create AppComponent with IDK DI
    // Configuration is loaded automatically via OidfConfigBinder
    val appComponent = AdminServerAppComponent.init(
        application = Unit
    )
    logger.info("AppComponent created and initialized")

    // Get configuration from the component (loaded via OidfConfigBinder)
    val config = appComponent.serverConfig
    logger.info("Configuration loaded: rootIdentifier=${config.rootIdentifier}, port=${config.port}")

    // Start Ktor server
    embeddedServer(CIO, port = config.port, host = config.host) {
        configureAdmin(appComponent, config)
    }.start(wait = true)
}

/**
 * Configure the Ktor application for admin server.
 */
fun Application.configureAdmin(appComponent: AdminServerAppComponent, config: AdminServerConfig) {
    // Install kotlin-inject plugin with AppComponent
    install(KotlinInjectPlugin) {
        this.appComponent = appComponent
    }
    logger.info("KotlinInject plugin installed - DI enabled")

    // Content negotiation
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // CORS configuration
    install(CORS) {
        config.corsAllowedOrigins.forEach { origin ->
            if (origin == "*") {
                anyHost()
            } else {
                allowHost(origin)
            }
        }
        config.corsAllowedMethods.forEach { method ->
            allowMethod(HttpMethod.parse(method))
        }
        config.corsAllowedHeaders.forEach { header ->
            if (header == "*") {
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.Accept)
            } else {
                allowHeader(header)
            }
        }
    }

    // Error handling
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respondText(
                text = """{"error": "${cause.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        // Health check
        get("/health") {
            call.respondText("OK")
        }
    }

    // Install universal HTTP adapters for admin API
    // The AdminHttpAdapter is automatically registered via DI (ContributesBinding)
    // and will handle all admin routes via the command-backed pattern
    installUniversalHttpAdapters {
        verboseLogging = config.devMode
        errorHandler = { call, e ->
            logger.error("Error handling request: ${call.request.uri}", e)
            call.respondText(
                text = """{"error": "${e.message?.replace("\"", "'")}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    logger.info("Universal HTTP adapters installed - Admin API ready")
}
