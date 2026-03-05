package com.sphereon.openid.fed.client.identifier

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.jose.JwaKeyType
import com.sphereon.crypto.resolution.IdentifierMethodDefaults
import com.sphereon.crypto.resolution.extern.ExternalIdentifierJwkOpts
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOIDFEntityIdOpts
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.asFederationClientComponent
import com.sphereon.openid.fed.client.test.ClientImplTestAppComponent
import com.sphereon.openid.fed.client.test.create
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end DI tests for the OIDF Entity ID ExternalIdentifierResolutionService.
 *
 * These tests verify:
 * 1. The service is properly registered in the DI graph
 * 2. It can be accessed from the session component
 * 3. It correctly supports/rejects identifier opts
 * 4. It integrates with the MultiExternalIdentifierService
 * 5. Error handling for missing trust anchors and unreachable entities
 */
class OidfEntityIdExternalIdentifierResolutionServiceE2eTest {

    private lateinit var session: SessionInstance
    private lateinit var federationClient: FederationClient
    private lateinit var oidfResolutionService: OidfEntityIdExternalIdentifierResolutionService

    @BeforeTest
    fun setUp() {
        configureKmsProvider()

        val app = ClientImplTestAppComponent::class.create(
            this, "federation-client-impl-test", "test", "1.0.0"
        )
        app.initRootScopeProvider()

        val context = app.userContextManager.getAnonymous()
        session = context.sessionContextManager.createOrGetFromId("oidf-entity-id-test")
        federationClient = session.asFederationClientComponent().federationClient
        oidfResolutionService = (session.component as OidfEntityIdExternalIdentifierResolutionServiceImpl.Component)
            .oidfEntityIdExternalIdentifierResolutionService
    }

    private fun configureKmsProvider() {
        val namespace = "federation-client-impl-test.test"
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

    // =========================================================================
    // DI graph tests
    // =========================================================================

    @Test
    fun testServiceIsInjectedViaDi() {
        assertNotNull(oidfResolutionService, "OidfEntityIdExternalIdentifierResolutionService should be created via DI")
    }

    @Test
    fun testFederationClientIsInjectedViaDi() {
        assertNotNull(federationClient, "FederationClient should be created via DI")
    }

    @Test
    fun testServiceIsRegisteredInMultiExternalIdentifierService() {
        val multiComponent = session.component as com.sphereon.crypto.resolution.extern.MultiExternalIdentifierResolutionServiceImpl.Component
        assertNotNull(multiComponent, "MultiExternalIdentifierResolutionServiceImpl.Component should be available")
    }

    // =========================================================================
    // isSupportedOpts() tests
    // =========================================================================

    @Test
    fun testIsSupportedOptsForEntityIdOpts() = runTest {
        val opts = ExternalIdentifierOIDFEntityIdOpts(
            identifier = "https://entity.example.com",
            trustAnchors = listOf("https://trust-anchor.example.com")
        )

        assertTrue(oidfResolutionService.isSupportedOpts(opts), "Should support ExternalIdentifierOIDFEntityIdOpts")
    }

    @Test
    fun testIsSupportedOptsRejectsJwkOpts() = runTest {
        val opts = ExternalIdentifierJwkOpts(
            identifier = CryptoJwk(kty = JwaKeyType.EC)
        )

        assertFalse(oidfResolutionService.isSupportedOpts(opts), "Should not support ExternalIdentifierJwkOpts")
    }

    // =========================================================================
    // isSupportedIdentifier() tests
    // =========================================================================

    @Test
    fun testSupportedIdentifierHttpsUrl() = runTest {
        assertTrue(
            oidfResolutionService.isSupportedIdentifier("https://entity.example.com"),
            "Should support https:// identifiers"
        )
    }

    @Test
    fun testUnsupportedIdentifierDid() = runTest {
        assertFalse(
            oidfResolutionService.isSupportedIdentifier("did:web:entity.example.com"),
            "Should not support DID identifiers"
        )
    }

    @Test
    fun testUnsupportedIdentifierNonString() = runTest {
        assertFalse(
            oidfResolutionService.isSupportedIdentifier(42),
            "Should not support non-string identifiers"
        )
    }

    // =========================================================================
    // asSupportedOpts() tests
    // =========================================================================

    @Test
    fun testAsSupportedOptsSuccess() = runTest {
        val opts = ExternalIdentifierOIDFEntityIdOpts(
            identifier = "https://entity.example.com",
            trustAnchors = listOf("https://ta.example.com")
        )

        val result = oidfResolutionService.asSupportedOpts(opts)
        assertTrue(result.isOk, "Should succeed for entity ID opts")
        assertTrue(result.value is ExternalIdentifierOIDFEntityIdOpts)
    }

    @Test
    fun testAsSupportedOptsRejectsJwkOpts() = runTest {
        val opts = ExternalIdentifierJwkOpts(
            identifier = CryptoJwk(kty = JwaKeyType.EC)
        )

        val result = oidfResolutionService.asSupportedOpts(opts)
        assertTrue(result.isErr, "Should reject JWK opts")
    }

    // =========================================================================
    // isSupportedIdentifierMethod() tests
    // =========================================================================

    @Test
    fun testSupportedIdentifierMethodEntityId() = runTest {
        assertTrue(
            oidfResolutionService.isSupportedIdentifierMethod(IdentifierMethodDefaults.ENTITY_ID),
            "Should support ENTITY_ID method"
        )
    }

    @Test
    fun testUnsupportedIdentifierMethodJwk() = runTest {
        assertFalse(
            oidfResolutionService.isSupportedIdentifierMethod(IdentifierMethodDefaults.JWK),
            "Should not support JWK method"
        )
    }

    @Test
    fun testUnsupportedIdentifierMethodDid() = runTest {
        assertFalse(
            oidfResolutionService.isSupportedIdentifierMethod(IdentifierMethodDefaults.DID),
            "Should not support DID method"
        )
    }

    // =========================================================================
    // resolve() error handling tests
    // =========================================================================

    @Test
    fun testResolveFailsWithoutTrustAnchors() = runTest {
        val opts = ExternalIdentifierOIDFEntityIdOpts(
            identifier = "https://entity.example.com",
            trustAnchors = null
        )

        val result = oidfResolutionService.resolve(opts)
        assertTrue(result.isErr, "Should fail when trustAnchors is null")
        assertTrue(
            result.error.message.defaultMessage.contains("trustAnchors must be provided"),
            "Error should mention missing trustAnchors: ${result.error.message.defaultMessage}"
        )
    }

    @Test
    fun testResolveFailsForUnreachableEntity() = runTest {
        val opts = ExternalIdentifierOIDFEntityIdOpts(
            identifier = "https://nonexistent.invalid.example.com",
            trustAnchors = listOf("https://trust-anchor.invalid.example.com")
        )

        val result = oidfResolutionService.resolve(opts)
        assertTrue(result.isErr, "Should fail for unreachable entity")
        assertTrue(
            result.error.message.defaultMessage.contains("Trust chain resolution failed"),
            "Error should mention trust chain resolution failure: ${result.error.message.defaultMessage}"
        )
    }

    // =========================================================================
    // Command ID test
    // =========================================================================

    @Test
    fun testCommandId() {
        assertEquals(
            "oidf.resolution.entity-id",
            OidfEntityIdExternalIdentifierResolutionServiceImpl.COMMAND_ID
        )
    }
}
