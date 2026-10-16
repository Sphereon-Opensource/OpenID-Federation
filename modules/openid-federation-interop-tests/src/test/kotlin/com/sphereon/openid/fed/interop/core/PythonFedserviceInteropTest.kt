package com.sphereon.openid.fed.interop.core

import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.SignedJWT
import com.sphereon.openid.fed.interop.harness.FedserviceHttpResponse
import com.sphereon.openid.fed.interop.harness.FedservicePeer
import com.sphereon.openid.fed.interop.harness.InteropEnvironment
import com.sphereon.openid.fed.interop.harness.LighthousePeer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SPHEREON_ENTITY_ID = InteropEnvironment.PUBLIC_ENTITY_ID
private const val LIGHTHOUSE_ENTITY_ID = LighthousePeer.ENTITY_ID

/**
 * Explicit, separately selected mixed-implementation lane. Both flows require the native
 * pinned Fedservice producer's declared JWT media types and cryptographically verify every
 * signed object before producing evidence. A producer MIME failure is a test failure, never a skip.
 */
@Tag("python")
class PythonFedserviceInteropTest {
    @Test
    fun pinnedFedserviceTrustAnchorResolvesRealSphereonSubordinate() {
        val scenario = "python_trust_anchor_to_sphereon_subordinate"
        val startedAt = Instant.now()
        var outcome = "failed"
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var fedservice: FedservicePeer? = null
        val verifier = LighthouseInteropTest()
        verifier.signedObjectEvidencePhase = "python_trust_anchor_initial"
        var entityConfigurationMediaTypeAccepted = false
        var resolveResponseMediaTypeAccepted: Boolean? = false
        var resolverPassed = false
        val receiverEntityId = FedservicePeer.ENTITY_ID
        val counterpartyEntityIds = listOf(SPHEREON_ENTITY_ID)
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment, configureCounterpartyTargets = false)
                .also { lighthouse = it }
            val activeFedservice = FedservicePeer.start(activeEnvironment).also { fedservice = it }
            activeEnvironment.configureCounterpartyPrivateTargets(
                mapOf(
                    "lighthouse.test:443" to activeLighthouse.egressContainer,
                    "fedservice.test:7003" to activeFedservice.egressContainer,
                ),
            )

            val pythonEc = activeFedservice.entityConfiguration()
            requireEntityStatementResponse(pythonEc).also { entityConfigurationMediaTypeAccepted = true }
            val pythonIdentity = verifier.verifyPeerConfiguration(pythonEc.body, activeFedservice.entityId)

            // The real Sphereon root publishes the pinned Python trust anchor as its authority hint.
            val sphereonIdentity = verifier.publishSphereonEntityConfiguration(
                activeEnvironment,
                authorityHint = activeFedservice.entityId,
            )
            activeEnvironment.verifyDurableState("after-initial-publication", sphereonIdentity.jwks)
            activeFedservice.provisionSubordinate(SPHEREON_ENTITY_ID)

            val resolved = activeFedservice.resolve(SPHEREON_ENTITY_ID, activeFedservice.entityId)
            requireResolveResponse(resolved).also { resolveResponseMediaTypeAccepted = true }
            verifier.signedObjectEvidencePhase = "python_trust_anchor_resolve"
            verifier.verifyResolveResponse(
                resolved.body,
                pythonIdentity.jwks,
                activeFedservice.entityId,
                SPHEREON_ENTITY_ID,
                mapOf(activeFedservice.entityId to pythonIdentity.jwks, SPHEREON_ENTITY_ID to sphereonIdentity.jwks),
                activeFedservice.entityId to SPHEREON_ENTITY_ID,
                activeFedservice.entityId,
                sphereonIdentity.metadata,
            )
            assertEquals(
                listOf(SPHEREON_ENTITY_ID to SPHEREON_ENTITY_ID, activeFedservice.entityId to SPHEREON_ENTITY_ID),
                resolvedLinks(resolved.body),
                "Fedservice must resolve the real Sphereon EC and its actual subordinate statement to itself",
            )
            resolverPassed = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(fedservice, lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) {
                outcome = "failed"
                cleanupFailures.forEach { cleanup -> primaryFailure?.addSuppressed(cleanup) }
            }
            try {
                writeEvidence(
                    scenario, "python_trust_anchor_to_sphereon_subordinate", startedAt, outcome,
                    receiverEntityId, counterpartyEntityIds,
                    entityConfigurationMediaTypeAccepted, resolveResponseMediaTypeAccepted, resolverPassed,
                    verifier.signedObjectEvidenceRecords(),
                )
            } catch (evidenceFailure: Throwable) {
                outcome = "failed"
                if (primaryFailure != null) primaryFailure!!.addSuppressed(evidenceFailure)
                else if (cleanupFailures.isNotEmpty()) cleanupFailures.first().addSuppressed(evidenceFailure)
                else throw evidenceFailure
            }
            if (primaryFailure == null && cleanupFailures.isNotEmpty()) throw cleanupFailures.first()
        }
    }

    @Test
    fun pinnedFedserviceLeafResolvesThroughRealSphereonTrustAnchorAtLighthouse() {
        val scenario = "python_leaf_under_sphereon_resolved_by_lighthouse"
        val startedAt = Instant.now()
        var outcome = "failed"
        var primaryFailure: Throwable? = null
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var fedservice: FedservicePeer? = null
        val verifier = LighthouseInteropTest()
        verifier.signedObjectEvidencePhase = "python_leaf_initial"
        var entityConfigurationMediaTypeAccepted = false
        var resolveResponseMediaTypeAccepted: Boolean? = null
        var resolverPassed = false
        val receiverEntityId = LIGHTHOUSE_ENTITY_ID
        val counterpartyEntityIds = listOf(SPHEREON_ENTITY_ID, FedservicePeer.ENTITY_ID)
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment, configureCounterpartyTargets = false)
                .also { lighthouse = it }
            val activeFedservice = FedservicePeer.startAsSphereonSubordinate(activeEnvironment).also { fedservice = it }
            activeEnvironment.configureCounterpartyPrivateTargets(
                mapOf(
                    "lighthouse.test:443" to activeLighthouse.egressContainer,
                    "fedservice.test:7003" to activeFedservice.egressContainer,
                ),
            )
            assertEquals(201, activeLighthouse.registerEndpoint("fetch", "/fetch").status)

            // Sphereon is a real intermediate here: it advertises LightHouse and signs the Python leaf.
            val sphereonIdentity = verifier.publishSphereonEntityConfiguration(
                activeEnvironment,
                authorityHint = LIGHTHOUSE_ENTITY_ID,
            )
            activeEnvironment.verifyDurableState("after-initial-publication", sphereonIdentity.jwks)

            val pythonEc = activeFedservice.entityConfiguration()
            requireEntityStatementResponse(pythonEc).also { entityConfigurationMediaTypeAccepted = true }
            val pythonIdentity = verifier.verifyPeerConfiguration(pythonEc.body, activeFedservice.entityId)
            val subordinateId = verifier.createSphereonSubordinateUnderAccount(
                activeEnvironment, "root", activeFedservice.entityId, pythonIdentity.jwks,
            )
            assertEquals(
                201,
                activeEnvironment.admin("POST", "/subordinates/$subordinateId/statement", account = "root").status,
                "Sphereon must issue the subordinate statement through its account API",
            )

            val lighthouseToSphereon = activeLighthouse.addSubordinate(SPHEREON_ENTITY_ID, sphereonIdentity.jwks)
            assertEquals(201, lighthouseToSphereon.status)
            val lighthouseEc = activeLighthouse.public(LighthousePeer.ENTITY_CONFIGURATION_PATH)
            assertEquals(200, lighthouseEc.status)
            val lighthouseIdentity = verifier.verifyPeerConfiguration(lighthouseEc.body, LIGHTHOUSE_ENTITY_ID)

            val resolved = activeLighthouse.resolve(activeFedservice.entityId, LIGHTHOUSE_ENTITY_ID)
            assertEquals(200, resolved.status)
            verifier.signedObjectEvidencePhase = "python_leaf_lighthouse_resolve"
            verifier.verifyResolveResponse(
                resolved.body,
                lighthouseIdentity.jwks,
                LIGHTHOUSE_ENTITY_ID,
                activeFedservice.entityId,
                mapOf(
                    LIGHTHOUSE_ENTITY_ID to lighthouseIdentity.jwks,
                    SPHEREON_ENTITY_ID to sphereonIdentity.jwks,
                    activeFedservice.entityId to pythonIdentity.jwks,
                ),
                SPHEREON_ENTITY_ID to activeFedservice.entityId,
                LIGHTHOUSE_ENTITY_ID,
                pythonIdentity.metadata,
            )
            assertEquals(
                listOf(
                    activeFedservice.entityId to activeFedservice.entityId,
                    SPHEREON_ENTITY_ID to activeFedservice.entityId,
                    LIGHTHOUSE_ENTITY_ID to SPHEREON_ENTITY_ID,
                ),
                resolvedLinks(resolved.body),
                "LightHouse must verify the signed Python leaf through Sphereon to its own anchor",
            )
            resolverPassed = true
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = closeOwned(fedservice, lighthouse, environment)
            if (cleanupFailures.isNotEmpty()) {
                outcome = "failed"
                cleanupFailures.forEach { cleanup -> primaryFailure?.addSuppressed(cleanup) }
            }
            try {
                writeEvidence(
                    scenario, "python_leaf_to_sphereon_to_lighthouse", startedAt, outcome,
                    receiverEntityId, counterpartyEntityIds,
                    entityConfigurationMediaTypeAccepted, resolveResponseMediaTypeAccepted, resolverPassed,
                    verifier.signedObjectEvidenceRecords(),
                )
            } catch (evidenceFailure: Throwable) {
                outcome = "failed"
                if (primaryFailure != null) primaryFailure!!.addSuppressed(evidenceFailure)
                else if (cleanupFailures.isNotEmpty()) cleanupFailures.first().addSuppressed(evidenceFailure)
                else throw evidenceFailure
            }
            if (primaryFailure == null && cleanupFailures.isNotEmpty()) throw cleanupFailures.first()
        }
    }
}

private fun requireEntityStatementResponse(response: FedserviceHttpResponse) {
    assertEquals(200, response.status, "Fedservice Entity Configuration request must succeed")
    requireDeclaredJwtMediaType(response.contentType, "application/entity-statement+jwt", "Entity Configuration")
    assertTrue(response.body.split('.').size == 3, "Fedservice Entity Configuration must be a compact JWT")
}

private fun requireResolveResponse(response: FedserviceHttpResponse) {
    assertEquals(200, response.status, "Fedservice resolve request must succeed")
    requireDeclaredJwtMediaType(response.contentType, "application/resolve-response+jwt", "resolve response")
    assertTrue(response.body.split('.').size == 3, "Fedservice resolve response must be a compact JWT")
}

private fun requireDeclaredJwtMediaType(actual: String, expected: String, label: String) {
    val mediaType = actual.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
    assertTrue(mediaType == expected, "Fedservice $label did not use its declared JWT media type")
}

private fun resolvedLinks(compact: String): List<Pair<String, String>> {
    val chain = SignedJWT.parse(compact).jwtClaimsSet.getClaim("trust_chain") as? List<*>
        ?: error("verified resolve response omitted trust_chain")
    return chain.map { raw ->
        val statement = SignedJWT.parse(raw as? String ?: error("verified trust_chain contains a non-JWT entry"))
        (statement.jwtClaimsSet.issuer ?: error("trust-chain statement omitted iss")) to
            (statement.jwtClaimsSet.subject ?: error("trust-chain statement omitted sub"))
    }
}

private fun closeOwned(
    fedservice: FedservicePeer?,
    lighthouse: LighthousePeer?,
    environment: InteropEnvironment?,
): List<Throwable> {
    val failures = mutableListOf<Throwable>()
    try { fedservice?.close() } catch (failure: Throwable) { failures += failure }
    try { lighthouse?.close() } catch (failure: Throwable) { failures += failure }
    try { environment?.close() } catch (failure: Throwable) { failures += failure }
    return failures
}

private fun writeEvidence(
    scenario: String,
    direction: String,
    startedAt: Instant,
    outcome: String,
    receiverEntityId: String,
    counterpartyEntityIds: List<String>,
    entityConfigurationMediaTypeAccepted: Boolean,
    resolveResponseMediaTypeAccepted: Boolean?,
    resolverPassed: Boolean,
    verifiedObjects: List<Map<String, String>>,
) {
    val configuredDirectory = System.getProperty("interop.evidence.directory")
        ?: error("python lane evidence directory was not configured")
    val runId = System.getProperty("interop.evidence.runId") ?: error("python lane run ID was not configured")
    require(runId.matches(Regex("[a-fA-F0-9-]{36}"))) { "python evidence run ID is invalid" }
    val directory = Path.of(configuredDirectory).toAbsolutePath().normalize()
    val project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val expectedDirectory = project.resolve("build/interop/python").normalize()
    require(directory == expectedDirectory) {
        "python evidence must remain under the module's build/interop/python directory"
    }
    var current = project
    for (part in listOf("build", "interop", "python")) {
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current)) { "python evidence path cannot contain a symbolic link" }
        if (!Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
        require(Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "python evidence path component is not a directory"
        }
        require(current.toRealPath() == current.toAbsolutePath().normalize()) {
            "python evidence path resolves outside the module build directory"
        }
    }
    val fields = linkedMapOf<String, Any?>(
        "scenario" to scenario,
        "direction" to direction,
        "outcome" to outcome,
        "producerQualificationRequired" to true,
        "runId" to runId,
        "producerEntityConfigurationMediaTypeAccepted" to entityConfigurationMediaTypeAccepted,
        "producerResolveResponseMediaTypeAccepted" to resolveResponseMediaTypeAccepted,
        "producerSourceCommit" to FedservicePeer.UPSTREAM_SOURCE_COMMIT,
        "producerRecipe" to FedservicePeer.PRODUCER_RECIPE,
        "producerEntityId" to FedservicePeer.ENTITY_ID,
        "receiverEntityId" to receiverEntityId,
        "counterpartyEntityIds" to counterpartyEntityIds,
        "receiverResolvePassed" to resolverPassed,
        "verifiedSignedObjects" to verifiedObjects,
        "compactJwtRecorded" to false,
        "suiteScope" to "explicit_python_lane_only",
        "startedAt" to startedAt.toString(),
        "finishedAt" to Instant.now().toString(),
    )
    val output = directory.resolve("$scenario-$runId.json")
    Files.writeString(
        output,
        JSONObjectUtils.toJSONString(fields),
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE_NEW,
        java.nio.file.StandardOpenOption.WRITE,
    )
}
