package com.sphereon.openid.fed.client.test

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.asFederationClientComponent

/**
 * Creates a [FederationClient] instance backed by the full DI graph.
 *
 * The returned client can be used directly from TypeScript — all return types
 * have `@JsExportCompat` and proper TypeScript definitions.
 */
@JsExport
fun createFederationClient(): FederationClient {
    configureKmsProvider()
    val app = ClientTestAppComponent::class.create(
        Unit, "federation-client-test-js", "test", "1.0.0"
    )
    app.initRootScopeProvider()
    val context = app.userContextManager.getAnonymous()
    val session = context.sessionContextManager.createOrGetFromId("default-session")
    return session.asFederationClientComponent().federationClient
}

private fun configureKmsProvider() {
    val namespace = "federation-client-test-js.test"
    DefaultAppMapPropertySource.addProperties(
        mapOf(
            "$namespace.kms.providers.memory.type" to "software",
            "$namespace.kms.providers.memory.id" to "memory",
            "$namespace.kms.providers.memory.enabled" to "true",
            "$namespace.kms.providers.memory.order" to "100",
            "$namespace.kms.providers.memory.keystore.type" to "memory",
            "$namespace.kms.providers.memory.keystore.id" to "oidf-memory-keystore",
            "$namespace.kms.providers.memory.keystore.keyvisibility" to "private",
            "$namespace.kms.providers.memory.keystore.scopebinding" to "app",
            "$namespace.kms.keystores.oidf-memory-keystore.type" to "memory",
            "$namespace.kms.keystores.oidf-memory-keystore.id" to "oidf-memory-keystore",
            "$namespace.kms.keystores.oidf-memory-keystore.keyvisibility" to "private",
            "$namespace.kms.keystores.oidf-memory-keystore.scopebinding" to "app"
        )
    )
}
