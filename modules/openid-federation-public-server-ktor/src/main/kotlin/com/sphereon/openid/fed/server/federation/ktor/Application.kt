package com.sphereon.openid.fed.server.federation.ktor

import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import com.sphereon.openid.fed.common.exceptions.federation.FederationException
import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.log.LogLevel
import com.sphereon.core.api.log.LogOutputFormat
import com.sphereon.core.api.log.LoggerConfig
import kotlinx.coroutines.runBlocking
import com.sphereon.openid.fed.openapi.models.ErrorResponse
import com.sphereon.openid.fed.server.federation.ktor.di.FederationServerAppGraph
import com.sphereon.openid.fed.server.federation.ktor.di.FederationServerConfig
import com.sphereon.openid.fed.server.federation.ktor.di.createFederationServerAppGraph
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
    // Configure default software KMS provider programmatically to ensure it's available
    configureDefaultKmsProvider()

    // Create IDK graph
    // Configuration is loaded automatically via OidfConfigBinder
    val appGraph = createFederationServerAppGraph(
        application = Unit
    )

    // Get configuration from the graph (loaded via OidfConfigBinder)
    val config = appGraph.serverConfig

    // Configure logging using loaded config
    configureLogger(appGraph)

    logger.info("Federation server initialized")
    logger.info("Configuration: rootIdentifier=${config.rootIdentifier}, port=${config.port}")

    // Start the embedded server
    embeddedServer(CIO, port = config.port, host = config.host) {
        configureFederation(appGraph, config)
    }.start(wait = true)
}

/**
 * Configure the Ktor application for federation server.
 */
fun Application.configureFederation(appGraph: FederationServerAppGraph, config: FederationServerConfig) {
    // Install kotlin-inject plugin with AppGraph
    install(KotlinInjectPlugin) {
        this.appGraph = appGraph
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
private fun configureLogger(appGraph: FederationServerAppGraph) {
    val loggerSettings = appGraph.configBinder.getLoggerConfig()
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

/**
 * Configure the default software KMS provider via property source.
 * This ensures a working KMS provider is available for key operations.
 * The configuration can be overridden by environment variables if needed.
 *
 * IMPORTANT: Properties must use the namespace prefix matching appId.profile
 * The namespace for federation server is: openid-federation-server.default
 */
private fun configureDefaultKmsProvider() {
    // Check if KMS provider is already configured via environment variables
    val envType = System.getenv("KMS_PROVIDERS_MEMORY_TYPE")
    if (envType != null) {
        println("KMS provider configuration detected in environment variables")
        return
    }

    // The namespace must match the app component's appId and profile
    // FederationServerAppGraph uses appId="openid-federation-server", profile="default"
    val namespace = "openid-federation-server.default"

    println("Configuring default software KMS provider programmatically with namespace: $namespace")
    DefaultAppMapPropertySource.addProperties(
        mapOf(
            // Software/memory KMS provider configuration with proper namespace prefix
            "$namespace.kms.providers.memory.type" to "software",
            "$namespace.kms.providers.memory.id" to "memory",
            "$namespace.kms.providers.memory.enabled" to "true",
            "$namespace.kms.providers.memory.order" to "100",
            // Memory keystore with app-level scope for persistence across requests
            "$namespace.kms.providers.memory.keystore.type" to "memory",
            "$namespace.kms.providers.memory.keystore.id" to "oidf-memory-keystore",
            "$namespace.kms.providers.memory.keystore.keyvisibility" to "private",
            "$namespace.kms.providers.memory.keystore.scopebinding" to "app",
            // Also register keystore at the standalone keystores path for KeyStoreConfigBinder
            "$namespace.kms.keystores.oidf-memory-keystore.type" to "memory",
            "$namespace.kms.keystores.oidf-memory-keystore.id" to "oidf-memory-keystore",
            "$namespace.kms.keystores.oidf-memory-keystore.keyvisibility" to "private",
            "$namespace.kms.keystores.oidf-memory-keystore.scopebinding" to "app"
        )
    )
    println("Default software KMS provider configured")
}
