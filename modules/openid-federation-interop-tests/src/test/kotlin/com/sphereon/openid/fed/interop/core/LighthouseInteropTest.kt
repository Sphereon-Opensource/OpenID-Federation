package com.sphereon.openid.fed.interop.core

import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.SignedJWT
import com.sphereon.openid.fed.interop.harness.InteropEnvironment
import com.sphereon.openid.fed.interop.harness.InteropHttpResponse
import com.sphereon.openid.fed.interop.harness.LighthousePeer
import com.sphereon.openid.fed.interop.harness.PeerLock
import com.sphereon.openid.fed.interop.harness.PeerStatementVerifier
import com.sphereon.openid.fed.interop.harness.ResearchEducationProfileVerifier
import com.sphereon.openid.fed.interop.harness.TrustedJwtVerifier
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SPHEREON = InteropEnvironment.PUBLIC_ENTITY_ID
private const val LIGHTHOUSE = LighthousePeer.ENTITY_ID
private const val ENTITY_CONFIGURATION = "/.well-known/openid-federation"

/** Real product-to-peer trust-chain acceptance. No in-process federation fixture or synthetic JWT is used. */
@Tag("core")
class LighthouseInteropTest {
    private val signedObjectEvidence = SignedObjectEvidenceCollector()
    internal var signedObjectEvidencePhase: String = "initial"

    internal fun signedObjectEvidenceRecords(): List<Map<String, String>> = signedObjectEvidence.records()

    @Test
    fun `Sphereon resolver validates a LightHouse trust anchor and its signed subordinate statement across restart`() {
        signedObjectEvidencePhase = "initial"
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var identity: EntityIdentity? = null
        var postRestartIdentity: EntityIdentity? = null
        var verifiedResolve = false
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        try {
            environment = InteropEnvironment.start()
            lighthouse = LighthousePeer.start(environment)
            ensureFetchEndpoint(lighthouse)
            val root = publishSphereonEntityConfiguration(environment, authorityHint = LIGHTHOUSE)
            assertSphereonLeafMetadata(root)
            identity = root
            environment.verifyDurableState("after-initial-publication", root.jwks)
            val lighthouseEc = verifyPeerConfiguration(lighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body, LIGHTHOUSE)

            val lighthouseRelationship = lighthouse.addSubordinate(SPHEREON, root.jwks)
            assertEquals(201, lighthouseRelationship.status)
            val subordinate = lighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, subordinate.status)
            val subordinateClaims = TrustedJwtVerifier.verify(
                subordinate.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, lighthouseEc.jwks,
            )
            signedObjectEvidence.recordVerified(subordinate.body, "subordinate_statement", signedObjectEvidencePhase)
            assertTrue(subordinateClaims.containsKey("jwks"))

            val resolved = environment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            if (resolved.status != 200) {
                val diagnosticResult = runCatching {
                    environment.retainSafeHttpFailure(
                        "sphereon-under-lighthouse-resolve",
                        "GET",
                        "/resolve",
                        resolved,
                    )
                }
                try {
                    assertEquals(
                        200,
                        resolved.status,
                        "Sphereon GET /resolve returned HTTP ${resolved.status}: ${safeHttpError(resolved.body)}; " +
                            "redacted private diagnostics: ${diagnosticResult.getOrNull() ?: "unavailable"}",
                    )
                } catch (assertionFailure: AssertionError) {
                    diagnosticResult.exceptionOrNull()?.let(assertionFailure::addSuppressed)
                    throw assertionFailure
                }
            }
            assertEquals(200, resolved.status, "Sphereon GET /resolve returned HTTP ${resolved.status}: ${safeHttpError(resolved.body)}")
            verifyResolveResponse(
                resolved.body, root.jwks, SPHEREON, SPHEREON,
                mapOf(SPHEREON to root.jwks, LIGHTHOUSE to lighthouseEc.jwks), LIGHTHOUSE to SPHEREON, LIGHTHOUSE,
                expectedMetadata = root.metadata,
            )
            verifiedResolve = true

            // The same public/admin JVM containers and the same Postgres + PKCS12 mounts are restarted.
            // A new publication after restart proves the selected private key remains usable.
            environment.restartSphereon()
            assertEquals(201, environment.admin("POST", "/entity-statement").status)
            val freshEntityConfiguration = environment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
            assertEquals(200, freshEntityConfiguration.status)
            signedObjectEvidencePhase = "post_restart"
            postRestartIdentity = verifySphereonConfiguration(
                freshEntityConfiguration.body, expectedAuthorityHints = listOf(LIGHTHOUSE),
            )
            assertSphereonLeafMetadata(postRestartIdentity)
            environment.verifyDurableState("after-restart-publication", postRestartIdentity.jwks)
            assertEquals(root.kid, postRestartIdentity.kid)
            assertEquals(root.canonicalPublicJwks, postRestartIdentity.canonicalPublicJwks)
            assertTrue(SignedJWT.parse(freshEntityConfiguration.body).jwtClaimsSet.issueTime.toInstant() >= root.issuedAt)

            val afterRestart = environment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(200, afterRestart.status)
            verifyResolveResponse(
                afterRestart.body, postRestartIdentity.jwks, SPHEREON, SPHEREON,
                mapOf(SPHEREON to postRestartIdentity.jwks, LIGHTHOUSE to lighthouseEc.jwks), LIGHTHOUSE to SPHEREON, LIGHTHOUSE,
                expectedMetadata = postRestartIdentity.metadata,
            )
            val relationshipId = (JSONObjectUtils.parse(lighthouseRelationship.body)["id"] as Number).toLong().toString()
            assertEquals(204, lighthouse.admin("DELETE", "/api/v1/admin/subordinates/$relationshipId").status)
            assertEquals(404, lighthouse.public("/fetch?sub=${url(SPHEREON)}").status)
            // Restarting the actual receiver clears any resolver cache; a fresh resolution must now fail.
            environment.restartSphereon()
            val withdrawn = environment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(400, withdrawn.status)
            assertEquals("invalid_trust_chain", JSONObjectUtils.getString(JSONObjectUtils.parse(withdrawn.body), "error"))
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (primaryFailure != null && environment != null) {
                try {
                    environment.retainSafeServerErrorLogs("sphereon-resolves-under-lighthouse-anchor")
                } catch (diagnosticFailure: Throwable) {
                    primaryFailure!!.addSuppressed(diagnosticFailure)
                }
            }
            val failures = closeOwned(lighthouse, environment)
            if (failures.isNotEmpty()) outcome = "failed"
            try {
                writeEvidence(
                    "sphereon-resolves-under-lighthouse-anchor", peer, startedAt, outcome,
                    identity, postRestartIdentity, verifiedResolve, environment,
                )
            } catch (evidenceFailure: Throwable) {
                failures += evidenceFailure
            }
            if (primaryFailure != null) failures.forEach(primaryFailure!!::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun `LightHouse resolver validates a Sphereon trust anchor and its signed subordinate statement across restart`() {
        signedObjectEvidencePhase = "initial"
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var identity: EntityIdentity? = null
        var postRestartIdentity: EntityIdentity? = null
        var verifiedResolve = false
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        try {
            environment = InteropEnvironment.start()
            lighthouse = LighthousePeer.start(environment)
            ensureFetchEndpoint(lighthouse)
            val sphereon = publishSphereonEntityConfiguration(environment)
            identity = sphereon
            environment.verifyDurableState("after-initial-publication", sphereon.jwks)
            assertSafeFetchError(
                environment.request("GET", "https://sphereon.test/fetch?sub=${url(SPHEREON)}"),
                expectedStatus = 400,
                expectedError = "invalid_request",
                operation = "Sphereon self-fetch",
            )
            assertSafeFetchError(
                environment.request("GET", "https://sphereon.test/fetch?sub=${url("https://unregistered.example")}"),
                expectedStatus = 404,
                expectedError = "not_found",
                operation = "Sphereon fetch of an unknown subject",
            )
            assertEquals(201, lighthouse.addTrustAnchor(SPHEREON, sphereon.jwks).status)
            assertEquals(201, lighthouse.addAuthorityHint(SPHEREON).status)
            assertEquals(
                201,
                lighthouse.registerEndpoint(
                    "resolve", "/resolve", JSONObjectUtils.toJSONString(mapOf("allowed_trust_anchors" to listOf(SPHEREON))),
                ).status,
            )
            val lighthouseEcJwt = lighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val lighthouseEc = verifyPeerConfiguration(lighthouseEcJwt, LIGHTHOUSE)
            assertEquals(
                "$LIGHTHOUSE/fetch",
                ((lighthouseEc.metadata?.get("federation_entity") as? Map<*, *>)?.get("federation_fetch_endpoint")),
            )

            val subordinateId = createSphereonSubordinate(environment, LIGHTHOUSE, lighthouseEc.jwks)
            assertEquals(201, environment.admin("POST", "/subordinates/$subordinateId/statement").status)
            val subordinate = environment.request("GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}")
            assertEquals(200, subordinate.status)
            TrustedJwtVerifier.verify(subordinate.body, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, sphereon.jwks)
            signedObjectEvidence.recordVerified(subordinate.body, "subordinate_statement", signedObjectEvidencePhase)

            val resolved = lighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(200, resolved.status, "Sphereon GET /resolve returned HTTP ${resolved.status}: ${safeHttpError(resolved.body)}")
            verifyResolveResponse(
                resolved.body, lighthouseEc.jwks, LIGHTHOUSE, LIGHTHOUSE,
                mapOf(LIGHTHOUSE to lighthouseEc.jwks, SPHEREON to sphereon.jwks), SPHEREON to LIGHTHOUSE, SPHEREON,
                expectedMetadata = lighthouseEc.metadata,
            )
            verifiedResolve = true

            environment.restartSphereon()
            assertEquals(201, environment.admin("POST", "/entity-statement").status)
            assertEquals(201, environment.admin("POST", "/subordinates/$subordinateId/statement").status)
            val freshEntityConfiguration = environment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
            assertEquals(200, freshEntityConfiguration.status)
            signedObjectEvidencePhase = "post_restart"
            postRestartIdentity = verifySphereonConfiguration(freshEntityConfiguration.body)
            environment.verifyDurableState("after-restart-publication", postRestartIdentity.jwks)
            assertEquals(sphereon.kid, postRestartIdentity.kid)
            assertEquals(sphereon.canonicalPublicJwks, postRestartIdentity.canonicalPublicJwks)

            val freshSubordinate = environment.request("GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}")
            assertEquals(200, freshSubordinate.status)
            TrustedJwtVerifier.verify(freshSubordinate.body, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, postRestartIdentity.jwks)
            signedObjectEvidence.recordVerified(freshSubordinate.body, "subordinate_statement", signedObjectEvidencePhase)
            val afterRestart = lighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(200, afterRestart.status)
            verifyResolveResponse(
                afterRestart.body, lighthouseEc.jwks, LIGHTHOUSE, LIGHTHOUSE,
                mapOf(LIGHTHOUSE to lighthouseEc.jwks, SPHEREON to postRestartIdentity.jwks), SPHEREON to LIGHTHOUSE, SPHEREON,
                expectedMetadata = lighthouseEc.metadata,
            )
            // The unframed bodyless DELETE is the acceptance request; the explicit-zero-length
            // request below is diagnostic-only and can never turn this operation into a pass.
            val subordinateDeletePath = "/subordinates/$subordinateId"
            val withdrawal = environment.adminDeleteWithWireCapture(subordinateDeletePath)
            if (withdrawal.curlExitCode != 0 || withdrawal.status != 200) {
                val controlResult = runCatching { environment.adminDeleteZeroLengthControl(subordinateDeletePath) }
                val diagnosticResult = runCatching {
                    environment.retainSafeAdminDeleteWireFailure(
                        subordinateDeletePath,
                        withdrawal,
                        controlResult.getOrNull(),
                        zeroLengthControlAttempted = true,
                    )
                }
                try {
                    assertEquals(
                        0,
                        withdrawal.curlExitCode,
                        "unframed Sphereon subordinate DELETE curl failed; " +
                            "zero-length control status=${controlResult.getOrNull()?.status ?: "unavailable"}; " +
                            "private diagnostics=${diagnosticResult.getOrNull() ?: "unavailable"}",
                    )
                    assertEquals(
                        200,
                        withdrawal.status,
                        "unframed Sphereon subordinate DELETE returned HTTP ${withdrawal.status}; " +
                            "zero-length control status=${controlResult.getOrNull()?.status ?: "unavailable"}; " +
                            "private diagnostics=${diagnosticResult.getOrNull() ?: "unavailable"}",
                    )
                } catch (assertionFailure: AssertionError) {
                    controlResult.exceptionOrNull()?.let(assertionFailure::addSuppressed)
                    diagnosticResult.exceptionOrNull()?.let(assertionFailure::addSuppressed)
                    throw assertionFailure
                }
            }
            assertEquals(0, withdrawal.curlExitCode)
            assertEquals(200, withdrawal.status)
            assertSafeFetchError(
                environment.request("GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}"),
                expectedStatus = 404,
                expectedError = "not_found",
                operation = "Sphereon fetch after subordinate withdrawal",
            )
            lighthouse.restart()
            val withdrawnResolution = lighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(404, withdrawnResolution.status)
            assertEquals("invalid_trust_chain", JSONObjectUtils.getString(JSONObjectUtils.parse(withdrawnResolution.body), "error"))
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (primaryFailure != null && environment != null) {
                try {
                    environment.retainSafeServerErrorLogs("lighthouse-resolves-under-sphereon-anchor")
                } catch (diagnosticFailure: Throwable) {
                    primaryFailure!!.addSuppressed(diagnosticFailure)
                }
            }
            val failures = closeOwned(lighthouse, environment)
            if (failures.isNotEmpty()) outcome = "failed"
            try {
                writeEvidence(
                    "lighthouse-resolves-under-sphereon-anchor", peer, startedAt, outcome,
                    identity, postRestartIdentity, verifiedResolve, environment,
                )
            } catch (evidenceFailure: Throwable) {
                failures += evidenceFailure
            }
            if (primaryFailure != null) failures.forEach(primaryFailure!!::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun `Sphereon applies LightHouse signed RP metadata policy and rejects a disallowed value`() {
        signedObjectEvidencePhase = "initial"
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var identity: EntityIdentity? = null
        var postRestartIdentity: EntityIdentity? = null
        var verifiedResolve = false
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        try {
            environment = InteropEnvironment.start()
            lighthouse = LighthousePeer.start(environment)
            ensureFetchEndpoint(lighthouse)

            val relyingPartyMetadata = linkedMapOf<String, Any?>(
                "client_name" to "Sphereon interop RP",
                "contacts" to listOf("rp-ops@interop.example.org"),
            )
            val provisioned = environment.admin(
                "POST",
                "/metadata",
                JSONObjectUtils.toJSONString(
                    mapOf("key" to "openid_relying_party", "metadata" to relyingPartyMetadata),
                ),
            )
            assertEquals(201, provisioned.status)

            // Resolution under the LightHouse anchor starts from Sphereon's authority_hints.
            val sphereon = publishSphereonEntityConfiguration(environment, authorityHint = LIGHTHOUSE)
            identity = sphereon
            environment.verifyDurableState("after-initial-publication", sphereon.jwks)
            val leafRpMetadata = sphereon.metadata?.get("openid_relying_party") as? Map<*, *>
                ?: error("Sphereon Entity Configuration omitted provisioned OpenID RP metadata")
            assertEquals("Sphereon interop RP", leafRpMetadata["client_name"])
            assertEquals(listOf("rp-ops@interop.example.org"), leafRpMetadata["contacts"])

            val lighthouseEc = verifyPeerConfiguration(
                lighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body,
                LIGHTHOUSE,
            )
            val relationship = lighthouse.addSubordinate(SPHEREON, sphereon.jwks)
            assertEquals(201, relationship.status)
            val relationshipId = (JSONObjectUtils.parse(relationship.body)["id"] as Number).toLong().toString()

            val addContactPolicy = mapOf(
                "openid_relying_party" to mapOf(
                    "contacts" to mapOf("add" to listOf("authority-ops@interop.example.org")),
                ),
            )
            assertEquals(
                200,
                lighthouse.admin(
                    "PUT",
                    "/api/v1/admin/subordinates/$relationshipId/metadata-policies",
                    addContactPolicy,
                ).status,
            )

            val signedPolicyStatement = lighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, signedPolicyStatement.status)
            val signedPolicyClaims = TrustedJwtVerifier.verify(
                signedPolicyStatement.body,
                ENTITY_STATEMENT_TYPE,
                LIGHTHOUSE,
                SPHEREON,
                lighthouseEc.jwks,
            )
            signedObjectEvidence.recordVerified(
                signedPolicyStatement.body, "subordinate_statement", signedObjectEvidencePhase,
            )
            val signedMetadataPolicy = signedPolicyClaims["metadata_policy"] as? Map<*, *>
                ?: error("LightHouse subordinate statement omitted metadata_policy")
            val signedRpPolicy = signedMetadataPolicy["openid_relying_party"] as? Map<*, *>
                ?: error("LightHouse subordinate statement omitted RP metadata policy")
            val signedContactPolicy = signedRpPolicy["contacts"] as? Map<*, *>
                ?: error("LightHouse subordinate RP policy omitted contacts")
            assertEquals(listOf("authority-ops@interop.example.org"), signedContactPolicy["add"])

            // Restart the real receiver after policy mutation so resolution cannot reuse a cached chain.
            environment.restartSphereon()
            signedObjectEvidencePhase = "post_positive_policy_restart"
            assertEquals(201, environment.admin("POST", "/entity-statement").status)
            val freshEntityConfiguration = environment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
            assertEquals(200, freshEntityConfiguration.status)
            postRestartIdentity = verifyPeerConfiguration(freshEntityConfiguration.body, SPHEREON)
            environment.verifyDurableState("after-restart-publication", postRestartIdentity.jwks)
            assertEquals(sphereon.kid, postRestartIdentity.kid)
            assertEquals(sphereon.canonicalPublicJwks, postRestartIdentity.canonicalPublicJwks)

            val resolved = environment.request(
                "GET",
                "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(200, resolved.status, "Sphereon GET /resolve returned HTTP ${resolved.status}: ${safeHttpError(resolved.body)}")
            val expectedMetadata = postRestartIdentity.metadata?.toMutableMap()
                ?: error("Sphereon Entity Configuration omitted metadata")
            val expectedRpMetadata = leafRpMetadata.entries.associate { it.key.toString() to it.value }.toMutableMap()
            expectedRpMetadata["contacts"] = listOf("rp-ops@interop.example.org", "authority-ops@interop.example.org")
            expectedMetadata["openid_relying_party"] = expectedRpMetadata
            verifyResolveResponse(
                resolved.body,
                postRestartIdentity.jwks,
                SPHEREON,
                SPHEREON,
                mapOf(SPHEREON to postRestartIdentity.jwks, LIGHTHOUSE to lighthouseEc.jwks),
                LIGHTHOUSE to SPHEREON,
                LIGHTHOUSE,
                expectedMetadata = expectedMetadata,
            )
            val resolvedClaims = TrustedJwtVerifier.verify(
                resolved.body, "resolve-response+jwt", SPHEREON, SPHEREON, postRestartIdentity.jwks,
            )
            val resolvedChain = resolvedClaims["trust_chain"] as? List<*>
                ?: error("resolve response omitted trust_chain")
            val resolvedPolicyStatement = resolvedChain.map { token -> SignedJWT.parse(token as String) }
                .single { it.jwtClaimsSet.issuer == LIGHTHOUSE && it.jwtClaimsSet.subject == SPHEREON }
            val resolvedPolicyCompact = resolvedPolicyStatement.serialize()
            val resolvedPolicyClaims = TrustedJwtVerifier.verify(
                resolvedPolicyCompact, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, lighthouseEc.jwks,
            )
            signedObjectEvidence.recordVerified(
                resolvedPolicyCompact, "subordinate_statement", "${signedObjectEvidencePhase}_policy_assertion",
            )
            assertEquals(addContactPolicy, resolvedPolicyClaims["metadata_policy"])
            verifiedResolve = true

            val disallowedPolicy = mapOf(
                "openid_relying_party" to mapOf(
                    "client_name" to mapOf("one_of" to listOf("Different, disallowed RP")),
                ),
            )
            assertEquals(
                200,
                lighthouse.admin(
                    "PUT",
                    "/api/v1/admin/subordinates/$relationshipId/metadata-policies",
                    disallowedPolicy,
                ).status,
            )
            signedObjectEvidencePhase = "disallowed_policy"
            val signedDisallowedStatement = lighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, signedDisallowedStatement.status)
            val signedDisallowedClaims = TrustedJwtVerifier.verify(
                signedDisallowedStatement.body,
                ENTITY_STATEMENT_TYPE,
                LIGHTHOUSE,
                SPHEREON,
                lighthouseEc.jwks,
            )
            signedObjectEvidence.recordVerified(
                signedDisallowedStatement.body, "subordinate_statement", signedObjectEvidencePhase,
            )
            val signedDisallowedRp =
                (signedDisallowedClaims["metadata_policy"] as? Map<*, *>)?.get("openid_relying_party") as? Map<*, *>
                    ?: error("LightHouse subordinate statement omitted the updated disallow policy")
            val signedClientNamePolicy = signedDisallowedRp["client_name"] as? Map<*, *>
                ?: error("LightHouse subordinate disallow policy omitted client_name")
            assertEquals(listOf("Different, disallowed RP"), signedClientNamePolicy["one_of"])

            environment.restartSphereon()
            signedObjectEvidencePhase = "post_disallowed_policy_restart"
            val rejected = environment.request(
                "GET",
                "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(400, rejected.status)
            assertEquals("invalid_metadata", JSONObjectUtils.getString(JSONObjectUtils.parse(rejected.body), "error"))
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val failures = closeOwned(lighthouse, environment)
            if (failures.isNotEmpty()) outcome = "failed"
            try {
                writeEvidence(
                    "lighthouse-rp-metadata-policy", peer, startedAt, outcome,
                    identity, postRestartIdentity, verifiedResolve, environment,
                )
            } catch (evidenceFailure: Throwable) {
                failures += evidenceFailure
            }
            if (primaryFailure != null) failures.forEach(primaryFailure!!::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }


    @Test
    fun `Sphereon account leaf resolves through LightHouse with real max path constraints`() {
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var outcome = "failed"
        var rootIdentity: EntityIdentity? = null
        var postRestartRoot: EntityIdentity? = null
        var threeEntityPassed = false
        var fourEntityPassedAtOne = false
        var leafPassedAtZero = false
        var descendantRejectedAtZero = false
        var signedLighthouseLeafAtOne: String? = null
        var signedLighthouseLeafAtZero: String? = null
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            signedObjectEvidencePhase = "topology_initial"
            ensureFetchEndpoint(activeLighthouse)
            assertEquals(201, activeLighthouse.addAuthorityHint(SPHEREON).status)

            // Root is the existing root account/entity; the subordinate entities are real
            // ACCOUNT-mode rows with explicit URL identifiers and the public per-account path.
            val root = publishSphereonEntityConfiguration(activeEnvironment)
            rootIdentity = root
            activeEnvironment.verifyDurableState("after-initial-publication", root.jwks)
            val lighthouseEcJwt = activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val lighthouseEc = verifyPeerConfiguration(lighthouseEcJwt, LIGHTHOUSE)

            val suffix = java.lang.Long.toUnsignedString(System.nanoTime(), 36).takeLast(10)
            val leafUsername = "leaf$suffix"
            val descendantUsername = "child$suffix"
            val leafId = "$SPHEREON/$leafUsername"
            val descendantId = "$SPHEREON/$descendantUsername"
            val leaf = publishSphereonTenantAccount(activeEnvironment, leafUsername, leafId, LIGHTHOUSE)
            val descendant = publishSphereonTenantAccount(activeEnvironment, descendantUsername, descendantId, leafId)

            // Sphereon root signs the root -> LightHouse intermediate relationship.
            val rootToLighthouseId = createSphereonSubordinate(activeEnvironment, LIGHTHOUSE, lighthouseEc.jwks)
            assertEquals(201, activeEnvironment.admin(
                "POST", "/subordinates/$rootToLighthouseId/statement", account = "root",
            ).status)
            val rootStatement = activeEnvironment.request("GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}")
            assertEquals(200, rootStatement.status)
            TrustedJwtVerifier.verify(rootStatement.body, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, root.jwks)
            signedObjectEvidence.recordVerified(rootStatement.body, "subordinate_statement", "sphereon_root_to_lighthouse")

            // LightHouse signs the intermediate -> leaf statement with the real Admin API
            // constraint for this subordinate. A separate Sphereon account is below the leaf.
            val lighthouseLeafRelation = activeLighthouse.addSubordinate(leafId, leaf.jwks)
            assertEquals(201, lighthouseLeafRelation.status)
            val lighthouseLeafRelationId = (JSONObjectUtils.parse(lighthouseLeafRelation.body)["id"] as Number).toLong().toString()
            setLightHouseMaxPathLength(activeLighthouse, lighthouseLeafRelationId, 1)
            val lighthouseLeafAtOne = fetchVerifiedLightHouseSubordinate(
                activeLighthouse, leafId, lighthouseEc.jwks, expectedMaxPathLength = 1,
            )
            signedLighthouseLeafAtOne = lighthouseLeafAtOne

            // Leaf signs leaf -> descendant through its own account-scoped admin context.
            val leafToDescendantId = createSphereonSubordinateUnderAccount(
                activeEnvironment, leafUsername, descendantId, descendant.jwks,
            )
            assertEquals(201, activeEnvironment.admin(
                "POST", "/subordinates/$leafToDescendantId/statement", account = leafUsername,
            ).status)
            val leafStatement = activeEnvironment.request(
                "GET", "https://sphereon.test/$leafUsername/fetch?sub=${url(descendantId)}",
            )
            assertEquals(200, leafStatement.status)
            TrustedJwtVerifier.verify(leafStatement.body, ENTITY_STATEMENT_TYPE, leafId, descendantId, leaf.jwks)
            signedObjectEvidence.recordVerified(leafStatement.body, "subordinate_statement", "sphereon_leaf_to_descendant")

            // Under max_path_length=1, both the actual three-entity chain and the longer
            // four-entity chain resolve. Verify each signed link and exact leaf-to-TA order.
            val resolutionKeys = mapOf(
                SPHEREON to root.jwks,
                LIGHTHOUSE to lighthouseEc.jwks,
                leafId to leaf.jwks,
                descendantId to descendant.jwks,
            )
            val leafResolution = resolveFromSphereonRoot(activeEnvironment, leafId)
            assertEquals(200, leafResolution.status, "Sphereon GET /resolve returned HTTP ${leafResolution.status}: ${safeHttpError(leafResolution.body)}")
            verifyResolveResponse(
                leafResolution.body, root.jwks, SPHEREON, leafId, resolutionKeys,
                LIGHTHOUSE to leafId, SPHEREON, expectedMetadata = leaf.metadata,
            )
            assertEquals(
                listOf(leafId to leafId, LIGHTHOUSE to leafId, SPHEREON to LIGHTHOUSE),
                resolvedLinks(leafResolution.body),
                "three-entity trust chain must be leaf Entity Configuration, LightHouse statement, then Sphereon TA statement",
            )
            threeEntityPassed = true

            val descendantResolution = resolveFromSphereonRoot(activeEnvironment, descendantId)
            assertEquals(200, descendantResolution.status)
            verifyResolveResponse(
                descendantResolution.body, root.jwks, SPHEREON, descendantId, resolutionKeys,
                leafId to descendantId, SPHEREON, expectedMetadata = descendant.metadata,
            )
            assertEquals(
                listOf(
                    descendantId to descendantId,
                    leafId to descendantId,
                    LIGHTHOUSE to leafId,
                    SPHEREON to LIGHTHOUSE,
                ),
                resolvedLinks(descendantResolution.body),
                "four-entity chain must include the real account leaf below LightHouse",
            )
            fourEntityPassedAtOne = true

            // The Sphereon restart exercises the same PostgreSQL/PKCS12 providers and
            // root selection used by the baseline; the account-specific public paths remain unchanged.
            activeEnvironment.restartSphereon()
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement", account = "root").status)
            val freshRootJwt = activeEnvironment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
            assertEquals(200, freshRootJwt.status)
            postRestartRoot = verifyPeerConfiguration(freshRootJwt.body, SPHEREON)
            activeEnvironment.verifyDurableState("after-restart-publication", postRestartRoot!!.jwks)
            assertEquals(root.kid, postRestartRoot!!.kid)
            assertEquals(root.canonicalPublicJwks, postRestartRoot!!.canonicalPublicJwks)
            assertEquals(201, activeEnvironment.admin(
                "POST", "/subordinates/$rootToLighthouseId/statement", account = "root",
            ).status)

            // Set the same real LightHouse subordinate constraint to zero, restart LH to
            // clear its statement cache, and verify the newly signed claim before resolving.
            setLightHouseMaxPathLength(activeLighthouse, lighthouseLeafRelationId, 0)
            activeLighthouse.restart()
            val freshLighthouseEcJwt = activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val freshLighthouseEc = verifyPeerConfiguration(freshLighthouseEcJwt, LIGHTHOUSE)
            val lighthouseLeafAtZero = fetchVerifiedLightHouseSubordinate(
                activeLighthouse, leafId, freshLighthouseEc.jwks, expectedMaxPathLength = 0,
            )
            signedLighthouseLeafAtZero = lighthouseLeafAtZero

            // The receiving public process owns its resolver/trust-chain caches. Restart it
            // after the signed constraint change, preserving the same PostgreSQL and PKCS12 state.
            activeEnvironment.restartSphereon()

            // The leaf itself has zero intermediates between LightHouse and subject, so it
            // remains valid at max_path_length=0. Its descendant introduces one and must fail.
            val leafAtZero = resolveFromSphereonRoot(activeEnvironment, leafId)
            assertEquals(200, leafAtZero.status)
            verifyResolveResponse(
                leafAtZero.body, postRestartRoot!!.jwks, SPHEREON, leafId,
                mapOf(SPHEREON to postRestartRoot!!.jwks, LIGHTHOUSE to freshLighthouseEc.jwks, leafId to leaf.jwks),
                LIGHTHOUSE to leafId, SPHEREON, expectedMetadata = leaf.metadata,
            )
            leafPassedAtZero = true

            val rejectedDescendant = resolveFromSphereonRoot(activeEnvironment, descendantId)
            assertEquals(400, rejectedDescendant.status)
            assertEquals("invalid_trust_chain", JSONObjectUtils.getString(JSONObjectUtils.parse(rejectedDescendant.body), "error"))
            descendantRejectedAtZero = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeTopologyConstraintEvidence(
                    peer, startedAt, outcome, rootIdentity, postRestartRoot,
                    threeEntityPassed, fourEntityPassedAtOne, leafPassedAtZero, descendantRejectedAtZero,
                    signedLighthouseLeafAtOne != null, signedLighthouseLeafAtZero != null,
                    signedObjectEvidence.records(), environment?.runtimeStateEvidence(), environment?.artifactIdentity(),
                )
            } catch (failure: Throwable) { cleanupFailures += failure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun publishSphereonTenantAccount(
        environment: InteropEnvironment,
        username: String,
        entityId: String,
        authorityHint: String,
    ): EntityIdentity {
        require(username.matches(Regex("[a-z][a-z0-9-]{0,30}"))) { "invalid bounded test account name" }
        val created = environment.admin(
            "POST", "/accounts", JSONObjectUtils.toJSONString(mapOf("username" to username, "identifier" to entityId)),
            account = "root",
        )
        assertEquals(201, created.status, "could not provision account-scoped federation entity")

        val keyResponse = environment.admin("POST", "/keys", account = username)
        assertEquals(201, keyResponse.status)
        val keyId = JSONObjectUtils.getString(JSONObjectUtils.parse(keyResponse.body), "id")
        val selection = environment.admin("GET", "/keys/signing-selection", account = username)
        assertEquals(200, selection.status)
        val revision = (JSONObjectUtils.parse(selection.body)["revision"] as Number).toLong()
        assertEquals(200, environment.admin(
            "PUT", "/keys/signing-selection",
            """{"expectedRevision":$revision,"selectedKeyId":"$keyId"}""",
            account = username,
        ).status)
        assertEquals(201, environment.admin(
            "POST", "/authority-hints", JSONObjectUtils.toJSONString(mapOf("identifier" to authorityHint)),
            account = username,
        ).status)
        // OpenID Federation 1.1 section 3.1.1: an Entity Configuration declares the Entity Types it takes part with.
        assertEquals(201, environment.admin(
            "POST", "/metadata",
            JSONObjectUtils.toJSONString(mapOf("key" to "federation_entity", "metadata" to mapOf(
                "organization_name" to "Sphereon interop $username", "federation_fetch_endpoint" to "$entityId/fetch",
            ))),
            account = username,
        ).status)
        assertEquals(201, environment.admin("POST", "/entity-statement", account = username).status)

        // Public route is /{username}/.well-known/openid-federation and matches the
        // explicit Entity Identifier https://sphereon.test/{username}; SNI/host stay sphereon.test.
        val published = environment.request("GET", "https://sphereon.test/$username$ENTITY_CONFIGURATION")
        assertEquals(200, published.status)
        return verifyPeerConfiguration(published.body, entityId)
    }

    internal fun createSphereonSubordinateUnderAccount(
        environment: InteropEnvironment,
        parentUsername: String,
        childEntityId: String,
        childJwks: Map<String, Any?>,
    ): String {
        val created = environment.admin(
            "POST", "/subordinates", JSONObjectUtils.toJSONString(mapOf("identifier" to childEntityId)),
            account = parentUsername,
        )
        assertEquals(201, created.status)
        val subordinateId = JSONObjectUtils.getString(JSONObjectUtils.parse(created.body), "id")
        val keys = childJwks["keys"] as? List<*> ?: error("child Entity Configuration omitted public JWKS")
        keys.forEach { raw ->
            val jwk = raw as? Map<*, *> ?: error("child Entity Configuration contains an invalid JWK")
            val body = JSONObjectUtils.toJSONString(jwk.entries.associate { it.key.toString() to it.value })
            assertEquals(201, environment.admin("POST", "/subordinates/$subordinateId/jwks", body, account = parentUsername).status)
        }
        return subordinateId
    }

    private fun setLightHouseMaxPathLength(lighthouse: LighthousePeer, subordinateId: String, value: Int) {
        require(value in 0..1) { "topology test max_path_length is outside its bounded set" }
        val updated = lighthouse.admin(
            "PUT", "/api/v1/admin/subordinates/$subordinateId/constraints",
            mapOf("max_path_length" to value),
        )
        assertEquals(200, updated.status)
        val constraints = JSONObjectUtils.parse(updated.body)
        assertEquals(value, (constraints["max_path_length"] as Number).toInt())
    }

    private fun fetchVerifiedLightHouseSubordinate(
        lighthouse: LighthousePeer,
        subject: String,
        lighthouseJwks: Map<String, Any?>,
        expectedMaxPathLength: Int,
    ): String {
        val response = lighthouse.public("/fetch?sub=${url(subject)}")
        assertEquals(200, response.status)
        val claims = TrustedJwtVerifier.verify(
            response.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, subject, lighthouseJwks,
        )
        val constraints = claims["constraints"] as? Map<*, *> ?: error("LightHouse subordinate omitted constraints")
        assertEquals(expectedMaxPathLength, (constraints["max_path_length"] as Number).toInt())
        signedObjectEvidence.recordVerified(
            response.body, "subordinate_statement", "lighthouse_leaf_max_path_length_$expectedMaxPathLength",
        )
        return response.body
    }

    private fun resolveFromSphereonRoot(environment: InteropEnvironment, subject: String) = environment.request(
        "GET", "https://sphereon.test/resolve?sub=${url(subject)}&trust_anchor=${url(SPHEREON)}",
    )

    private fun resolvedLinks(compact: String): List<Pair<String, String>> {
        val chain = SignedJWT.parse(compact).jwtClaimsSet.getClaim("trust_chain") as? List<*>
            ?: error("resolve response omitted trust_chain")
        val links = chain.map { raw ->
            val token = raw as? String ?: error("resolve response contains a non-JWT trust-chain entry")
            val claims = SignedJWT.parse(token).jwtClaimsSet
            (claims.issuer ?: error("trust-chain statement omitted iss")) to
                (claims.subject ?: error("trust-chain statement omitted sub"))
        }
        // OpenID Federation 1.1 section 4: the chain logically ends with the Trust Anchor's Entity Configuration,
        // which MAY be omitted from the array. Compare the subject-to-anchor statements either way.
        val last = links.lastOrNull()
        return if (links.size > 1 && last != null && last.first == last.second && links[links.size - 2].first == last.first) {
            links.dropLast(1)
        } else links
    }

    private fun writeTopologyConstraintEvidence(
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        root: EntityIdentity?,
        postRestartRoot: EntityIdentity?,
        threeEntityPassed: Boolean,
        fourEntityPassedAtOne: Boolean,
        leafPassedAtZero: Boolean,
        descendantRejectedAtZero: Boolean,
        lighthouseStatementAtOneVerified: Boolean,
        lighthouseStatementAtZeroVerified: Boolean,
        verifiedSignedObjects: List<Map<String, String>>,
        runtimeStateChecks: List<Map<String, Any?>>?,
        sourceAndArtifactIdentity: Map<String, String>?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        Files.createDirectories(Path.of(directory))
        val fields = linkedMapOf<String, Any?>(
            "scenario" to "sphereon-ta-lighthouse-intermediate-account-leaf-path-constraints",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "sphereonRootEntityId" to SPHEREON,
            "leafAndDescendantUseAccountPath" to true,
            "constraintRefreshBoundary" to "cold-receiving-process-after-peer-policy-change",
            "automaticWarmCacheInvalidationTested" to false,
            "rootKidStableAcrossRestart" to if (root == null || postRestartRoot == null) null else root.kid == postRestartRoot.kid,
            "rootJwksStableAcrossRestart" to if (root == null || postRestartRoot == null) null else root.canonicalPublicJwks == postRestartRoot.canonicalPublicJwks,
            "threeEntityRootLighthouseLeafPassedAtMaxPathLengthOne" to threeEntityPassed,
            "fourEntityDescendantPathPassedAtMaxPathLengthOne" to fourEntityPassedAtOne,
            "threeEntityLeafStillPassedAtMaxPathLengthZero" to leafPassedAtZero,
            "fourEntityDescendantPathRejectedAtMaxPathLengthZero" to descendantRejectedAtZero,
            "signedLighthouseStatementAtMaxPathLengthOneVerified" to lighthouseStatementAtOneVerified,
            "signedLighthouseStatementAtMaxPathLengthZeroVerified" to lighthouseStatementAtZeroVerified,
            "verifiedSignedObjects" to verifiedSignedObjects,
            "runtimeStateChecks" to runtimeStateChecks,
            "sourceAndArtifactIdentity" to sourceAndArtifactIdentity,
            "compactJwtRecorded" to false,
            "claim" to if (outcome == "passed") "real Sphereon account path resolves through LightHouse and applies fresh max_path_length after a cold receiving-process restart" else "topology/constraint scenario did not pass",
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
        ).filterValues { it != null }
        Files.writeString(
            Path.of(directory).resolve("sphereon-ta-lighthouse-intermediate-account-path-constraints.json"),
            JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8,
        )
    }


    @Test
    fun `Sphereon validates Lighthouse signer rollover during key overlap`() {
        signedObjectEvidencePhase = "initial"
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var initialLighthouse: EntityIdentity? = null
        var rotatedLighthouse: EntityIdentity? = null
        var sphereon: EntityIdentity? = null
        var originalKid: String? = null
        var rotatedKid: String? = null
        var oldStatementVerifiedDuringOverlap = false
        var newStatementVerified = false
        var resolverPassed = false
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            ensureFetchEndpoint(activeLighthouse)

            // 15s EC lifetime bounds KMS's minimum announcement lead time; increase it
            // again once the replacement signer is active so the receiver gets a usable EC.
            assertEquals(200, activeLighthouse.setEntityConfigurationLifetime(15).status)
            val sphereonIdentity = publishSphereonEntityConfiguration(activeEnvironment, authorityHint = LIGHTHOUSE)
            sphereon = sphereonIdentity
            activeEnvironment.verifyDurableState("after-initial-publication", sphereonIdentity.jwks)

            val beforeJwt = activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val beforeVerified = PeerStatementVerifier.verify(beforeJwt, LIGHTHOUSE)
            initialLighthouse = verifyPeerConfiguration(beforeJwt, LIGHTHOUSE)
            originalKid = beforeVerified.kid
            assertEquals(LIGHTHOUSE, beforeVerified.issuer)
            signedObjectEvidencePhase = "initial"

            val relation = activeLighthouse.addSubordinate(SPHEREON, sphereonIdentity.jwks)
            assertEquals(201, relation.status)
            val originalStatement = activeLighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, originalStatement.status)
            val originalClaims = TrustedJwtVerifier.verify(
                originalStatement.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, initialLighthouse.jwks,
            )
            signedObjectEvidence.recordVerified(originalStatement.body, "subordinate_statement", "pre_rotation")
            assertEquals(LIGHTHOUSE, originalClaims["iss"])
            assertEquals(SPHEREON, originalClaims["sub"])
            val originalStatementKid = requireNotNull(SignedJWT.parse(originalStatement.body).header.keyID)
            assertEquals(originalKid, originalStatementKid)

            // Enabling the real filesystem KMS rotation loop immediately notices the
            // unbounded initial key, announces its actual generated replacement for 15s,
            // and keeps the old public key valid for 300s of configured overlap.
            val rotation = activeLighthouse.admin(
                "PUT", "/api/v1/admin/kms/rotation",
                mapOf(
                    "enabled" to true,
                    "interval" to 3600,
                    "overlap" to 300,
                    "key_announcement_lead_time" to 15,
                ),
            )
            assertEquals(200, rotation.status)
            val savedRotation = JSONObjectUtils.parse(rotation.body)
            assertEquals(true, savedRotation["enabled"])
            assertEquals(300L, (savedRotation["overlap"] as Number).toLong())
            assertEquals(15L, (savedRotation["key_announcement_lead_time"] as Number).toLong())
            val rotationDeadline = System.nanoTime() + 90_000_000_000L
            var observedRotatedKid: String? = null
            while (System.nanoTime() < rotationDeadline && observedRotatedKid == null) {
                val candidate = activeLighthouse.public(LighthousePeer.ENTITY_CONFIGURATION_PATH)
                if (candidate.status == 200) {
                    val verified = runCatching { PeerStatementVerifier.verify(candidate.body, LIGHTHOUSE) }.getOrNull()
                    if (verified != null && verified.kid != originalKid) observedRotatedKid = verified.kid
                }
                if (observedRotatedKid == null) Thread.sleep(250)
            }
            assertTrue(observedRotatedKid != null, "LightHouse did not publish a verified Entity Configuration under a new signer before the bounded deadline")

            // LH's fetch response caches subordinate JWTs. Restarting the same peer
            // clears that process cache while preserving its real SQLite/filesystem KMS state.
            activeLighthouse.restart()
            assertEquals(200, activeLighthouse.setEntityConfigurationLifetime(600).status)
            signedObjectEvidencePhase = "post_rotation"
            val afterJwt = activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val afterVerified = PeerStatementVerifier.verify(afterJwt, LIGHTHOUSE)
            rotatedLighthouse = verifyPeerConfiguration(afterJwt, LIGHTHOUSE)
            rotatedKid = afterVerified.kid
            assertEquals(observedRotatedKid, rotatedKid, "LightHouse's active signer changed unexpectedly across its persistence restart")
            assertEquals(beforeVerified.issuer, afterVerified.issuer)
            assertTrue(afterVerified.kid != beforeVerified.kid, "LightHouse signer kid did not change after real KMS rotation")

            val overlapKeys = rotatedLighthouse!!.jwks["keys"] as? List<*>
                ?: error("rotated LightHouse EC omitted its JWKS keys")
            val overlapKids = overlapKeys.mapNotNull { (it as? Map<*, *>)?.get("kid") as? String }.toSet()
            val oldKid = requireNotNull(originalKid)
            val newKid = requireNotNull(rotatedKid)
            assertTrue(oldKid in overlapKids, "old public signing key was absent during configured overlap")
            assertTrue(newKid in overlapKids, "new active signing key was absent from published JWKS")
            val oldKey = overlapKeys.map { it as? Map<*, *> ?: error("invalid published LightHouse JWK") }
                .single { it["kid"] == oldKid }
            val oldKeyExpiration = (oldKey["exp"] as? Number)?.toLong()
                ?: error("rotated LightHouse JWKS omitted old key expiration needed to prove overlap")
            assertTrue(oldKeyExpiration > Instant.now().epochSecond, "old signing key expired before overlap verification")

            // Re-verify the exact previously fetched real subordinate statement using
            // the fresh post-rotation Lighthouse JWKS. No JWT is synthesized or logged.
            val originalStatementExpiration = requireNotNull(SignedJWT.parse(originalStatement.body).jwtClaimsSet.expirationTime)
                .toInstant()
            assertTrue(originalStatementExpiration.isAfter(Instant.now()), "old subordinate statement expired before overlap verification")
            val oldClaimsAfterRotation = TrustedJwtVerifier.verify(
                originalStatement.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, rotatedLighthouse!!.jwks,
            )
            assertEquals(LIGHTHOUSE, oldClaimsAfterRotation["iss"])
            assertEquals(SPHEREON, oldClaimsAfterRotation["sub"])
            oldStatementVerifiedDuringOverlap = true
            signedObjectEvidence.recordVerified(
                originalStatement.body, "subordinate_statement", "old_statement_verified_during_overlap",
            )

            val newStatement = activeLighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, newStatement.status)
            val newClaims = TrustedJwtVerifier.verify(
                newStatement.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, rotatedLighthouse!!.jwks,
            )
            assertEquals(LIGHTHOUSE, newClaims["iss"])
            assertEquals(SPHEREON, newClaims["sub"])
            val newStatementKid = requireNotNull(SignedJWT.parse(newStatement.body).header.keyID)
            assertEquals(rotatedKid, newStatementKid)
            assertTrue(newStatement.body != originalStatement.body, "LightHouse returned the cached pre-rotation statement")
            newStatementVerified = true
            signedObjectEvidence.recordVerified(newStatement.body, "subordinate_statement", "new_signer")

            // Restart the real Sphereon JVM receiver to clear resolver caches, then resolve
            // through the LightHouse anchor and verify every returned chain JWT under fresh JWKS.
            activeEnvironment.restartSphereon()
            val resolved = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(200, resolved.status, "Sphereon GET /resolve returned HTTP ${resolved.status}: ${safeHttpError(resolved.body)}")
            verifyResolveResponse(
                resolved.body, sphereonIdentity.jwks, SPHEREON, SPHEREON,
                mapOf(SPHEREON to sphereonIdentity.jwks, LIGHTHOUSE to rotatedLighthouse!!.jwks),
                LIGHTHOUSE to SPHEREON, LIGHTHOUSE,
                expectedMetadata = sphereonIdentity.metadata,
            )
            resolverPassed = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (primaryFailure != null && environment != null) {
                try { environment!!.retainSafeServerErrorLogs("lighthouse-kms-key-rollover-overlap") }
                catch (diagnosticFailure: Throwable) { primaryFailure!!.addSuppressed(diagnosticFailure) }
            }
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeKeyRolloverEvidence(
                    peer, startedAt, outcome, originalKid, rotatedKid, oldStatementVerifiedDuringOverlap,
                    newStatementVerified, resolverPassed, signedObjectEvidence.records(),
                    environment?.artifactIdentity(),
                )
            } catch (evidenceFailure: Throwable) { cleanupFailures += evidenceFailure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun writeKeyRolloverEvidence(
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        originalKid: String?,
        rotatedKid: String?,
        oldStatementVerifiedDuringOverlap: Boolean,
        newStatementVerified: Boolean,
        resolverPassed: Boolean,
        verifiedSignedObjects: List<Map<String, String>>,
        sourceAndArtifactIdentity: Map<String, String>?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val fields = linkedMapOf<String, Any?>(
            "scenario" to "lighthouse-kms-key-rollover-overlap",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "peerEntityId" to LIGHTHOUSE,
            "sphereonEntityId" to SPHEREON,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
            "originalSigningKid" to originalKid,
            "rotatedSigningKid" to rotatedKid,
            "signerKidChanged" to (originalKid != null && rotatedKid != null && originalKid != rotatedKid),
            "oldStatementVerifiedDuringOverlap" to oldStatementVerifiedDuringOverlap,
            "newStatementVerifiedUnderRotatedJwks" to newStatementVerified,
            "sphereonResolverAcceptedFreshChain" to resolverPassed,
            "verifiedSignedObjects" to verifiedSignedObjects,
            "sourceAndArtifactIdentity" to sourceAndArtifactIdentity,
            "claim" to if (outcome == "passed") "real LightHouse filesystem-KMS key rollover passed during configured overlap" else "real key-rollover scenario did not pass",
        ).filterValues { it != null }
        val output = Path.of(directory)
        Files.createDirectories(output)
        Files.writeString(output.resolve("lighthouse-kms-key-rollover-overlap.json"), JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8)
    }


    @ParameterizedTest(name = "real Sphereon -> LightHouse path accepts {0}")
    @ValueSource(strings = ["ES256", "ES384", "ES512"])
    fun `LightHouse resolver accepts each key algorithm supported by Sphereon own-key API`(algorithm: String) {
        require(algorithm in setOf("ES256", "ES384", "ES512"))
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var outcome = "failed"
        var keyId: String? = null
        var identity: EntityIdentity? = null
        var verifiedResolve = false
        var resolveStatus: Int? = null
        var resolveError: String? = null
        val verifiedObjects = mutableListOf<Map<String, String>>()
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            ensureFetchEndpoint(activeLighthouse)

            // This is the real admin CreateKey schema and normal default software PKCS12 KMS.
            // No synthetic keys or private JWT construction is involved.
            val created = activeEnvironment.admin(
                "POST", "/keys", JSONObjectUtils.toJSONString(mapOf("signatureAlgorithm" to algorithm)),
            )
            assertEquals(201, created.status, "Sphereon POST /keys rejected a schema-advertised algorithm")
            val createdJson = JSONObjectUtils.parse(created.body)
            keyId = JSONObjectUtils.getString(createdJson, "id")
            assertEquals(algorithm, JSONObjectUtils.getString(createdJson, "alg"))
            val createSelection = activeEnvironment.admin("GET", "/keys/signing-selection")
            assertEquals(200, createSelection.status)
            val createRevision = (JSONObjectUtils.parse(createSelection.body)["revision"] as Number).toLong()
            assertEquals(200, activeEnvironment.admin(
                "PUT", "/keys/signing-selection",
                """{"expectedRevision":$createRevision,"selectedKeyId":"$keyId"}""",
            ).status)
            activeEnvironment.rememberSelectedKeyId(keyId!!)

            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement").status)
            val entityConfiguration = activeEnvironment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
            assertEquals(200, entityConfiguration.status)
            val sphereon = verifySphereonConfiguration(entityConfiguration.body)
            identity = sphereon
            assertEquals(algorithm, sphereon.algorithm, "signed Sphereon Entity Configuration used another algorithm")
            verifiedObjects += algorithmSignedObjectRecord(
                entityConfiguration.body, "entity_configuration", "sphereon_entity_configuration",
            )

            // Pin the actual Sphereon public JWKS as LightHouse's real trust anchor, then use
            // LightHouse's production resolver to validate the Sphereon -> LightHouse statement.
            assertEquals(201, activeLighthouse.addTrustAnchor(SPHEREON, sphereon.jwks).status)
            assertEquals(201, activeLighthouse.addAuthorityHint(SPHEREON).status)
            assertEquals(201, activeLighthouse.registerEndpoint(
                "resolve", "/resolve", JSONObjectUtils.toJSONString(mapOf("allowed_trust_anchors" to listOf(SPHEREON))),
            ).status)
            val lighthouseEcJwt = activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val lighthouseIdentity = verifyPeerConfiguration(lighthouseEcJwt, LIGHTHOUSE)

            val subordinateId = createSphereonSubordinate(activeEnvironment, LIGHTHOUSE, lighthouseIdentity.jwks)
            assertEquals(201, activeEnvironment.admin("POST", "/subordinates/$subordinateId/statement").status)
            val subordinateJwt = activeEnvironment.request(
                "GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}",
            ).also { assertEquals(200, it.status) }.body
            TrustedJwtVerifier.verify(subordinateJwt, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, sphereon.jwks)
            assertEquals(algorithm, SignedJWT.parse(subordinateJwt).header.algorithm.name)
            verifiedObjects += algorithmSignedObjectRecord(
                subordinateJwt, "subordinate_statement", "sphereon_subordinate_statement",
            )

            val resolve = activeLighthouse.resolve(LIGHTHOUSE, SPHEREON)
            resolveStatus = resolve.status
            resolveError = algorithmSafeFederationError(resolve.body)
            if (resolve.status != 200) outcome = "receiver_rejected_algorithm"
            assertEquals(
                200, resolve.status,
                "pinned LightHouse resolver rejected the real Sphereon $algorithm chain (safe error=$resolveError)",
            )
            verifyResolveResponse(
                resolve.body,
                lighthouseIdentity.jwks,
                LIGHTHOUSE,
                LIGHTHOUSE,
                mapOf(LIGHTHOUSE to lighthouseIdentity.jwks, SPHEREON to sphereon.jwks),
                SPHEREON to LIGHTHOUSE,
                SPHEREON,
                expectedMetadata = lighthouseIdentity.metadata,
            )
            verifiedObjects += algorithmSignedObjectRecord(
                resolve.body, "resolve_response", "lighthouse_resolve_response",
            )
            val chain = SignedJWT.parse(resolve.body).jwtClaimsSet.getClaim("trust_chain") as? List<*>
                ?: error("verified LightHouse resolve response omitted trust_chain")
            chain.forEach { raw ->
                val compact = raw as? String ?: error("verified resolve chain contains a non-JWT value")
                val parsed = SignedJWT.parse(compact)
                val issuer = requireNotNull(parsed.jwtClaimsSet.issuer)
                val subject = requireNotNull(parsed.jwtClaimsSet.subject)
                TrustedJwtVerifier.verify(
                    compact, ENTITY_STATEMENT_TYPE, issuer, subject,
                    mapOf(LIGHTHOUSE to lighthouseIdentity.jwks, SPHEREON to sphereon.jwks)[issuer]
                        ?: error("verified resolve chain has an unconfigured issuer"),
                )
                verifiedObjects += algorithmSignedObjectRecord(
                    compact,
                    if (issuer == subject) "entity_configuration" else "subordinate_statement",
                    "lighthouse_resolved_chain",
                )
            }
            verifiedResolve = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeAlgorithmInteropEvidence(
                    algorithm, peer, startedAt, outcome, keyId, identity,
                    resolveStatus, resolveError, verifiedResolve, verifiedObjects,
                    environment?.artifactIdentity(),
                )
            } catch (failure: Throwable) { cleanupFailures += failure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }


    @Test
    fun `Sphereon RSA SHA-256 own key is selected published and accepted by LightHouse`() {
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var sphereon: EntityIdentity? = null
        var primaryFailure: Throwable? = null
        var outcome = "failed"
        var resolverPassed = false
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            ensureFetchEndpoint(activeLighthouse)

            // The configured real software KMS persists generated signing keys in PKCS12.
            val created = activeEnvironment.admin(
                "POST", "/keys", """{"signatureAlgorithm":"RS256"}""",
            )
            assertEquals(201, created.status, "POST /keys must accept schema-advertised RS256")
            val createdJson = JSONObjectUtils.parse(created.body)
            val selectedKeyId = JSONObjectUtils.getString(createdJson, "id")
            assertEquals("RS256", JSONObjectUtils.getString(createdJson, "alg"))
            val selection = activeEnvironment.admin("GET", "/keys/signing-selection")
            assertEquals(200, selection.status)
            val revision = (JSONObjectUtils.parse(selection.body)["revision"] as Number).toLong()
            assertEquals(200, activeEnvironment.admin(
                "PUT", "/keys/signing-selection",
                """{"expectedRevision":$revision,"selectedKeyId":"$selectedKeyId"}""",
            ).status)
            activeEnvironment.rememberSelectedKeyId(selectedKeyId)

            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement").status)
            val ecResponse = activeEnvironment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
            assertEquals(200, ecResponse.status)
            val sphereonIdentity = verifySphereonConfiguration(ecResponse.body)
            sphereon = sphereonIdentity
            assertEquals("RS256", sphereonIdentity.algorithm, "published EC must use the selected RSA key")
            activeEnvironment.verifyDurableState("after-initial-publication", sphereonIdentity.jwks)

            // LightHouse consumes the actual Sphereon RSA public JWKS as its trust anchor.
            assertEquals(201, activeLighthouse.addTrustAnchor(SPHEREON, sphereonIdentity.jwks).status)
            assertEquals(201, activeLighthouse.addAuthorityHint(SPHEREON).status)
            assertEquals(201, activeLighthouse.registerEndpoint(
                "resolve", "/resolve", JSONObjectUtils.toJSONString(mapOf("allowed_trust_anchors" to listOf(SPHEREON))),
            ).status)
            val lighthouseEcJwt = activeLighthouse.public(ENTITY_CONFIGURATION)
                .also { assertEquals(200, it.status) }.body
            val lighthouseIdentity = verifyPeerConfiguration(lighthouseEcJwt, LIGHTHOUSE)

            val subordinateId = createSphereonSubordinate(activeEnvironment, LIGHTHOUSE, lighthouseIdentity.jwks)
            assertEquals(201, activeEnvironment.admin("POST", "/subordinates/$subordinateId/statement").status)
            val subordinate = activeEnvironment.request("GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}")
            assertEquals(200, subordinate.status)
            TrustedJwtVerifier.verify(
                subordinate.body, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, sphereonIdentity.jwks,
            )
            assertEquals("RS256", SignedJWT.parse(subordinate.body).header.algorithm.name)

            val resolved = activeLighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(200, resolved.status, "pinned LightHouse resolver must accept Sphereon's RS256 chain")
            verifyResolveResponse(
                resolved.body,
                lighthouseIdentity.jwks,
                LIGHTHOUSE,
                LIGHTHOUSE,
                mapOf(LIGHTHOUSE to lighthouseIdentity.jwks, SPHEREON to sphereonIdentity.jwks),
                SPHEREON to LIGHTHOUSE,
                SPHEREON,
                expectedMetadata = lighthouseIdentity.metadata,
            )
            resolverPassed = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeEvidence(
                    "sphereon-rs256-lighthouse-resolver", peer, startedAt, outcome,
                    sphereon, null, resolverPassed, environment,
                )
            } catch (failure: Throwable) { cleanupFailures += failure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }


    private fun algorithmSignedObjectRecord(compact: String, objectType: String, phase: String): Map<String, String> {
        // Call only after the existing cryptographic and semantic verifier accepted compact.
        val jwt = SignedJWT.parse(compact)
        return linkedMapOf(
            "objectType" to objectType,
            "phase" to phase,
            "issuer" to requireNotNull(jwt.jwtClaimsSet.issuer),
            "subject" to requireNotNull(jwt.jwtClaimsSet.subject),
            "alg" to jwt.header.algorithm.name,
            "kid" to requireNotNull(jwt.header.keyID),
        )
    }

    private fun algorithmSafeFederationError(body: String): String? = runCatching {
        JSONObjectUtils.getString(JSONObjectUtils.parse(body), "error")
    }.getOrNull()?.takeIf { it in setOf("invalid_trust_chain", "no_trust_chain", "trust_chain_validation_failed") }

    private fun writeAlgorithmInteropEvidence(
        algorithm: String,
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        keyId: String?,
        identity: EntityIdentity?,
        resolveStatus: Int?,
        resolveError: String?,
        resolveVerified: Boolean,
        verifiedObjects: List<Map<String, String>>,
        sourceAndArtifactIdentity: Map<String, String>?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        Files.createDirectories(Path.of(directory))
        val fields = linkedMapOf<String, Any?>(
            "scenario" to "sphereon-own-key-$algorithm-through-lighthouse-resolver",
            "outcome" to outcome,
            "requestedAlgorithm" to algorithm,
            "ownKeyRequestSchemaSupportsAlgorithm" to true,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "sphereonEntityId" to SPHEREON,
            "lighthouseEntityId" to LIGHTHOUSE,
            "sphereonSelectedKeyId" to keyId,
            "sphereonEntityConfigurationAlgorithm" to identity?.algorithm,
            "receiverResolveHttpStatus" to resolveStatus,
            "receiverResolveError" to resolveError,
            "receiverResolveCryptographicallyAndSemanticallyVerified" to resolveVerified,
            "verifiedSignedObjects" to verifiedObjects,
            "compactJwtRecorded" to false,
            "sourceAndArtifactIdentity" to sourceAndArtifactIdentity,
            "claim" to if (outcome == "passed") {
                "real Sphereon own-key $algorithm Entity Configuration and subordinate signature accepted by pinned LightHouse resolver"
            } else {
                "real Sphereon own-key $algorithm algorithm case did not complete successfully"
            },
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
        ).filterValues { it != null }
        Files.writeString(
            Path.of(directory).resolve("sphereon-own-key-$algorithm-lighthouse-resolver.json"),
            JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8,
        )
    }


    internal fun publishSphereonEntityConfiguration(
        environment: InteropEnvironment,
        authorityHint: String? = null,
    ): EntityIdentity {
        val createdKey = environment.admin("POST", "/keys")
        assertEquals(
            201,
            createdKey.status,
            "POST /keys returned HTTP ${createdKey.status}: ${safeHttpError(createdKey.body)}",
        )
        val createdKeyJson = JSONObjectUtils.parse(createdKey.body)
        val selectedKeyId = JSONObjectUtils.getString(createdKeyJson, "id")
        environment.rememberSelectedKeyId(selectedKeyId)
        val currentSelection = environment.admin("GET", "/keys/signing-selection")
        assertEquals(
            200,
            currentSelection.status,
            "GET /keys/signing-selection returned HTTP ${currentSelection.status}: ${safeHttpError(currentSelection.body)}",
        )
        val revision = (JSONObjectUtils.parse(currentSelection.body)["revision"] as Number).toLong()
        assertEquals(
            200,
            environment.admin("PUT", "/keys/signing-selection", """{"expectedRevision":$revision,"selectedKeyId":"$selectedKeyId"}""").status,
        )
        if (authorityHint != null) {
            val hint = JSONObjectUtils.toJSONString(mapOf("identifier" to authorityHint))
            assertEquals(201, environment.admin("POST", "/authority-hints", hint).status)
            val metadata = JSONObjectUtils.toJSONString(
                mapOf(
                    "key" to "federation_entity",
                    // A superior must advertise where its Subordinate Statements are fetched (OpenID Federation 1.1
                    // section 5.1.1); the standalone server publishes only the metadata it is given.
                    "metadata" to mapOf("federation_resolve_endpoint" to "$SPHEREON/resolve", "federation_fetch_endpoint" to "$SPHEREON/fetch"),
                ),
            )
            val createdMetadata = environment.admin("POST", "/metadata", metadata)
            assertEquals(
                201,
                createdMetadata.status,
                "POST /metadata returned HTTP ${createdMetadata.status}: ${safeHttpError(createdMetadata.body)}",
            )
        }
        val statement = environment.admin("POST", "/entity-statement")
        if (statement.status != 201) {
            val scenario = if (authorityHint == LIGHTHOUSE) {
                "sphereon-under-lighthouse-initial-publication"
            } else {
                "sphereon-under-sphereon-initial-publication"
            }
            val diagnosticResult = runCatching {
                environment.retainSafeHttpFailure(scenario, "POST", "/entity-statement", statement)
            }
            try {
                assertEquals(
                    201,
                    statement.status,
                    "Initial POST /entity-statement failed; redacted private diagnostics: " +
                        (diagnosticResult.getOrNull()?.toString() ?: "unavailable"),
                )
            } catch (assertionFailure: AssertionError) {
                diagnosticResult.exceptionOrNull()?.let(assertionFailure::addSuppressed)
                throw assertionFailure
            }
        }
        assertEquals(201, statement.status)
        val response = environment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
        assertEquals(200, response.status)
        return verifySphereonConfiguration(
            response.body,
            expectedAuthorityHints = if (authorityHint == null) emptyList() else listOf(authorityHint),
        )
    }

    private fun createSphereonSubordinate(environment: InteropEnvironment, identifier: String, jwks: Map<String, Any?>): String {
        val created = environment.admin("POST", "/subordinates", JSONObjectUtils.toJSONString(mapOf("identifier" to identifier)))
        assertEquals(201, created.status)
        val subordinateId = JSONObjectUtils.getString(JSONObjectUtils.parse(created.body), "id")
        val keys = jwks["keys"] as? List<*> ?: error("peer Entity Configuration has no JWKS keys")
        keys.forEach { key ->
            val keyObject = key as? Map<*, *> ?: error("peer Entity Configuration contains an invalid JWK")
            val body = JSONObjectUtils.toJSONString(keyObject.entries.associate { it.key.toString() to it.value })
            assertEquals(201, environment.admin("POST", "/subordinates/$subordinateId/jwks", body).status)
        }
        return subordinateId
    }

    private fun ensureFetchEndpoint(lighthouse: LighthousePeer) {
        assertEquals(201, lighthouse.registerEndpoint("fetch", "/fetch").status)
    }

    internal fun verifyPeerConfiguration(compact: String, entityId: String): EntityIdentity {
        val verified = PeerStatementVerifier.verify(compact, entityId)
        signedObjectEvidence.recordVerified(compact, "entity_configuration", signedObjectEvidencePhase)
        val claims = SignedJWT.parse(compact).jwtClaimsSet
        val jwks = JSONObjectUtils.getJSONObject(claims.toJSONObject(), "jwks")
        val metadata = claims.getClaim("metadata") as? Map<*, *>
        val authorityHints = (claims.getClaim("authority_hints") as? List<*>)
            ?.map { it as? String ?: error("verified Entity Configuration has a non-string authority hint") }
            ?: emptyList()
        return EntityIdentity(
            verified.kid,
            verified.algorithm,
            verified.jwks,
            jwks,
            requireNotNull(claims.issueTime).toInstant(),
            metadata?.entries?.associate { it.key.toString() to it.value },
            authorityHints,
            authorityHintsMatchExpected = false,
        )
    }

    /** Read back the configured lifetime from the real server's signed Entity Configuration. */
    private fun verifySphereonConfiguration(
        compact: String,
        expectedAuthorityHints: List<String> = emptyList(),
    ): EntityIdentity {
        val verifiedIdentity = verifyPeerConfiguration(compact, SPHEREON)
        val identity = verifiedIdentity.copy(
            authorityHintsMatchExpected = verifiedIdentity.authorityHints == expectedAuthorityHints,
        )
        val claims = SignedJWT.parse(compact).jwtClaimsSet
        val issuedAt = requireNotNull(claims.issueTime).toInstant().epochSecond
        val expiresAt = requireNotNull(claims.expirationTime).toInstant().epochSecond
        assertEquals(
            InteropEnvironment.STATEMENT_LIFETIME_SECONDS,
            expiresAt - issuedAt,
            "Sphereon Entity Configuration lifetime must match its configured server value",
        )
        return identity
    }

    private fun assertSphereonLeafMetadata(identity: EntityIdentity) {
        assertTrue(
            identity.authorityHints == listOf(LIGHTHOUSE),
            "verified Entity Configuration must retain the configured authority hint",
        )
        val federationEntityMetadata = identity.metadata?.get("federation_entity") as? Map<*, *>
        assertTrue(
            federationEntityMetadata?.get("federation_resolve_endpoint") == "$SPHEREON/resolve",
            "verified Entity Configuration must advertise this server's actual resolve endpoint",
        )
    }

    private fun assertSafeFetchError(
        response: InteropHttpResponse,
        expectedStatus: Int,
        expectedError: String,
        operation: String,
    ) {
        val safeError = safeHttpError(response.body)
        assertEquals(expectedStatus, response.status, "$operation returned HTTP ${response.status}: $safeError")
        assertEquals("error=$expectedError", safeError, "$operation returned an unexpected error: $safeError")
    }

    /** The Sphereon server's own error_description, bounded and with anything JWT-shaped redacted. */
    private fun describedBy(parsed: Map<*, *>): String {
        val description = parsed["error_description"] as? String ?: return ""
        val redacted = description.replace(Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"""), "[jwt]")
            .filter { !it.isISOControl() }.take(300)
        return " description=\"$redacted\""
    }

    /** Include only standard error fields; never print arbitrary server bodies into test output. */
    internal fun safeHttpError(body: String): String {
        val parsed = runCatching { JSONObjectUtils.parse(body) as? Map<*, *> }.getOrNull()
            ?: return "server returned an error outside the safe OAuth allowlist"
        val code = parsed["error"] as? String
            ?: return "server returned an error outside the safe OAuth allowlist"
        return if (code in SAFE_OAUTH_ERROR_CODES) "error=$code"
        // A bare snake_case code carries no server data; descriptions and bodies stay unprinted.
        else if (code.matches(Regex("[a-z][a-z0-9_]{0,63}"))) "error=$code (outside the OAuth allowlist)" + describedBy(parsed)
        else "server returned an error outside the safe OAuth allowlist"
    }

    internal fun verifyResolveResponse(
        compact: String,
        signingJwks: Map<String, Any?>,
        issuer: String,
        subject: String,
        issuerJwksByEntity: Map<String, Map<String, Any?>>,
        expectedSubordinate: Pair<String, String>,
        expectedAnchor: String,
        expectedMetadata: Map<String, Any?>?,
    ) {
        val claims = TrustedJwtVerifier.verify(compact, "resolve-response+jwt", issuer, subject, signingJwks)
        signedObjectEvidence.recordVerified(compact, "resolve_response", signedObjectEvidencePhase)
        val chain = claims["trust_chain"] as? List<*> ?: error("resolver omitted trust_chain")
        assertTrue(chain.isNotEmpty(), "resolver returned an empty trust chain")
        val links = chain.map { token ->
            val parsed = SignedJWT.parse(token as String)
            val statementIssuer = parsed.jwtClaimsSet.issuer ?: error("trust chain statement has no issuer")
            val statementSubject = parsed.jwtClaimsSet.subject ?: error("trust chain statement has no subject")
            TrustedJwtVerifier.verify(
                token,
                ENTITY_STATEMENT_TYPE,
                statementIssuer,
                statementSubject,
                issuerJwksByEntity[statementIssuer] ?: error("trust chain includes an unconfigured issuer"),
            )
            signedObjectEvidence.recordVerified(token, if (statementIssuer == statementSubject) "entity_configuration" else "subordinate_statement", "${signedObjectEvidencePhase}_chain")
            statementIssuer to statementSubject
        }
        assertEquals(subject to subject, links.first(), "resolved chain must begin with the subject Entity Configuration")
        assertTrue(
            links.zipWithNext().all { (current, next) -> current.first == next.second },
            "resolved trust-chain links are not connected leaf-to-anchor",
        )
        assertEquals(expectedAnchor, links.last().first, "resolved chain did not terminate at the expected trust anchor")
        assertTrue(expectedSubordinate in links, "resolved chain omitted the expected signed trust-anchor-to-subordinate statement")
        val metadata = claims["metadata"] as? Map<*, *>
        assertEquals(expectedMetadata ?: emptyMap<String, Any?>(), metadata ?: emptyMap<Any?, Any?>())
    }

    private fun writeEvidence(
        scenario: String,
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        initial: EntityIdentity?,
        afterRestart: EntityIdentity?,
        resolverPassed: Boolean,
        environment: InteropEnvironment?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val fields = linkedMapOf<String, Any?>(
            "scenario" to scenario,
            "signedObject" to "entity_configuration_and_subordinate_statement_and_resolve_response",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "sphereonEntityId" to SPHEREON,
            "peerEntityId" to LIGHTHOUSE,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
            "initialSigningAlgorithm" to initial?.algorithm,
            "postRestartSigningAlgorithm" to afterRestart?.algorithm,
            "initialSigningKid" to initial?.kid,
            "postRestartSigningKid" to afterRestart?.kid,
            "publicJwksStable" to if (initial == null || afterRestart == null) null else {
                initial.canonicalPublicJwks == afterRestart.canonicalPublicJwks
            },
            "resolverVerified" to resolverPassed,
            "initialEntityConfigurationStructure" to initial?.let(::entityConfigurationStructureEvidence),
            "postRestartEntityConfigurationStructure" to afterRestart?.let(::entityConfigurationStructureEvidence),
            "runtimeStateChecks" to environment?.runtimeStateEvidence()?.takeIf { it.isNotEmpty() },
            "verifiedSignedObjects" to signedObjectEvidence.records(),
            "sourceAndArtifactIdentity" to environment?.artifactIdentity(),
            "claim" to if (outcome == "passed") {
                "real deployment mixed-implementation core path passed"
            } else {
                "real deployment mixed-implementation core path did not pass"
            },
        ).filterValues { it != null }
        val output = Path.of(directory)
        Files.createDirectories(output)
        Files.writeString(output.resolve("$scenario.json"), JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8)
    }

    /** Only allowlisted metadata type names and booleans/counts leave the verified EC. */
    private fun entityConfigurationStructureEvidence(identity: EntityIdentity): Map<String, Any?> {
        val metadata = identity.metadata
        val types = metadata?.keys?.filterIsInstance<String>()?.toSet().orEmpty()
        return linkedMapOf(
            "signatureVerified" to true,
            "metadataObjectPresent" to (metadata != null),
            "metadataTypeCount" to (metadata?.size ?: 0),
            "recognizedPublicMetadataTypes" to types.filter { it in PUBLIC_METADATA_TYPES }.sorted(),
            "unknownMetadataTypeCount" to types.count { it !in PUBLIC_METADATA_TYPES },
            "authorityHintsMatchExpected" to identity.authorityHintsMatchExpected,
            "authorityHintCount" to identity.authorityHints.size,
        )
    }

    private fun closeOwned(peer: LighthousePeer?, environment: InteropEnvironment?): MutableList<Throwable> {
        val failures = mutableListOf<Throwable>()
        try { peer?.close() } catch (failure: Throwable) { failures += failure }
        try { environment?.close() } catch (failure: Throwable) { failures += failure }
        return failures
    }

    private fun url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    internal data class EntityIdentity(
        val kid: String,
        val algorithm: String,
        val canonicalPublicJwks: String,
        val jwks: Map<String, Any?>,
        val issuedAt: Instant,
        val metadata: Map<String, Any?>?,
        val authorityHints: List<String>,
        val authorityHintsMatchExpected: Boolean,
    )

    /** Parse only after the existing cryptographic verifier succeeds; never persist compact JWTs. */
    private class SignedObjectEvidenceCollector {
        private val verifiedObjects = mutableListOf<Map<String, String>>()
        fun recordVerified(compactJwt: String, objectType: String, phase: String) {
            val jwt = SignedJWT.parse(compactJwt)
            val claims = jwt.jwtClaimsSet
            verifiedObjects.add(linkedMapOf(
                "object" to objectType, "issuer" to requireNotNull(claims.issuer),
                "subject" to requireNotNull(claims.subject), "alg" to jwt.header.algorithm.name,
                "kid" to requireNotNull(jwt.header.keyID), "phase" to phase,
            ))
        }
        fun records(): List<Map<String, String>> = verifiedObjects.toList()
    }

    private companion object {
        const val ENTITY_STATEMENT_TYPE = "entity-statement+jwt"
        val PUBLIC_METADATA_TYPES = setOf(
            "federation_entity",
            "openid_provider",
            "openid_relying_party",
            "oauth_authorization_server",
            "oauth_client",
            "openid_credential_issuer",
            "openid_credential_verifier",
        )
        val SAFE_OAUTH_ERROR_CODES = setOf(
            "unauthorized", "invalid_token", "invalid_request", "forbidden", "not_found",
            "server_error", "invalid_client", "temporarily_unavailable", "insufficient_scope",
        )
    }

    // Candidate insertion: add as one test and helper members inside LighthouseInteropTest.
    // It uses the existing real Sphereon/Lighthouse peers and the current v7 harness helpers.

    @Test
    fun sphereonAppliesCompatiblePoliciesFromBothSignedLevelsAndRejectsTheirConflict() {
        runTwoLevelPolicyScenario()
    }

    /** Reused by the separate pilot-draft profile lane; it runs the same actual three-entity fixture. */
    internal fun runTwoLevelPolicyScenario(
        profileAssertions: Boolean = false,
        structuralProbe: ((InteropEnvironment, String) -> Unit)? = null,
    ) {
        signedObjectEvidencePhase = "two_level_policy_compatible_initial"
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var rootIdentity: EntityIdentity? = null
        var leafIdentity: EntityIdentity? = null
        var leafEntityId: String? = null
        var compatibleResolvePassed = false
        var bothSignedPolicyStatementsVerified = false
        var conflictingPolicyStatementVerified = false
        var conflictRejected = false
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            ensureFetchEndpoint(activeLighthouse)

            val root = publishSphereonEntityConfiguration(activeEnvironment, authorityHint = LIGHTHOUSE)
            rootIdentity = root
            activeEnvironment.verifyDurableState("after-initial-publication", root.jwks)

            if (profileAssertions) {
                // The draft-only lane checks the actual Sphereon intermediate Entity Configuration.
                val rootConfiguration = activeEnvironment.request("GET", "https://sphereon.test$ENTITY_CONFIGURATION")
                assertEquals(200, rootConfiguration.status)
                ResearchEducationProfileVerifier.verify(rootConfiguration.body, SPHEREON)
                signedObjectEvidence.recordVerified(
                    rootConfiguration.body, "entity_configuration", "edugain_re_draft_sphereon_intermediate_ec",
                )
            }

            // This is the product's supported account-level policy API. The root account's
            // stored policy is included by its real subordinate-statement builder.
            val sphereonPolicy = mapOf(
                "contacts" to mapOf("add" to listOf("root-policy@interop.example.org")),
            )
            val rootPolicyCreate = activeEnvironment.admin(
                "POST", "/metadata-policies",
                JSONObjectUtils.toJSONString(mapOf("key" to "openid_relying_party", "policy" to sphereonPolicy)),
                account = "root",
            )
            assertEquals(201, rootPolicyCreate.status)

            val suffix = java.lang.Long.toUnsignedString(System.nanoTime(), 36).takeLast(10)
            val leafUsername = "policy$suffix"
            val leafId = "$SPHEREON/$leafUsername"
            leafEntityId = leafId
            publishSphereonTenantAccount(activeEnvironment, leafUsername, leafId, SPHEREON)

            // Publish leaf metadata through the account metadata API, then republish its
            // actual Entity Configuration so the resolver starts from signed RP contacts.
            val rpMetadata = mapOf(
                "client_name" to "Sphereon two-level policy RP",
                "contacts" to listOf("leaf-policy@interop.example.org"),
            )
            val leafMetadataResponse = activeEnvironment.admin(
                "POST", "/metadata",
                JSONObjectUtils.toJSONString(mapOf("key" to "openid_relying_party", "metadata" to rpMetadata)),
                account = leafUsername,
            )
            assertEquals(201, leafMetadataResponse.status)
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement", account = leafUsername).status)
            val leafConfiguration = activeEnvironment.request("GET", "https://sphereon.test/$leafUsername$ENTITY_CONFIGURATION")
            assertEquals(200, leafConfiguration.status)
            val leaf = verifyPeerConfiguration(leafConfiguration.body, leafId)
            leafIdentity = leaf
            if (profileAssertions) {
                ResearchEducationProfileVerifier.verify(leafConfiguration.body, leafId)
                signedObjectEvidence.recordVerified(
                    leafConfiguration.body, "entity_configuration", "edugain_re_draft_leaf_ec",
                )
            }
            val publishedRp = leaf.metadata?.get("openid_relying_party") as? Map<*, *>
                ?: error("Sphereon leaf Entity Configuration omitted RP metadata")
            assertEquals(listOf("leaf-policy@interop.example.org"), publishedRp["contacts"])
            signedObjectEvidence.recordVerified(leafConfiguration.body, "entity_configuration", "two_level_policy_leaf_ec")

            // The root signs the immediate subordinate statement; its selected signer is a
            // real root account key, and the account-level policy is read into this payload.
            val rootToLeafId = createSphereonSubordinateUnderAccount(activeEnvironment, "root", leafId, leaf.jwks)
            assertEquals(201, activeEnvironment.admin(
                "POST", "/subordinates/$rootToLeafId/statement", account = "root",
            ).status)
            val rootStatement = activeEnvironment.request("GET", "https://sphereon.test/fetch?sub=${url(leafId)}")
            assertEquals(200, rootStatement.status)
            val rootClaims = TrustedJwtVerifier.verify(rootStatement.body, ENTITY_STATEMENT_TYPE, SPHEREON, leafId, root.jwks)
            val rootStatementPolicy = rootClaims["metadata_policy"] as? Map<*, *>
                ?: error("Sphereon signed subordinate statement omitted metadata_policy")
            val rootRpPolicy = rootStatementPolicy["openid_relying_party"] as? Map<*, *>
                ?: error("Sphereon signed subordinate statement omitted RP policy")
            val rootContactsPolicy = rootRpPolicy["contacts"] as? Map<*, *>
                ?: error("Sphereon signed subordinate statement omitted contacts policy")
            assertEquals(listOf("root-policy@interop.example.org"), rootContactsPolicy["add"])
            signedObjectEvidence.recordVerified(rootStatement.body, "subordinate_statement", "sphereon_root_to_leaf_policy")

            val lighthouseConfiguration = activeLighthouse.public(ENTITY_CONFIGURATION)
                .also { assertEquals(200, it.status) }.body
            val lighthouseEc = verifyPeerConfiguration(lighthouseConfiguration, LIGHTHOUSE)
            if (profileAssertions) {
                ResearchEducationProfileVerifier.verify(lighthouseConfiguration, LIGHTHOUSE)
                signedObjectEvidence.recordVerified(
                    lighthouseConfiguration, "entity_configuration", "edugain_re_draft_lighthouse_anchor_ec",
                )
            }
            val lighthouseRelationship = activeLighthouse.addSubordinate(SPHEREON, root.jwks)
            assertEquals(201, lighthouseRelationship.status)
            val lighthouseRelationshipId = (JSONObjectUtils.parse(lighthouseRelationship.body)["id"] as Number).toLong().toString()

            val lighthousePolicy = mapOf(
                "openid_relying_party" to mapOf(
                    "contacts" to mapOf("add" to listOf("authority-policy@interop.example.org")),
                ),
            )
            assertEquals(200, activeLighthouse.admin(
                "PUT", "/api/v1/admin/subordinates/$lighthouseRelationshipId/metadata-policies", lighthousePolicy,
            ).status)
            val lighthouseStatement = activeLighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, lighthouseStatement.status)
            val lighthouseClaims = TrustedJwtVerifier.verify(
                lighthouseStatement.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, lighthouseEc.jwks,
            )
            val lighthouseStatementPolicy = lighthouseClaims["metadata_policy"] as? Map<*, *>
                ?: error("LightHouse signed subordinate statement omitted metadata_policy")
            val lighthouseRpPolicy = lighthouseStatementPolicy["openid_relying_party"] as? Map<*, *>
                ?: error("LightHouse signed subordinate statement omitted RP policy")
            val lighthouseContactsPolicy = lighthouseRpPolicy["contacts"] as? Map<*, *>
                ?: error("LightHouse signed subordinate statement omitted contacts policy")
            assertEquals(listOf("authority-policy@interop.example.org"), lighthouseContactsPolicy["add"])
            signedObjectEvidence.recordVerified(lighthouseStatement.body, "subordinate_statement", "lighthouse_to_sphereon_policy")
            bothSignedPolicyStatementsVerified = true

            // The receiver restart clears its resolver cache. Both real signed levels add
            // distinct contacts; trust-chain merge is superior-first (LightHouse, then root).
            activeEnvironment.restartSphereon()
            signedObjectEvidencePhase = "two_level_policy_compatible_after_receiver_restart"
            val resolved = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(200, resolved.status, "Sphereon GET /resolve returned HTTP ${resolved.status}: ${safeHttpError(resolved.body)}")
            val expectedMetadata = leaf.metadata?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
                ?: error("Sphereon leaf Entity Configuration omitted metadata")
            val expectedRp = publishedRp.entries.associate { it.key.toString() to it.value }.toMutableMap()
            expectedRp["contacts"] = listOf(
                "leaf-policy@interop.example.org",
                "authority-policy@interop.example.org",
                "root-policy@interop.example.org",
            )
            expectedMetadata["openid_relying_party"] = expectedRp
            verifyResolveResponse(
                resolved.body,
                root.jwks,
                SPHEREON,
                leafId,
                mapOf(SPHEREON to root.jwks, LIGHTHOUSE to lighthouseEc.jwks, leafId to leaf.jwks),
                SPHEREON to leafId,
                LIGHTHOUSE,
                expectedMetadata,
            )
            assertEquals(
                listOf(leafId to leafId, SPHEREON to leafId, LIGHTHOUSE to SPHEREON),
                resolvedLinks(resolved.body),
            )
            compatibleResolvePassed = true

            // The source-pinned OFCLI structural probe is supplemental only; Nimbus has already
            // validated the resolve JWT, signer keys, and every statement in the real chain.
            structuralProbe?.invoke(activeEnvironment, leafId)

            // Replace the actual LightHouse subordinate policy. The root still requires its
            // add value, while the superior subset_of allows only a different contact; the
            // standard policy merge must fail closed on their incompatible signed values.
            signedObjectEvidencePhase = "two_level_policy_conflict"
            val conflictPolicy = mapOf(
                "openid_relying_party" to mapOf(
                    "contacts" to mapOf("subset_of" to listOf("authority-policy@interop.example.org")),
                ),
            )
            assertEquals(200, activeLighthouse.admin(
                "PUT", "/api/v1/admin/subordinates/$lighthouseRelationshipId/metadata-policies", conflictPolicy,
            ).status)
            val conflictingStatement = activeLighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, conflictingStatement.status)
            val conflictingClaims = TrustedJwtVerifier.verify(
                conflictingStatement.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, lighthouseEc.jwks,
            )
            val conflictingPolicy = conflictingClaims["metadata_policy"] as? Map<*, *>
                ?: error("LightHouse conflicting signed statement omitted metadata_policy")
            val conflictingRp = conflictingPolicy["openid_relying_party"] as? Map<*, *>
                ?: error("LightHouse conflicting signed statement omitted RP policy")
            val conflictingContacts = conflictingRp["contacts"] as? Map<*, *>
                ?: error("LightHouse conflicting signed statement omitted contacts policy")
            assertEquals(listOf("authority-policy@interop.example.org"), conflictingContacts["subset_of"])
            signedObjectEvidence.recordVerified(conflictingStatement.body, "subordinate_statement", signedObjectEvidencePhase)
            conflictingPolicyStatementVerified = true

            activeEnvironment.restartSphereon()
            signedObjectEvidencePhase = "two_level_policy_conflict_after_receiver_restart"
            val rejected = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(400, rejected.status)
            assertEquals("invalid_metadata", JSONObjectUtils.getString(JSONObjectUtils.parse(rejected.body), "error"))
            conflictRejected = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (primaryFailure != null) {
                try { environment?.retainSafeServerErrorLogs("two-level-policy") }
                catch (diagnosticFailure: Throwable) { primaryFailure!!.addSuppressed(diagnosticFailure) }
            }
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeTwoLevelMetadataPolicyEvidence(
                    peer, startedAt, outcome, rootIdentity, leafIdentity, leafEntityId,
                    compatibleResolvePassed, bothSignedPolicyStatementsVerified,
                    conflictingPolicyStatementVerified, conflictRejected,
                    signedObjectEvidence.records(), environment?.runtimeStateEvidence(), environment?.artifactIdentity(),
                )
            } catch (failure: Throwable) {
                cleanupFailures += failure
            }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun writeTwoLevelMetadataPolicyEvidence(
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        root: EntityIdentity?,
        leaf: EntityIdentity?,
        leafEntityId: String?,
        compatibleResolvePassed: Boolean,
        bothSignedPolicyStatementsVerified: Boolean,
        conflictingPolicyStatementVerified: Boolean,
        conflictRejected: Boolean,
        verifiedSignedObjects: List<Map<String, String>>,
        runtimeStateChecks: List<Map<String, Any?>>?,
        sourceAndArtifactIdentity: Map<String, String>?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        Files.createDirectories(Path.of(directory))
        val fields = linkedMapOf<String, Any?>(
            "scenario" to "lighthouse-sphereon-two-level-rp-metadata-policy-inheritance",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "sphereonRootEntityId" to SPHEREON,
            "lightHouseTrustAnchorEntityId" to LIGHTHOUSE,
            "leafEntityId" to leafEntityId,
            "rootSignerAlgorithm" to root?.algorithm,
            "rootSignerKid" to root?.kid,
            "leafSignerAlgorithm" to leaf?.algorithm,
            "leafSignerKid" to leaf?.kid,
            "compatibleTwoLevelResolvePassed" to compatibleResolvePassed,
            "bothRealSignedPolicyStatementsVerified" to bothSignedPolicyStatementsVerified,
            "conflictingRealSignedPolicyStatementVerified" to conflictingPolicyStatementVerified,
            "twoLevelConflictRejectedAsInvalidMetadata" to conflictRejected,
            "cacheBoundary" to "cold-sphereon-receiver-restart-before-each-resolution",
            "automaticWarmCacheInvalidationTested" to false,
            "verifiedSignedObjects" to verifiedSignedObjects,
            "runtimeStateChecks" to runtimeStateChecks,
            "sourceAndArtifactIdentity" to sourceAndArtifactIdentity,
            "compactJwtRecorded" to false,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
        ).filterValues { it != null }
        Files.writeString(
            Path.of(directory).resolve("lighthouse-sphereon-two-level-metadata-policy.json"),
            JSONObjectUtils.toJSONString(fields),
            StandardCharsets.UTF_8,
        )
    }

    // Candidate insertion: add the following test and helpers as members of LighthouseInteropTest.
    // Existing imports and harness functions in core-aggregate-v7 already cover these types/APIs.

    @Test
    fun lighthouseAnchorThroughSphereonRootToAccountLeafWithMultiAnchorAndNamingConstraints() {
        signedObjectEvidencePhase = "inverse_topology_initial"
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var root: EntityIdentity? = null
        var leaf: EntityIdentity? = null
        var leafEntityId: String? = null
        var postPolicyLighthouse: EntityIdentity? = null
        var activeLighthousePath = false
        var preferredAnchorPath = false
        var directAlternativePath = false
        var permittedConstraintVerified = false
        var excludedByNamingConstraint = false
        var directPathSurvivedLighthouseConstraint = false
        var unrelatedAnchorRejected = false
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            ensureFetchEndpoint(activeLighthouse)
            val rootIdentity = publishSphereonEntityConfiguration(activeEnvironment, authorityHint = LIGHTHOUSE)
            root = rootIdentity
            activeEnvironment.verifyDurableState("after-initial-publication", rootIdentity.jwks)
            val initialLighthouse = verifyPeerConfiguration(
                activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body,
                LIGHTHOUSE,
            )

            val suffix = java.lang.Long.toUnsignedString(System.nanoTime(), 36).takeLast(10)
            val leafUsername = "inverse$suffix"
            val leafId = "$SPHEREON/$leafUsername"
            leafEntityId = leafId
            val leafIdentity = publishSphereonTenantAccount(activeEnvironment, leafUsername, leafId, SPHEREON)
            leaf = leafIdentity

            // LightHouse signs the LightHouse -> Sphereon-root relationship.
            val lighthouseToRoot = activeLighthouse.addSubordinate(SPHEREON, rootIdentity.jwks)
            assertEquals(201, lighthouseToRoot.status)
            val lighthouseToRootId = (JSONObjectUtils.parse(lighthouseToRoot.body)["id"] as Number).toLong().toString()
            val lighthouseRootStatement = activeLighthouse.public("/fetch?sub=${url(SPHEREON)}")
            assertEquals(200, lighthouseRootStatement.status)
            TrustedJwtVerifier.verify(
                lighthouseRootStatement.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, SPHEREON, initialLighthouse.jwks,
            )
            signedObjectEvidence.recordVerified(
                lighthouseRootStatement.body, "subordinate_statement", "lighthouse_to_sphereon_root_initial",
            )

            // The root signs root -> real account leaf; the leaf advertises the root as its hint.
            val rootToLeafId = createSphereonSubordinateUnderAccount(activeEnvironment, "root", leafId, leafIdentity.jwks)
            assertEquals(201, activeEnvironment.admin(
                "POST", "/subordinates/$rootToLeafId/statement", account = "root",
            ).status)
            val rootToLeaf = activeEnvironment.request("GET", "https://sphereon.test/fetch?sub=${url(leafId)}")
            assertEquals(200, rootToLeaf.status)
            TrustedJwtVerifier.verify(rootToLeaf.body, ENTITY_STATEMENT_TYPE, SPHEREON, leafId, rootIdentity.jwks)
            signedObjectEvidence.recordVerified(rootToLeaf.body, "subordinate_statement", "sphereon_root_to_account_leaf")

            setLightHouseNamingConstraints(activeLighthouse, lighthouseToRootId, "sphereon.test")
            // Restart the signer peer at its signed-statement cache boundary after the API update.
            activeLighthouse.restart()
            val permittedLighthouse = verifyPeerConfiguration(
                activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body,
                LIGHTHOUSE,
            )
            fetchVerifiedLightHouseNamingSubordinate(
                activeLighthouse, SPHEREON, permittedLighthouse.jwks, "sphereon.test",
                "inverse_topology_permitted_naming_constraint",
            )
            permittedConstraintVerified = true

            val keys = mapOf(SPHEREON to rootIdentity.jwks, LIGHTHOUSE to permittedLighthouse.jwks, leafId to leafIdentity.jwks)
            val activePath = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(200, activePath.status, "Sphereon GET /resolve returned HTTP ${activePath.status}: ${safeHttpError(activePath.body)}")
            verifyResolveResponse(
                activePath.body, rootIdentity.jwks, SPHEREON, leafId, keys,
                SPHEREON to leafId, LIGHTHOUSE, expectedMetadata = leafIdentity.metadata,
            )
            assertEquals(
                listOf(leafId to leafId, SPHEREON to leafId, LIGHTHOUSE to SPHEREON), resolvedLinks(activePath.body),
                "selected inverse chain must be leaf EC, Sphereon root-to-leaf, then LightHouse-to-root",
            )
            activeLighthousePath = true

            // OpenID Federation 1.1 section 8.3.1: trust_anchor may repeat, and the resolver may answer with a chain
            // to any of the given anchors. Verify whichever valid chain the product selected, link by link.
            val preferred = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(LIGHTHOUSE)}&trust_anchor=${url(SPHEREON)}",
            )
            assertEquals(200, preferred.status, "Sphereon GET /resolve returned HTTP ${preferred.status}: ${safeHttpError(preferred.body)}")
            val selectedLinks = resolvedLinks(preferred.body)
            if (selectedLinks.last().first == LIGHTHOUSE) {
                verifyResolveResponse(
                    preferred.body, rootIdentity.jwks, SPHEREON, leafId, keys,
                    SPHEREON to leafId, LIGHTHOUSE, expectedMetadata = leafIdentity.metadata,
                )
                assertEquals(listOf(leafId to leafId, SPHEREON to leafId, LIGHTHOUSE to SPHEREON), selectedLinks)
            } else {
                verifyResolveResponse(
                    preferred.body, rootIdentity.jwks, SPHEREON, leafId, keys,
                    SPHEREON to leafId, SPHEREON, expectedMetadata = leafIdentity.metadata,
                )
                assertEquals(listOf(leafId to leafId, SPHEREON to leafId), selectedLinks)
            }
            preferredAnchorPath = true

            val direct = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(SPHEREON)}",
            )
            assertEquals(200, direct.status)
            verifyResolveResponse(
                direct.body, rootIdentity.jwks, SPHEREON, leafId, keys,
                SPHEREON to leafId, SPHEREON, expectedMetadata = leafIdentity.metadata,
            )
            assertEquals(listOf(leafId to leafId, SPHEREON to leafId), resolvedLinks(direct.body))
            directAlternativePath = true
            // Provision a second real account identity as an unrelated candidate anchor.
            // It is served by the same test host, but no signed path from the target leaf reaches it.
            val unrelatedUsername = "other$suffix"
            val unrelatedId = "$SPHEREON/$unrelatedUsername"
            publishSphereonTenantAccount(activeEnvironment, unrelatedUsername, unrelatedId, SPHEREON)
            val unrelatedConfiguration = activeEnvironment.request(
                "GET", "https://sphereon.test/$unrelatedUsername$ENTITY_CONFIGURATION",
            )
            assertEquals(200, unrelatedConfiguration.status)
            val unrelatedIdentity = verifyPeerConfiguration(unrelatedConfiguration.body, unrelatedId)
            signedObjectEvidence.recordVerified(
                unrelatedConfiguration.body, "entity_configuration", "unrelated_sibling_account_anchor_candidate",
            )
            assertTrue(unrelatedIdentity.kid.isNotBlank())
            val unrelatedResolution = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(unrelatedId)}",
            )
            assertEquals(400, unrelatedResolution.status)
            assertEquals("invalid_trust_chain", JSONObjectUtils.getString(JSONObjectUtils.parse(unrelatedResolution.body), "error"))
            unrelatedAnchorRejected = true

            // Change the actual peer-issued constraint, restart its signer cache, verify the
            // new signed claim, then restart the receiver cache before negative resolution.
            setLightHouseNamingConstraints(activeLighthouse, lighthouseToRootId, "unrelated.example")
            activeLighthouse.restart()
            postPolicyLighthouse = verifyPeerConfiguration(
                activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body,
                LIGHTHOUSE,
            )
            fetchVerifiedLightHouseNamingSubordinate(
                activeLighthouse, SPHEREON, postPolicyLighthouse!!.jwks, "unrelated.example",
                "inverse_topology_excluding_naming_constraint",
            )
            activeEnvironment.restartSphereon()
            signedObjectEvidencePhase = "after_lighthouse_naming_constraint_and_receiver_restart"

            val rejected = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(LIGHTHOUSE)}",
            )
            assertEquals(400, rejected.status)
            assertEquals("invalid_trust_chain", JSONObjectUtils.getString(JSONObjectUtils.parse(rejected.body), "error"))
            excludedByNamingConstraint = true

            // The direct Sphereon anchor remains valid because that chain excludes the
            // restrictive LightHouse statement. This also distinguishes path-specific failure.
            val directAfterConstraint = activeEnvironment.request(
                "GET", "https://sphereon.test/resolve?sub=${url(leafId)}&trust_anchor=${url(SPHEREON)}",
            )
            assertEquals(200, directAfterConstraint.status)
            verifyResolveResponse(
                directAfterConstraint.body, rootIdentity.jwks, SPHEREON, leafId, keys,
                SPHEREON to leafId, SPHEREON, expectedMetadata = leafIdentity.metadata,
            )
            assertEquals(listOf(leafId to leafId, SPHEREON to leafId), resolvedLinks(directAfterConstraint.body))
            directPathSurvivedLighthouseConstraint = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeInverseTopologyEvidence(
                    peer, startedAt, outcome, root, leaf, leafEntityId, postPolicyLighthouse,
                    activeLighthousePath, preferredAnchorPath, directAlternativePath,
                    permittedConstraintVerified, excludedByNamingConstraint,
                    directPathSurvivedLighthouseConstraint, unrelatedAnchorRejected, signedObjectEvidence.records(),
                    environment?.runtimeStateEvidence(), environment?.artifactIdentity(),
                )
            } catch (failure: Throwable) {
                cleanupFailures += failure
            }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun setLightHouseNamingConstraints(lighthouse: LighthousePeer, subordinateId: String, permittedHost: String) {
        require(permittedHost == "sphereon.test" || permittedHost == "unrelated.example") {
            "naming constraint is outside the bounded test set"
        }
        val response = lighthouse.admin(
            "PUT", "/api/v1/admin/subordinates/$subordinateId/constraints",
            mapOf("naming_constraints" to mapOf("permitted" to listOf(permittedHost))),
        )
        assertEquals(200, response.status)
        val stored = JSONObjectUtils.parse(response.body) as? Map<*, *>
            ?: error("LightHouse constraints response was not an object")
        val constraints = stored["naming_constraints"] as? Map<*, *>
            ?: error("LightHouse constraints response omitted naming_constraints")
        assertEquals(listOf(permittedHost), constraints["permitted"])
    }

    private fun fetchVerifiedLightHouseNamingSubordinate(
        lighthouse: LighthousePeer,
        subject: String,
        lighthouseJwks: Map<String, Any?>,
        expectedPermittedHost: String,
        phase: String,
    ): String {
        val response = lighthouse.public("/fetch?sub=${url(subject)}")
        assertEquals(200, response.status)
        val claims = TrustedJwtVerifier.verify(response.body, ENTITY_STATEMENT_TYPE, LIGHTHOUSE, subject, lighthouseJwks)
        val constraints = claims["constraints"] as? Map<*, *> ?: error("LightHouse subordinate omitted constraints")
        val naming = constraints["naming_constraints"] as? Map<*, *>
            ?: error("LightHouse subordinate omitted naming_constraints")
        assertEquals(listOf(expectedPermittedHost), naming["permitted"])
        signedObjectEvidence.recordVerified(response.body, "subordinate_statement", phase)
        return response.body
    }

    private fun writeInverseTopologyEvidence(
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        root: EntityIdentity?,
        leaf: EntityIdentity?, 
        leafEntityId: String?,
        postPolicyLighthouse: EntityIdentity?,
        activeLighthousePath: Boolean,
        preferredAnchorPath: Boolean,
        directAlternativePath: Boolean,
        permittedConstraintVerified: Boolean,
        excludedByNamingConstraint: Boolean,
        directPathSurvivedLighthouseConstraint: Boolean,
        unrelatedAnchorRejected: Boolean,
        verifiedSignedObjects: List<Map<String, String>>,
        runtimeStateChecks: List<Map<String, Any?>>?,
        sourceAndArtifactIdentity: Map<String, String>?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        Files.createDirectories(Path.of(directory))
        val fields = linkedMapOf<String, Any?>(
            "scenario" to "lighthouse-anchor-sphereon-root-account-leaf-multi-anchor-naming-constraints",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "sphereonRootEntityId" to SPHEREON,
            "lighthouseEntityId" to LIGHTHOUSE,
            "leafEntityId" to leafEntityId,
            "constraintCacheBoundary" to "cold-light-house-and-receiving-sphereon-restarts",
            "automaticWarmCacheInvalidationTested" to false,
            "rootSigningKid" to root?.kid,
            "leafSigningKid" to leaf?.kid,
            "lighthousePostPolicySigningKid" to postPolicyLighthouse?.kid,
            "validInversePathPassed" to activeLighthousePath,
            "orderedMultiAnchorSelectedLighthousePath" to preferredAnchorPath,
            "directSphereonAlternativePathPassed" to directAlternativePath,
            "signedPermittedNamingConstraintVerified" to permittedConstraintVerified,
            "lighthousePathRejectedAfterRestrictiveNamingConstraint" to excludedByNamingConstraint,
            "directSphereonPathSurvivedLighthouseConstraint" to directPathSurvivedLighthouseConstraint,
            "unrelatedRealSiblingAnchorRejected" to unrelatedAnchorRejected,
            "verifiedSignedObjects" to verifiedSignedObjects,
            "runtimeStateChecks" to runtimeStateChecks,
            "sourceAndArtifactIdentity" to sourceAndArtifactIdentity,
            "compactJwtRecorded" to false,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
        ).filterValues { it != null }
        Files.writeString(
            Path.of(directory).resolve("lighthouse-anchor-sphereon-root-account-leaf.json"),
            JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8,
        )
    }

    // PRIVATE insertion into LighthouseInteropTest, after the accepted baseline tests.
    // Real Sphereon KMS/admin API and pinned LightHouse resolver/admin API only.
    // Static candidate; not compiled or run.
    @Test
    fun `Sphereon signer rollover is accepted by the LightHouse resolver that takes anchor keys by reference`() {
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        var outcome = "failed"
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var initialKid: String? = null
        var rotatedKid: String? = null
        var oldEntityConfigurationVerifiedUnderRotatedJwks = false
        var rolledChainAccepted = false

        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            ensureFetchEndpoint(activeLighthouse)

            val initialEntityConfiguration = publishSphereonEntityConfiguration(activeEnvironment)
            activeEnvironment.verifyDurableState("after-initial-publication", initialEntityConfiguration.jwks)
            initialKid = initialEntityConfiguration.kid
            val originalSphereonJwt = activeEnvironment.request(
                "GET", "https://sphereon.test$ENTITY_CONFIGURATION",
            ).also { assertEquals(200, it.status) }.body
            val originalSphereon = verifySphereonConfiguration(originalSphereonJwt)
            assertEquals(initialKid, originalSphereon.kid)

            val createdAnchor = activeLighthouse.addTrustAnchor(SPHEREON, originalSphereon.jwks)
            assertEquals(201, createdAnchor.status)
            val anchorId = ((JSONObjectUtils.parse(createdAnchor.body) as Map<*, *>) ["id"] as Number)
                .toLong().toString()
            assertTrue(anchorId.matches(Regex("[0-9]+")), "LightHouse returned a non-numeric trust-anchor row id")
            assertEquals(201, activeLighthouse.addAuthorityHint(SPHEREON).status)
            assertEquals(
                201,
                activeLighthouse.registerEndpoint(
                    "resolve", "/resolve", JSONObjectUtils.toJSONString(mapOf("allowed_trust_anchors" to listOf(SPHEREON))),
                ).status,
            )

            val lighthouseJwt = activeLighthouse.public(ENTITY_CONFIGURATION).also { assertEquals(200, it.status) }.body
            val lighthouseIdentity = verifyPeerConfiguration(lighthouseJwt, LIGHTHOUSE)
            val subordinateId = createSphereonSubordinate(activeEnvironment, LIGHTHOUSE, lighthouseIdentity.jwks)
            assertEquals(201, activeEnvironment.admin("POST", "/subordinates/$subordinateId/statement").status)

            val initialResolve = activeLighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(200, initialResolve.status)
            verifyResolveResponse(
                initialResolve.body, lighthouseIdentity.jwks, LIGHTHOUSE, LIGHTHOUSE,
                mapOf(LIGHTHOUSE to lighthouseIdentity.jwks, SPHEREON to originalSphereon.jwks),
                SPHEREON to LIGHTHOUSE, SPHEREON, expectedMetadata = lighthouseIdentity.metadata,
            )

            // Create a second normal KMS key and select it through Sphereon's revision-checked admin API.
            // This is a production key-management path, not a locally generated test key.
            val createdKey = activeEnvironment.admin("POST", "/keys")
            assertEquals(201, createdKey.status)
            val newKeyId = JSONObjectUtils.getString(JSONObjectUtils.parse(createdKey.body), "id")
            val selection = activeEnvironment.admin("GET", "/keys/signing-selection")
            assertEquals(200, selection.status)
            val revision = (JSONObjectUtils.parse(selection.body)["revision"] as Number).toLong()
            assertEquals(
                200,
                activeEnvironment.admin(
                    "PUT", "/keys/signing-selection",
                    """{"expectedRevision":$revision,"selectedKeyId":"$newKeyId"}""",
                ).status,
            )
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement").status)

            // Restart Sphereon to prove the selected replacement remains usable after the production JVMs restart.
            activeEnvironment.restartSphereon()
            val rotatedJwt = activeEnvironment.request(
                "GET", "https://sphereon.test$ENTITY_CONFIGURATION",
            ).also { assertEquals(200, it.status) }.body
            val rotatedEntityConfiguration = verifySphereonConfiguration(rotatedJwt)
            rotatedKid = rotatedEntityConfiguration.kid
            assertTrue(initialKid != rotatedKid, "Sphereon Entity Configuration signer kid did not change")
            val rotatedPublicKeys = rotatedEntityConfiguration.jwks["keys"] as? List<*>
                ?: error("Sphereon Entity Configuration omitted its public signing keys")
            val rotatedKids = rotatedPublicKeys.mapNotNull { (it as? Map<*, *>)?.get("kid") as? String }.toSet()
            val previousKid = requireNotNull(initialKid)
            val selectedRotatedKid = requireNotNull(rotatedKid)
            assertTrue(previousKid in rotatedKids, "Sphereon did not advertise its previous key during rollover")
            assertTrue(selectedRotatedKid in rotatedKids, "Sphereon did not advertise its selected replacement key")

            // The exact captured pre-rotation peer EC remains valid under the live post-rotation public JWKS.
            // No compact JWT is emitted in evidence or assertion messages.
            val oldClaims = TrustedJwtVerifier.verify(
                originalSphereonJwt, ENTITY_STATEMENT_TYPE, SPHEREON, SPHEREON, rotatedEntityConfiguration.jwks,
            )
            assertEquals(SPHEREON, oldClaims["iss"])
            assertEquals(SPHEREON, oldClaims["sub"])
            oldEntityConfigurationVerifiedUnderRotatedJwks = true

            // Publish a fresh root-signed subordinate statement from the real replacement signer.
            assertEquals(201, activeEnvironment.admin("POST", "/subordinates/$subordinateId/statement").status)
            val newSubordinate = activeEnvironment.request(
                "GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}",
            ).also { assertEquals(200, it.status) }.body
            val newSubordinateClaims = TrustedJwtVerifier.verify(
                newSubordinate, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, rotatedEntityConfiguration.jwks,
            )
            assertEquals(SPHEREON, newSubordinateClaims["iss"])
            assertEquals(LIGHTHOUSE, newSubordinateClaims["sub"])
            assertEquals(selectedRotatedKid, SignedJWT.parse(newSubordinate).header.keyID)

            // The pinned LightHouse resolve endpoint takes anchor keys by reference: it builds its anchors from
            // the allowed Entity Identifiers (resolve.go, NewTrustAnchorsFromEntityIDs) and verifies the anchor's
            // own Entity Configuration, so the admin trust-anchor JWKS row does not pin a resolve result.
            // After a cold restart it must accept the real chain the replacement signer produced, and that
            // chain must verify independently under the rotated anchor JWKS.
            activeLighthouse.restart()
            val rolledResolve = activeLighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(200, rolledResolve.status)
            verifyResolveResponse(
                rolledResolve.body, lighthouseIdentity.jwks, LIGHTHOUSE, LIGHTHOUSE,
                mapOf(LIGHTHOUSE to lighthouseIdentity.jwks, SPHEREON to rotatedEntityConfiguration.jwks),
                SPHEREON to LIGHTHOUSE, SPHEREON, expectedMetadata = lighthouseIdentity.metadata,
            )
            rolledChainAccepted = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try {
                writeSphereonSignerRolloverEvidence(
                    peer, startedAt, outcome, initialKid, rotatedKid,
                    oldEntityConfigurationVerifiedUnderRotatedJwks, rolledChainAccepted,
                    environment?.artifactIdentity(),
                )
            } catch (writeFailure: Throwable) {
                cleanupFailures += writeFailure
            }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun writeSphereonSignerRolloverEvidence(
        peer: PeerLock,
        startedAt: Instant,
        outcome: String,
        initialKid: String?,
        rotatedKid: String?,
        oldEntityConfigurationVerifiedUnderRotatedJwks: Boolean,
        rolledChainAccepted: Boolean,
        sourceAndArtifactIdentity: Map<String, String>?,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val fields = linkedMapOf<String, Any?>(
            "scenario" to "sphereon-signer-rollover-lighthouse-by-reference-anchor",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "sphereonEntityId" to SPHEREON,
            "lighthouseEntityId" to LIGHTHOUSE,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
            "initialSphereonSigningKid" to initialKid,
            "rotatedSphereonSigningKid" to rotatedKid,
            "signerKidChanged" to (initialKid != null && rotatedKid != null && initialKid != rotatedKid),
            "oldEntityConfigurationVerifiedUnderRotatedJwks" to oldEntityConfigurationVerifiedUnderRotatedJwks,
            "lighthouseAnchorKeys" to "by_reference",
            "rolledChainAcceptedByLighthouse" to rolledChainAccepted,
            "sourceAndArtifactIdentity" to sourceAndArtifactIdentity,
            "claim" to if (outcome == "passed") {
                "real Sphereon signer rollover chain verified and was accepted by the LightHouse resolver after a cold restart"
            } else {
                "Sphereon signer rollover scenario did not pass"
            },
        ).filterValues { it != null }
        val output = Path.of(directory)
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("sphereon-signer-rollover-lighthouse-by-reference-anchor.json"),
            JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8,
        )
    }
}
