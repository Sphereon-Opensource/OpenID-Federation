package com.sphereon.openid.fed.server

import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import com.sphereon.openid.fed.common.exceptions.federation.FederationException
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.log.LogLevel
import com.sphereon.core.api.log.LogOutputFormat
import com.sphereon.core.api.log.LoggerConfig
import kotlinx.coroutines.runBlocking
import com.sphereon.openid.fed.openapi.models.ErrorResponse
import com.sphereon.openid.fed.server.di.FederationServerAppComponent
import com.sphereon.openid.fed.server.di.FederationServerConfig
import com.sphereon.openid.fed.server.di.create
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

private val logger = Log.app().withTag("Application")

/**
 * Main entry point for the Federation Server.
 *
 * Configuration is loaded via IDK's OidfConfigBinder which supports:
 * - IDK-normalized environment variables (OIDF_SERVER_FEDERATION_PORT)
 * - Legacy environment variables (SERVER_PORT, ROOT_IDENTIFIER, etc.)
 * - reference.conf defaults
 */
fun main() {
    // Create IDK component hierarchy
    // Configuration is loaded automatically via OidfConfigBinder
    val appComponent = FederationServerAppComponent.init(
        application = Unit
    )

    // Get configuration from the component (loaded via OidfConfigBinder)
    val config = appComponent.serverConfig

    // Configure logging using loaded config
    configureLogger(appComponent)

    logger.info("Federation server initialized")
    logger.info("Configuration: rootIdentifier=${config.rootIdentifier}, port=${config.port}")

    // Start the embedded server
    embeddedServer(CIO, port = config.port, host = config.host) {
        configureFederation(appComponent, config)
    }.start(wait = true)
}

/**
 * Configure the Ktor application for federation server.
 */
fun Application.configureFederation(appComponent: FederationServerAppComponent, config: FederationServerConfig) {
    // Install kotlin-inject plugin with AppComponent
    install(KotlinInjectPlugin) {
        this.appComponent = appComponent
    }
    logger.info("KotlinInject plugin installed - DI enabled")

    // Configure standard plugins
    configurePlugins(config)

    // Health check endpoint
    routing {
        get("/health") {
            call.respondText("OK")
        }
    }

    // Install universal HTTP adapters for federation API
    // The FederationHttpAdapter is automatically registered via DI (ContributesBinding)
    // and will handle all federation routes via the command-backed pattern
    installUniversalHttpAdapters {
        verboseLogging = System.getenv("APP_DEV_MODE")?.toBoolean() ?: false
        errorHandler = { call, e ->
            logger.error("Error handling request: ${call.request.uri}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "server_error",
                    errorDescription = e.message ?: "An unexpected error occurred"
                )
            )
        }
    }

    logger.info("Universal HTTP adapters installed - Federation API ready")
}

/**
 * Configures the logger with settings from OidfConfigBinder.
 */
private fun configureLogger(appComponent: FederationServerAppComponent) {
    val loggerSettings = appComponent.configBinder.getLoggerConfig()
    val severityStr = loggerSettings.severity
    val outputFormatStr = loggerSettings.output
    val includeTimestamp = loggerSettings.includeTimestamp

    val minLevel = try {
        LogLevel.valueOf(severityStr.uppercase())
    } catch (e: IllegalArgumentException) {
        println("Failed to parse severity '$severityStr', defaulting to DEBUG. Error: ${e.message}")
        LogLevel.DEBUG
    }

    val outputFormat = try {
        LogOutputFormat.valueOf(outputFormatStr.uppercase())
    } catch (e: IllegalArgumentException) {
        println("Failed to parse output format '$outputFormatStr', defaulting to TEXT. Error: ${e.message}")
        LogOutputFormat.TEXT
    }

    println("Logger configured with minLevel: ${minLevel.name}, output format: ${outputFormat.name}, includeTimestamp: $includeTimestamp")

    val loggerConfig = LoggerConfig(
        minLevel = minLevel,
        outputFormat = outputFormat,
        includeTimestamp = includeTimestamp
    )

    // Configure the global logger
    runBlocking {
        Log.app().setGlobalConfig(loggerConfig)
    }
}

/**
 * Configures Ktor plugins for the application.
 */
private fun Application.configurePlugins(config: FederationServerConfig) {
    // Content negotiation for JSON
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

    // Call logging
    install(CallLogging) {
        level = Level.INFO
    }

    // Status pages for error handling
    install(StatusPages) {
        // Handle federation-specific exceptions
        exception<FederationException> { call, cause ->
            val statusCode = HttpStatusCode.fromValue(cause.httpStatus)
            Log.app().withTag("StatusPages").debug("Federation exception: ${cause::class.simpleName} - ${cause.errorDescription}")
            call.respond(
                statusCode,
                ErrorResponse(
                    error = cause.error,
                    errorDescription = cause.errorDescription
                )
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "invalid_request",
                    errorDescription = cause.message ?: "Invalid request"
                )
            )
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(
                    error = "not_found",
                    errorDescription = cause.message ?: "Resource not found"
                )
            )
        }
        exception<Throwable> { call, cause ->
            Log.app().withTag("StatusPages").error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "server_error",
                    errorDescription = cause.message ?: "An unexpected error occurred"
                )
            )
        }
    }
}
