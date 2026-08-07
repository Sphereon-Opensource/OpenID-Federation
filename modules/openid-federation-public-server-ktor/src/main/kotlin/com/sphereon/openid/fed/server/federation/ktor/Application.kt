package com.sphereon.openid.fed.server.federation.ktor

import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import com.sphereon.openid.fed.common.exceptions.federation.FederationException
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.log.LogLevel
import com.sphereon.core.api.log.LogOutputFormat
import com.sphereon.core.api.log.LoggerConfig
import kotlinx.coroutines.runBlocking
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.openapi.models.ErrorResponse
import com.sphereon.openid.fed.server.federation.ktor.auth.OidfJwtAuthSupport.installOidfJwtAuthIfConfigured
import com.sphereon.openid.fed.server.federation.ktor.di.FederationServerAppGraph
import com.sphereon.openid.fed.server.federation.ktor.di.FederationServerConfig
import com.sphereon.openid.fed.server.federation.ktor.di.createFederationServerAppGraph
import com.sphereon.openid.fed.server.federation.ktor.session.OidfSessionTenantResolver
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
 * Configuration is loaded via OidfConfigBinder which supports:
 * - IDK-normalized environment variables (OIDF_SERVER_FEDERATION_PORT)
 * - Legacy environment variables (SERVER_PORT, ROOT_IDENTIFIER, etc.)
 * - Defaults seeded into IDK DefaultAppMapPropertySource (see [OidfConfigBootstrap])
 *
 * ## Bootstrap boundary
 * KMS + OIDF defaults must be seeded **before** AppGraph creation so session
 * KeyManagerService and OidfConfigBinder see the same property sources.
 * appId/profile here must match [createFederationServerAppGraph] defaults.
 */
fun main() {
    // --- Config / KMS bootstrap (IDK property maps) ---
    // Seeds oidf.* defaults + default software/memory KMS into DefaultAppMapPropertySource
    // and un-namespaced kms.providers.* into DefaultPrincipalMapPropertySource.
    // Session KeyManagerService resolves from principal config; app binders use app map
    // (including namespaced appId.profile keys). Env/deployer overrides already present win.
    // See OidfConfigBootstrap KDoc for the full IDK KmsKtor-aligned contract.
    OidfConfigBootstrap.seed(appId = "openid-federation-server", profile = "default")

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
    // Install kotlin-inject plugin with AppGraph.
    //
    // ## Session tenant boundary (L2)
    // Align IDK session.tenantId with federation Account.id in LEGACY mode so scoped
    // IDK resources match domain account_id. See OidfSessionTenantResolver KDoc.
    // Path-based public entity selection still uses TenantContextResolver separately.
    val identity = appGraph.configBinder.getIdentityConfig()
    install(KotlinInjectPlugin) {
        this.appGraph = appGraph
        tenantResolver = OidfSessionTenantResolver(appGraph.configBinder)
    }
    logger.info(
        "KotlinInject plugin installed - DI enabled " +
            "(identity.mode=${identity.mode}, session.alignment=${identity.sessionAlignment})",
    )

    // PLATFORM: optional JWT validation (requireAuth=false so OpenID Federation public
    // protocol endpoints remain anonymous when no Bearer is presented)
    installOidfJwtAuthIfConfigured(
        configBinder = appGraph.configBinder,
        requireAuth = false,
        anonymousPaths = listOf("/health", "/.well-known/**", "/**"),
    )

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


