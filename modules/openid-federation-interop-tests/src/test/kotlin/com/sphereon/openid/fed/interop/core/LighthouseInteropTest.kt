package com.sphereon.openid.fed.interop.core

import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.SignedJWT
import com.sphereon.openid.fed.interop.harness.InteropEnvironment
import com.sphereon.openid.fed.interop.harness.InteropHttpResponse
import com.sphereon.openid.fed.interop.harness.LighthousePeer
import com.sphereon.openid.fed.interop.harness.PeerLock
import com.sphereon.openid.fed.interop.harness.PeerStatementVerifier
import com.sphereon.openid.fed.interop.harness.TrustedJwtVerifier
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
    @Test
    fun `Sphereon resolver validates a LightHouse trust anchor and its signed subordinate statement across restart`() {
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
            assertEquals(200, resolved.status)
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
            postRestartIdentity = verifySphereonConfiguration(freshEntityConfiguration.body, listOf(LIGHTHOUSE))
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

            val resolved = lighthouse.resolve(LIGHTHOUSE, SPHEREON)
            assertEquals(200, resolved.status)
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
            postRestartIdentity = verifySphereonConfiguration(freshEntityConfiguration.body)
            environment.verifyDurableState("after-restart-publication", postRestartIdentity.jwks)
            assertEquals(sphereon.kid, postRestartIdentity.kid)
            assertEquals(sphereon.canonicalPublicJwks, postRestartIdentity.canonicalPublicJwks)

            val freshSubordinate = environment.request("GET", "https://sphereon.test/fetch?sub=${url(LIGHTHOUSE)}")
            assertEquals(200, freshSubordinate.status)
            TrustedJwtVerifier.verify(freshSubordinate.body, ENTITY_STATEMENT_TYPE, SPHEREON, LIGHTHOUSE, postRestartIdentity.jwks)
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

    private fun publishSphereonEntityConfiguration(
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
                    "metadata" to mapOf("federation_resolve_endpoint" to "$SPHEREON/resolve"),
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

    private fun verifyPeerConfiguration(compact: String, entityId: String): EntityIdentity {
        val verified = PeerStatementVerifier.verify(compact, entityId)
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

    /** Include only standard error fields; never print arbitrary server bodies into test output. */
    private fun safeHttpError(body: String): String {
        val parsed = runCatching { JSONObjectUtils.parse(body) as? Map<*, *> }.getOrNull()
            ?: return "server returned an error outside the safe OAuth allowlist"
        val code = parsed["error"] as? String
            ?: return "server returned an error outside the safe OAuth allowlist"
        return if (code in SAFE_OAUTH_ERROR_CODES) "error=$code"
        else "server returned an error outside the safe OAuth allowlist"
    }

    private fun verifyResolveResponse(
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

    private data class EntityIdentity(
        val kid: String,
        val algorithm: String,
        val canonicalPublicJwks: String,
        val jwks: Map<String, Any?>,
        val issuedAt: Instant,
        val metadata: Map<String, Any?>?,
        val authorityHints: List<String>,
        val authorityHintsMatchExpected: Boolean,
    )

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
}
