package com.sphereon.openid.fed.client.test

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.asFederationClientComponent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FederationClientJsTest {

    private val client: FederationClient

    init {
        configureKmsProvider()

        val app = ClientTestAppComponent::class.create(
            this, "federation-client-test-js", "test", "0.25.0"
        )
        app.initRootScopeProvider()
        val context = app.userContextManager.getAnonymous()
        val session = context.sessionContextManager.createOrGetFromId("test-session")
        client = session.asFederationClientComponent().federationClient
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

    @Test
    fun testDiComponentCreation() {
        assertNotNull(client, "FederationClient should be created via DI")
    }

    @Test
    fun testGetEntityConfiguration() = runTest {
        val serverUrl = js("process.env.FEDERATION_SERVER_URL").unsafeCast<String?>()
        if (serverUrl != null) {
            val entityConfig = client.entityConfigurationStatementGet(serverUrl)
            assertNotNull(entityConfig, "Entity configuration should not be null")
            assertNotNull(entityConfig.iss, "Entity configuration should have an issuer")
        }
    }

    @Test
    fun testResolveTrustChain() = runTest {
        val serverUrl = js("process.env.FEDERATION_SERVER_URL").unsafeCast<String?>()
        val trustAnchorUrl = js("process.env.FEDERATION_TRUST_ANCHOR_URL").unsafeCast<String?>()
        if (serverUrl != null && trustAnchorUrl != null) {
            val response = client.trustChainResolve(
                entityIdentifier = serverUrl,
                trustAnchors = arrayOf(trustAnchorUrl),
                maxDepth = 5
            )
            assertNotNull(response, "Trust chain resolve response should not be null")
        }
    }

    companion object {
        private const val SWAMID_LEAF = "https://oidf-dco-poc-rp-1.swamid.se"
    }

    @Test
    fun testGetEntityConfigurationSwamidLeaf() = runTest {
        val trustAnchorUrl = js("process.env.FEDERATION_TRUST_ANCHOR_URL").unsafeCast<String?>()
            ?: return@runTest
        val entityConfig = client.entityConfigurationStatementGet(SWAMID_LEAF)
        assertNotNull(entityConfig, "SWAMID leaf entity configuration should not be null")
        assertEquals(SWAMID_LEAF, entityConfig.iss, "iss should match the SWAMID leaf identifier")
    }

    @Test
    fun testResolveTrustChainSwamidLeaf() = runTest {
        val trustAnchorUrl = js("process.env.FEDERATION_TRUST_ANCHOR_URL").unsafeCast<String?>()
            ?: return@runTest
        val response = client.trustChainResolve(
            entityIdentifier = SWAMID_LEAF,
            trustAnchors = arrayOf(trustAnchorUrl),
            maxDepth = 5
        )
        assertNotNull(response, "Trust chain resolve response should not be null")
        assertTrue(response.trustChain.isNotEmpty(), "Trust chain should not be empty: ${response.errorMessage}")
        // leaf + intermediate(s) + trust anchor = at least 3
        assertTrue(response.trustChain.size >= 3, "Trust chain should have at least 3 entries (leaf + intermediate + TA), got ${response.trustChain.size}")
    }
}
