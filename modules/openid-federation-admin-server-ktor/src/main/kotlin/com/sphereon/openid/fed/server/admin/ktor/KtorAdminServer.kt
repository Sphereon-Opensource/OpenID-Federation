package com.sphereon.openid.fed.server.admin.ktor

import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.core.api.log.Log
import com.sphereon.crypto.kms.keystore.memory.MemoryKeyStoreBackingStorage
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerAppGraph
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerConfig
import com.sphereon.openid.fed.server.admin.ktor.di.createAdminServerAppGraph
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

    // Configure default software KMS provider programmatically to ensure it's available
    // This can be overridden by environment variables if needed
    configureDefaultKmsProvider()

    // Create AppGraph with IDK DI
    // Configuration is loaded automatically via OidfConfigBinder
    val appGraph = createAdminServerAppGraph(
        application = Unit
    )
    logger.info("AppGraph created and initialized")

    // Debug: Print KMS provider config to verify it's being set correctly
    debugLogKmsProviderConfig(appGraph)

    // Get configuration from the component (loaded via OidfConfigBinder)
    val config = appGraph.serverConfig
    logger.info("Configuration loaded: rootIdentifier=${config.rootIdentifier}, port=${config.port}")

    // Start Ktor server
    embeddedServer(CIO, port = config.port, host = config.host) {
        configureAdmin(appGraph, config)
    }.start(wait = true)
}

/**
 * Configure the Ktor application for admin server.
 */
fun Application.configureAdmin(appGraph: AdminServerAppGraph, config: AdminServerConfig) {
    // Install kotlin-inject plugin with AppGraph
    install(KotlinInjectPlugin) {
        this.appGraph = appGraph
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

        // Debug endpoint to show backing storage state
        get("/debug/kms") {
            val backingStorage = appGraph.memoryKeyStoreBackingStorage
            val partitionCount = backingStorage.getPartitionCount()
            val partitionKeys = backingStorage.getPartitionKeys()

            val sb = StringBuilder()
            sb.appendLine("=== KMS Backing Storage Debug ===")
            sb.appendLine("Partition count: $partitionCount")
            sb.appendLine("Partition keys:")
            partitionKeys.forEach { key ->
                val partition = backingStorage.getPartition(key)
                sb.appendLine("  - $key -> keys: ${partition.keys.size}, certs: ${partition.certificates.size}")
                partition.keys.forEach { (alias, keyInfo) ->
                    sb.appendLine("      Key: alias=$alias, kid=${keyInfo.kid}, providerId=${keyInfo.providerId}")
                }
            }

            // Get KMS provider configs
            sb.appendLine()
            sb.appendLine("=== KMS Provider Configs ===")
            try {
                val configs = appGraph.kmsProviderConfigBinder.getKmsProviderConfigs(appGraph.appConfigService)
                configs.forEach { config ->
                    sb.appendLine("Provider: ${config.id} (${config.kmsProviderType})")
                    sb.appendLine("  enabled: ${config.enabled}")
                    // Try to access keyStore if it's a SoftwareKmsProviderConfig
                    try {
                        val softwareConfig = config as? com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
                        if (softwareConfig != null) {
                            sb.appendLine("  keyStore: ${softwareConfig.keyStore}")
                            sb.appendLine("    type: ${softwareConfig.keyStore.keyStoreType}")
                            sb.appendLine("    id: ${softwareConfig.keyStore.id}")
                            val memoryConfig = softwareConfig.keyStore as? com.sphereon.crypto.kms.keystore.memory.MemoryKeyStoreConfig
                            if (memoryConfig != null) {
                                sb.appendLine("    scopeBinding: ${memoryConfig.scopeBinding}")
                            }
                        }
                    } catch (e: Exception) {
                        sb.appendLine("  (Could not read keyStore config: ${e.message})")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("Error getting KMS configs: ${e.message}")
            }

            call.respondText(sb.toString())
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

/**
 * Configure the default software KMS provider via property source.
 * This ensures a working KMS provider is available for key operations.
 * The configuration can be overridden by environment variables if needed.
 *
 * IMPORTANT: Properties must use the namespace prefix matching appId.profile
 * The namespace for admin server is: openid-federation-admin-server.default
 */
private fun configureDefaultKmsProvider() {
    // Check if KMS provider is already configured via environment variables
    val envType = System.getenv("KMS_PROVIDERS_MEMORY_TYPE")
    if (envType != null) {
        logger.info("KMS provider configuration detected in environment variables")
        return
    }

    // The namespace must match the app component's appId and profile
    // AdminServerAppGraph uses appId="openid-federation-admin-server", profile="default"
    val namespace = "openid-federation-admin-server.default"

    logger.info("Configuring default software KMS provider programmatically with namespace: $namespace")
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
    logger.info("Default software KMS provider configured")
}

/**
 * Debug function to log the KMS provider configuration.
 * This helps verify that the config is being deserialized correctly.
 */
private fun debugLogKmsProviderConfig(appGraph: AdminServerAppGraph) {
    try {
        logger.info("=== DEBUG: KMS Config ===")

        // Get properties from DefaultAppMapPropertySource
        val properties = DefaultAppMapPropertySource.getSource()
        logger.info("Total properties in DefaultAppMapPropertySource: ${properties.size}")

        // Filter for KMS-related properties
        val kmsProperties = properties.filter { it.key.contains("kms", ignoreCase = true) }
        logger.info("KMS-related properties:")
        kmsProperties.forEach { (key, value) ->
            logger.info("  $key = $value")
        }

        logger.info("=== END DEBUG ===")
    } catch (e: Exception) {
        logger.error("Debug logging failed", e)
    }
}
