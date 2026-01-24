package com.sphereon.openid.fed.client

import com.sphereon.core.defaults.context.DefaultPrincipalInputString
import com.sphereon.core.defaults.context.DefaultTenantInputString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for FederationClientJS lifecycle management.
 *
 * These tests verify proper CoroutineScope management to prevent memory leaks
 * in long-running Node.js applications.
 */
class FederationClientJSTest {

    @Test
    fun testClientLifecycle() = runTest {
        // Initialize the test DI infrastructure
        val appComponent = FederationTestAppComponent.init(
            application = this,
            appId = "federation-client-test",
            profile = "test",
            version = "1.0.0"
        )

        // Create user context through proper IDK context management
        val contextInstance = appComponent.userContextManager.createOrGetFromInputs(
            DefaultTenantInputString("test-tenant"),
            DefaultPrincipalInputString("test-principal")
        )

        // Create session through proper IDK session management
        val sessionInstance = contextInstance.sessionContextManager.createOrGetFromId("test-session")

        // Get FederationClient using the extension function
        val federationClient = sessionInstance.asFederationClientComponent().federationClient

        // Create the JS wrapper
        val client = FederationClientJS(federationClient)

        // Initially not closed
        assertFalse(client.isClosed(), "Client should not be closed initially")

        // Close the client
        client.close()

        // Verify it's closed
        assertTrue(client.isClosed(), "Client should be closed after close()")
    }

    @Test
    fun testCloseIsIdempotent() = runTest {
        val appComponent = FederationTestAppComponent.init(
            application = this,
            appId = "federation-client-test",
            profile = "test",
            version = "1.0.0"
        )

        val contextInstance = appComponent.userContextManager.createOrGetFromInputs(
            DefaultTenantInputString("test-tenant"),
            DefaultPrincipalInputString("test-principal")
        )

        val sessionInstance = contextInstance.sessionContextManager.createOrGetFromId("test-session")
        val federationClient = sessionInstance.asFederationClientComponent().federationClient

        val client = FederationClientJS(federationClient)

        // Close multiple times - should not throw
        client.close()
        client.close()
        client.close()

        assertTrue(client.isClosed())
    }
}
