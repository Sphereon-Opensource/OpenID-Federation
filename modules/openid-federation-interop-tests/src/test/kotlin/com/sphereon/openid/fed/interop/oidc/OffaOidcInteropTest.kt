package com.sphereon.openid.fed.interop.oidc

import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.SignedJWT
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.sphereon.openid.fed.interop.harness.OidcInteropEnvironment
import com.sphereon.openid.fed.interop.harness.PeerLock
import com.sphereon.openid.fed.interop.harness.InteropHttpResponse
import com.sphereon.openid.fed.interop.harness.PeerStatementVerifier
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A real OFFA automatic-registration flow through the combined production OIDF+IDK host. */
@Tag("oidc")
class OffaOidcInteropTest {
    @Test
    fun `OFFA omission uses its pinned ES512 default and completes the browser flow`() = runScenario(null)

    @Test
    fun `OFFA RS256 profile completes the browser private key jwt flow`() = runScenario("RS256")

    @Test
    fun `OFFA ES256 profile completes the browser private key jwt flow`() = runScenario("ES256")

    @Test
    fun `OFFA ES384 profile completes the browser private key jwt flow`() = runScenario("ES384")

    @Test
    fun `OFFA ES512 profile completes the browser private key jwt flow`() = runScenario("ES512")

    private fun runScenario(oidcAlgorithm: String?) {
        val startedAt = Instant.now()
        val profileId = oidcAlgorithm?.lowercase() ?: "default"
        val expectedAlgorithm = oidcAlgorithm ?: "ES512"
        val protocolObjects = linkedMapOf<String, Any?>()
        val negativeControls = linkedMapOf<String, Any?>()
        val controls = linkedMapOf<String, Any?>()
        var counterpart: PeerLock? = null
        var environment: OidcInteropEnvironment? = null
        var playwright: Playwright? = null
        var browser: Browser? = null
        var context: BrowserContext? = null
        var stage = "startup"
        var flowChecksPassed = false
        var primaryFailure: Throwable? = null

        try {
            counterpart = PeerLock.load("offa")
            environment = OidcInteropEnvironment.start(oidcAlgorithm)
            val activeEnvironment = requireNotNull(environment)
            stage = "fixture_provisioning"
            publishSphereonRootAndRegisterOffa(activeEnvironment)

            stage = "browser_startup"
            playwright = Playwright.create()
            browser = playwright.chromium().connect(activeEnvironment.browserEndpoint())
            context = browser.newContext(Browser.NewContextOptions().setIgnoreHTTPSErrors(false))
            val page = context.newPage()
            page.setDefaultTimeout(60_000.0)

            // Mutate only the existing unsigned outer redirect_uri. The helper requires the
            // expected registered callback and preserves the signed `request` bytes exactly.
            val redirectInjectionObserved = AtomicBoolean(false)
            context.route("https://sphereon.test/authorize?**") { route ->
                val requestUrl = route.request().url()
                val attackedUrl = OuterRedirectParameterControl.replace(
                    requestUrl,
                    expectedOriginal = "https://offa.test/redirect",
                    replacement = UNTRUSTED_REDIRECT,
                )
                route.resume(Route.ResumeOptions().setUrl(attackedUrl))
                redirectInjectionObserved.set(true)
            }

            stage = "browser_tls_negative"
            // The browser image trusts only the interop CA. A separate valid certificate from
            // an untrusted CA must still fail in Chromium; this catches accidental TLS ignores.
            try {
                page.navigate("https://wrong-ca.test/")
                fail("Chromium accepted a TLS certificate issued by an untrusted CA")
            } catch (failure: PlaywrightException) {
                assertTrue(
                    failure.message?.contains("ERR_CERT_AUTHORITY_INVALID") == true,
                    "expected Chromium's untrusted-CA error, got a different navigation failure",
                )
            }
            controls["browserRejectedUntrustedCa"] = true

            stage = "browser_authorization"
            val issuer = encode(SPHEREON)
            val target = encode("https://offa.test/")
            page.navigate("https://offa.test/login?iss=$issuer&target_link_uri=$target")
            page.locator("#username").waitFor()
            val opLoginUrl = URI(page.url())
            assertEquals("https", opLoginUrl.scheme)
            assertEquals("sphereon.test", opLoginUrl.host)
            assertTrue(opLoginUrl.path.isNotBlank(), "IDK login page must have a real route")
            page.locator("#username").fill("alice")
            page.locator("#password").fill(OP_FIXTURE_PASSWORD)
            page.locator("form.form-sections button[type='submit']").click()

            page.waitForFunction(
                "() => location.protocol === 'https:' && location.host === 'offa.test' && location.pathname === '/'",
            )
            assertTrue(page.url().startsWith("https://offa.test/"))
            val authenticatedPage = page.locator("body").innerText()
            assertTrue(Regex("(?i)OIDC-sub").containsMatchIn(authenticatedPage), "OFFA must render the authenticated OIDC subject header")
            assertTrue(
                authenticatedPage.contains(ALICE_SUBJECT),
                "OFFA must show the configured Alice subject on the authenticated user page",
            )
            assertTrue(redirectInjectionObserved.get(), "the normal browser authorization request must receive the unsigned redirect injection")
            controls["browserAuthenticationCompleted"] = true
            controls["unsignedOuterRedirectWasInjected"] = true

            stage = "protocol_object_verification"
            val discovery = activeEnvironment.request("GET", "$SPHEREON/.well-known/openid-configuration")
            verifyActualProtocolObjects(activeEnvironment, discovery, expectedAlgorithm, protocolObjects)

            stage = "tampered_request_object_negative"
            negativeControls["tamperedRequestObject"] = rejectTamperedObservedRequestObject(activeEnvironment)

            stage = "client_assertion_replay_negative"
            negativeControls["replayedClientAssertion"] = rejectReplayedObservedClientAssertion(activeEnvironment, discovery)
            flowChecksPassed = true
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailures = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { cleanupFailures += failure }
        }
        cleanup { context?.close() }
        cleanup { browser?.close() }
        cleanup { playwright?.close() }
        cleanup { environment?.close() }

        val cleanupSucceeded = cleanupFailures.isEmpty()
        val outcome = if (primaryFailure == null && cleanupSucceeded && flowChecksPassed) "passed" else "failed"
        val failureStage = when {
            primaryFailure != null -> stage
            !cleanupSucceeded -> "cleanup"
            !flowChecksPassed -> "incomplete"
            else -> "none"
        }
        val evidenceFailure = runCatching {
            writeEvidence(
                profileId = profileId,
                configuredAlgorithm = oidcAlgorithm,
                expectedAlgorithm = expectedAlgorithm,
                startedAt = startedAt,
                counterpart = counterpart,
                outcome = outcome,
                failureStage = failureStage,
                cleanupSucceeded = cleanupSucceeded,
                flowChecksPassed = flowChecksPassed,
                protocolObjects = protocolObjects,
                negativeControls = negativeControls,
                controls = controls,
            )
        }.exceptionOrNull()

        primaryFailure?.let { failure ->
            cleanupFailures.forEach(failure::addSuppressed)
            evidenceFailure?.let(failure::addSuppressed)
            throw failure
        }
        if (cleanupFailures.isNotEmpty()) {
            val failure = IllegalStateException("OIDC runtime cleanup failed")
            cleanupFailures.forEach(failure::addSuppressed)
            evidenceFailure?.let(failure::addSuppressed)
            throw failure
        }
        if (evidenceFailure != null) throw IllegalStateException("OIDC evidence write failed", evidenceFailure)
        check(flowChecksPassed) { "OIDC flow did not complete" }
    }

    private fun publishSphereonRootAndRegisterOffa(environment: OidcInteropEnvironment) {
        val createdKey = environment.admin("POST", "/keys")
        assertEquals(201, createdKey.status, "real admin POST /keys failed")
        val keyId = JSONObjectUtils.getString(JSONObjectUtils.parse(createdKey.body), "id")
        val selection = environment.admin("GET", "/keys/signing-selection")
        assertEquals(200, selection.status, "real admin signing-selection read failed")
        val revision = (JSONObjectUtils.parse(selection.body)["revision"] as Number).toLong()
        val selected = environment.admin(
            "PUT", "/keys/signing-selection",
            """{"expectedRevision":$revision,"selectedKeyId":"$keyId"}""",
        )
        assertEquals(200, selected.status, "real admin signing-key CAS failed")

        val rootPublication = environment.admin("POST", "/entity-statement")
        assertEquals(201, rootPublication.status, "real admin root Entity Configuration publication failed")
        val root = environment.request("GET", "$SPHEREON/.well-known/openid-federation")
        assertEquals(200, root.status)
        PeerStatementVerifier.verify(root.body, SPHEREON)
        val rootMetadata = JSONObjectUtils.getJSONObject(
            SignedJWT.parse(root.body).payload.toJSONObject(), "metadata",
        )
        assertTrue(rootMetadata.containsKey("openid_provider"), "same-entity EC must advertise the real OIDC OP metadata")
        val discovery = environment.request("GET", "$SPHEREON/.well-known/openid-configuration")
        assertEquals(200, discovery.status, "real IDK OIDC discovery route must share the Federation Entity Identifier")
        val discoveryObject = JSONObjectUtils.parse(discovery.body)
        assertEquals(SPHEREON, JSONObjectUtils.getString(discoveryObject, "issuer"))

        val offaEc = environment.request("GET", "$OFFA/.well-known/openid-federation")
        assertEquals(200, offaEc.status, "pinned OFFA Entity Configuration must be reachable over verified TLS")
        PeerStatementVerifier.verify(offaEc.body, OFFA)
        val offaJwks = JSONObjectUtils.getJSONObject(SignedJWT.parse(offaEc.body).payload.toJSONObject(), "jwks")
        val createdSubordinate = environment.admin(
            "POST", "/subordinates", JSONObjectUtils.toJSONString(mapOf("identifier" to OFFA)),
        )
        assertEquals(201, createdSubordinate.status)
        val subordinateId = JSONObjectUtils.getString(JSONObjectUtils.parse(createdSubordinate.body), "id")
        val keys = offaJwks["keys"] as? List<*> ?: error("OFFA EC has no JWKS keys")
        keys.forEach { key ->
            val publicJwk = key as? Map<*, *> ?: error("OFFA EC contains an invalid JWK")
            assertTrue(publicJwk.keys.none { it?.toString() in setOf("d", "p", "q", "dp", "dq", "qi", "k", "oth") })
            val body = JSONObjectUtils.toJSONString(publicJwk.entries.associate { it.key.toString() to it.value })
            assertEquals(201, environment.admin("POST", "/subordinates/$subordinateId/jwks", body).status)
        }
        assertEquals(201, environment.admin("POST", "/subordinates/$subordinateId/statement").status)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

    /** Independently verifies the real objects captured passively during the successful browser flow. */
    private fun verifyActualProtocolObjects(
        environment: OidcInteropEnvironment,
        discovery: InteropHttpResponse,
        expectedAlgorithm: String,
        verifiedObjects: MutableMap<String, Any?>,
    ) {
        assertEquals(200, discovery.status, "real OP discovery must remain available for independent verification")
        val discoveryJson = JSONObjectUtils.parse(discovery.body)
        assertTrue(
            (discoveryJson["id_token_signing_alg_values_supported"] as? List<*>)?.contains(expectedAlgorithm) == true,
            "the actual OP discovery metadata must advertise the ID Token algorithm used by the real token",
        )
        val tokenEndpoint = JSONObjectUtils.getString(discoveryJson, "token_endpoint")
        val jwksUri = JSONObjectUtils.getString(discoveryJson, "jwks_uri")
        val tokenUri = URI(tokenEndpoint)
        val jwksLocation = URI(jwksUri)
        assertEquals("https", tokenUri.scheme)
        assertEquals("sphereon.test", tokenUri.host)
        assertEquals("https", jwksLocation.scheme)
        assertEquals("sphereon.test", jwksLocation.host)

        val opJwksResponse = environment.request("GET", jwksUri)
        assertEquals(200, opJwksResponse.status, "real OP JWKS must be available over verified HTTPS")
        val opJwks = JWKSet.parse(opJwksResponse.body)
        assertPublicJwks(opJwks)
        val rpRegistration = JSONObjectUtils.parse(environment.protocolEvidence("rp-registration.json"))
        assertEquals(OFFA, JSONObjectUtils.getString(rpRegistration, "clientId"))
        assertEquals(expectedAlgorithm, rpRegistration["idTokenSignedResponseAlg"])
        assertTrue(
            ((rpRegistration["validUntilEpochSeconds"] as? Number)?.toLong() ?: 0L) > Instant.now().epochSecond,
            "the actual resolved RP registration must still be valid",
        )
        val rpJwks = JWKSet.parse(JSONObjectUtils.toJSONString(mapOf("keys" to rpRegistration["keys"])))
        assertPublicJwks(rpJwks)

        val jarCompact = environment.protocolEvidence("request-object.jwt")
        val jar = verifyObservedJwt(
            compact = jarCompact,
            keys = rpJwks.keys,
            issuer = OFFA,
            audience = SPHEREON,
            expectedAlgorithm = expectedAlgorithm,
        )
        val nonce = jar.getStringClaim("nonce")?.takeIf(String::isNotBlank)
            ?: fail("the real signed Request Object must contain a nonce")
        assertEquals("https://offa.test/redirect", jar.getStringClaim("redirect_uri"), "the signed callback must be OFFA's registered redirect URI")
        verifiedObjects["requestObject"] = verifiedObjectEvidence(
            jarCompact, noncePresent = true, registeredRedirectVerified = true,
        )

        val assertionCompact = environment.protocolEvidence("client-assertion.jwt")
        val assertion = verifyObservedJwt(
            compact = assertionCompact,
            keys = rpJwks.keys,
            issuer = OFFA,
            audience = tokenEndpoint,
            expectedAlgorithm = expectedAlgorithm,
            requireExactAudience = true,
        )
        assertEquals(OFFA, assertion.subject, "private_key_jwt sub must identify the real resolved RP")
        verifiedObjects["clientAssertion"] = verifiedObjectEvidence(
            assertionCompact, exactAudience = true, clientSubjectVerified = true,
        )

        val idTokenCompact = environment.protocolEvidence("id-token.jwt")
        val idToken = verifyObservedJwt(
            compact = idTokenCompact,
            keys = opJwks.keys,
            issuer = SPHEREON,
            audience = OFFA,
            expectedAlgorithm = expectedAlgorithm,
        )
        assertEquals(nonce, idToken.getStringClaim("nonce"), "the real ID Token nonce must match the signed authorization request")
        verifiedObjects["idToken"] = verifiedObjectEvidence(idTokenCompact, nonceMatchedToRequestObject = true)
    }

    private fun rejectTamperedObservedRequestObject(environment: OidcInteropEnvironment): Map<String, Any?> {
        val compact = environment.protocolEvidence("request-object.jwt")
        val parts = compact.split('.')
        require(parts.size == 3)
        val signature = Base64.getUrlDecoder().decode(parts[2])
        require(signature.isNotEmpty())
        signature[0] = (signature[0].toInt() xor 1).toByte()
        val corrupted = "${parts[0]}.${parts[1]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
        val response = environment.request(
            "GET",
            "$SPHEREON/authorize?client_id=${encode(OFFA)}&request=${encode(corrupted)}",
        )
        return assertEndpointError(response, "invalid_request_object", "the real authorize endpoint must reject a signature-corrupted captured JAR")
    }

    private fun rejectReplayedObservedClientAssertion(
        environment: OidcInteropEnvironment,
        discovery: InteropHttpResponse,
    ): Map<String, Any?> {
        val tokenEndpoint = JSONObjectUtils.getString(JSONObjectUtils.parse(discovery.body), "token_endpoint")
        val assertion = environment.protocolEvidence("client-assertion.jwt")
        val response = environment.tokenForm(
            tokenEndpoint,
            listOf(
                "grant_type" to "authorization_code",
                "client_id" to OFFA,
                "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion" to assertion,
            ),
        )
        return assertEndpointError(response, "invalid_client", "the real token endpoint must reject the already accepted client-assertion jti")
    }

    private fun assertEndpointError(response: InteropHttpResponse, expected: String, message: String): Map<String, Any?> {
        assertTrue(response.status in 400..499, "$message (HTTP ${response.status})")
        val parsed = try {
            JSONObjectUtils.parse(response.body)
        } catch (_: Exception) {
            fail("endpoint rejection must be a structured OAuth error")
        }
        val errorCode = JSONObjectUtils.getString(parsed, "error")
        assertEquals(expected, errorCode, message)
        require(errorCode in setOf("invalid_request_object", "invalid_client"))
        return linkedMapOf("result" to "rejected", "httpStatus" to response.status, "oauthError" to errorCode)
    }

    private fun verifyObservedJwt(
        compact: String,
        keys: List<JWK>,
        issuer: String,
        audience: String,
        expectedAlgorithm: String,
        requireExactAudience: Boolean = false,
    ): com.nimbusds.jwt.JWTClaimsSet {
        val jwt = SignedJWT.parse(compact)
        val header = jwt.header
        assertEquals(expectedAlgorithm, header.algorithm.name, "the protocol object's actual signing algorithm must match the profile")
        val kid = header.keyID?.takeIf { it.isNotBlank() } ?: fail("the real signed protocol object must have a kid")
        val key = keys.singleOrNull { it.keyID == kid } ?: fail("the actual kid must select exactly one advertised verification key")
        val verifier: JWSVerifier = when (expectedAlgorithm) {
            "RS256" -> if (key is RSAKey) RSASSAVerifier(key.toPublicJWK() as RSAKey)
                else fail("the actual public key type must match RS256")
            "ES256" -> if (key is ECKey && key.curve.name == "P-256") ECDSAVerifier(key.toPublicJWK() as ECKey)
                else fail("the actual public key curve must match ES256")
            "ES384" -> if (key is ECKey && key.curve.name == "P-384") ECDSAVerifier(key.toPublicJWK() as ECKey)
                else fail("the actual public key curve must match ES384")
            "ES512" -> if (key is ECKey && key.curve.name == "P-521") ECDSAVerifier(key.toPublicJWK() as ECKey)
                else fail("the actual public key curve must match ES512")
            else -> fail("the actual protocol profile is outside the supported algorithm matrix")
        }
        assertTrue(jwt.verify(verifier), "the real protocol object's signature must verify with the independently selected public key")
        val claims = jwt.jwtClaimsSet
        assertEquals(issuer, claims.issuer, "the real protocol object's issuer must match the expected peer")
        if (requireExactAudience) {
            assertEquals(listOf(audience), claims.audience, "private_key_jwt aud must be exactly the discovered token endpoint")
        } else {
            assertTrue(audience in claims.audience, "the real protocol object's audience must match the expected endpoint or client")
        }
        val exp = claims.expirationTime?.toInstant()?.epochSecond ?: fail("the real protocol object must contain exp")
        assertTrue(exp > Instant.now().epochSecond, "the real protocol object's exp must still be in the future")
        return claims
    }

    private fun verifiedObjectEvidence(
        compact: String,
        noncePresent: Boolean? = null,
        nonceMatchedToRequestObject: Boolean? = null,
        registeredRedirectVerified: Boolean? = null,
        exactAudience: Boolean? = null,
        clientSubjectVerified: Boolean? = null,
    ): Map<String, Any?> {
        val header = SignedJWT.parse(compact).header
        val kid = header.keyID?.takeIf { it.isNotBlank() } ?: error("verified object omitted its key id")
        return linkedMapOf(
            "algorithm" to header.algorithm.name,
            "kid" to kid,
            "signatureVerified" to true,
            "issuerVerified" to true,
            "audienceVerified" to true,
            "exactAudienceVerified" to exactAudience,
            "expiryVerified" to true,
            "noncePresent" to noncePresent,
            "nonceMatchedToRequestObject" to nonceMatchedToRequestObject,
            "registeredRedirectVerified" to registeredRedirectVerified,
            "clientSubjectVerified" to clientSubjectVerified,
        ).filterValues { it != null }
    }

    private fun writeEvidence(
        profileId: String,
        configuredAlgorithm: String?,
        expectedAlgorithm: String,
        startedAt: Instant,
        counterpart: PeerLock?,
        outcome: String,
        failureStage: String,
        cleanupSucceeded: Boolean,
        flowChecksPassed: Boolean,
        protocolObjects: Map<String, Any?>,
        negativeControls: Map<String, Any?>,
        controls: Map<String, Any?>,
    ) {
        require(profileId in setOf("default", "rs256", "es256", "es384", "es512"))
        require(outcome in setOf("passed", "failed"))
        require(failureStage in setOf(
            "startup", "fixture_provisioning", "browser_startup", "browser_tls_negative",
            "browser_authorization", "protocol_object_verification", "tampered_request_object_negative",
            "client_assertion_replay_negative", "cleanup", "incomplete", "none",
        ))
        val output = Path.of(System.getProperty("interop.evidence.directory")
            ?: error("OIDC evidence output is not configured")).toAbsolutePath().normalize()
        require(output.fileName.toString() == "oidc" && output.parent?.fileName?.toString() == "interop")
        val parent = output.parent
        require(parent != null)
        require(!Files.isSymbolicLink(parent))
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(parent))
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(output) && Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))
        } else {
            Files.createDirectory(output)
        }
        val fields = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "scenario" to "offa-oidc-$profileId",
            "outcome" to outcome,
            "failureStage" to failureStage,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
            "profileMode" to if (configuredAlgorithm == null) "omitted_default" else "explicit",
            "configuredAlgorithm" to configuredAlgorithm,
            "expectedAlgorithm" to expectedAlgorithm,
            "counterpart" to counterpart?.let {
                mapOf(
                    "name" to it.name,
                    "sourceCommit" to it.commit,
                    "imageDigest" to it.image.substringAfter("@sha256:"),
                    "license" to it.license,
                )
            },
            "flowChecksPassedBeforeCleanup" to flowChecksPassed,
            "cleanupSucceeded" to cleanupSucceeded,
            "verifiedObjects" to protocolObjects,
            "negativeControls" to negativeControls,
            "controls" to controls,
        ).filterValues { it != null }
        Files.writeString(
            output.resolve("offa-$profileId.json"),
            JSONObjectUtils.toJSONString(fields),
            UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }

    private fun assertPublicJwks(jwks: JWKSet) {
        assertTrue(jwks.keys.isNotEmpty(), "the actual protocol JWKS must contain a verification key")
        val privateFields = setOf("d", "p", "q", "dp", "dq", "qi", "k", "oth")
        assertTrue(
            jwks.keys.all { key -> key.toJSONObject().keys.none(privateFields::contains) },
            "observed protocol JWKS data must contain public keys only",
        )
    }

    private companion object {
        const val SPHEREON = "https://sphereon.test"
        const val OFFA = "https://offa.test"
        // Exact fixture credential configured by OidfOpBootstrap; never recorded in evidence.
        const val OP_FIXTURE_PASSWORD = "Sphereon-OIDF-2026!"
        const val ALICE_SUBJECT = "urn:sphereon:oidf:op:alice"
        const val UNTRUSTED_REDIRECT = "https://attacker.test/callback"
    }
}
