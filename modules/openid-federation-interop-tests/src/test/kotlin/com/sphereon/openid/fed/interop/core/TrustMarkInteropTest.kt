package com.sphereon.openid.fed.interop.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.SignedJWT
import com.sphereon.openid.fed.interop.harness.InteropEnvironment
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Private candidate; apply only after the existing bidirectional core test passes. */
@Tag("core")
class TrustMarkInteropTest {
    @Test
    fun `Sphereon resolver includes active LightHouse trust mark then filters it after issuer revocation and restart`() {
        val peer = PeerLock.load("lighthouse")
        val signedObjects = mutableListOf<Map<String, String>>()
        val statusEvidence = mutableListOf<Map<String, String>>()
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var outcome = "failed"
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            val type = "https://interop.example/marks/core-test"
            assertEquals(201, activeLighthouse.registerEndpoint("fetch", "/fetch").status)
            assertEquals(201, activeLighthouse.registerEndpoint("trust_mark", "/trust-mark").status)
            assertEquals(201, activeLighthouse.registerEndpoint("trust_mark_status", "/trust-mark-status").status)

            val typeResponse = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/types/", mapOf("trust_mark_type" to type),
            )
            assertEquals(201, typeResponse.status)
            val typeId = JSONObjectUtils.parse(typeResponse.body)["id"].toString()
            val issuerResponse = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/types/$typeId/issuers",
                mapOf("issuer" to LIGHTHOUSE),
            )
            assertEquals(201, issuerResponse.status)

            val specResponse = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/issuance-spec",
                mapOf(
                    "trust_mark_type" to type,
                    "lifetime" to 3600,
                    "eligibility_config" to mapOf("mode" to "db_only"),
                ),
            )
            assertEquals(201, specResponse.status)
            val specId = JSONObjectUtils.parse(specResponse.body)["id"].toString()
            val subjectResponse = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/issuance-spec/$specId/subjects",
                mapOf("entity_id" to SPHEREON, "status" to "active"),
            )
            assertEquals(201, subjectResponse.status)
            val subjectId = JSONObjectUtils.parse(subjectResponse.body)["id"].toString()

            // Publish a real Sphereon key and signed Entity Configuration before Lighthouse records its subordinate.
            val sphereon = publishSphereonEntityConfiguration(activeEnvironment)
            assertEquals(201, activeLighthouse.addSubordinate(SPHEREON, sphereon.jwks).status)
            val lighthouseEcJwt = activeLighthouse.public(LighthousePeer.ENTITY_CONFIGURATION_PATH).also {
                assertEquals(200, it.status)
            }.body
            val lighthouseEc = PeerStatementVerifier.verify(lighthouseEcJwt, LIGHTHOUSE)
            assertEquals(LIGHTHOUSE, lighthouseEc.issuer)
            signedObjects += safeObject(lighthouseEcJwt, "entity_configuration", "initial")
            val lighthouseClaims = SignedJWT.parse(lighthouseEcJwt).jwtClaimsSet
            val lighthouseJwks = JSONObjectUtils.getJSONObject(lighthouseClaims.toJSONObject(), "jwks")
            val recognized = lighthouseClaims.getClaim("trust_mark_issuers") as? Map<*, *>
                ?: error("LightHouse trust anchor Entity Configuration omitted trust_mark_issuers")
            val allowedIssuers = recognized[type] as? List<*>
                ?: error("LightHouse Entity Configuration omitted issuer list for the provisioned mark type")
            assertTrue(LIGHTHOUSE in allowedIssuers, "LightHouse did not publish itself as an authorized mark issuer")
            val endpointMetadata = lighthouseClaims.getClaim("metadata") as? Map<*, *>
                ?: error("LightHouse Entity Configuration omitted metadata")
            val federationEntity = endpointMetadata["federation_entity"] as? Map<*, *>
                ?: error("LightHouse Entity Configuration omitted federation_entity metadata")
            assertEquals("$LIGHTHOUSE/trust-mark-status", federationEntity["federation_trust_mark_status_endpoint"])

            val issued = activeEnvironment.request(
                "GET", "https://lighthouse.test/trust-mark?sub=${url(SPHEREON)}&trust_mark_type=${url(type)}",
            )
            assertEquals(200, issued.status)
            val mark = issued.body
            val markClaims = TrustedJwtVerifier.verify(mark, "trust-mark+jwt", LIGHTHOUSE, SPHEREON, lighthouseJwks)
            assertEquals(type, markClaims["trust_mark_type"])
            assertTrue((markClaims["jti"] as? String)?.isNotBlank() == true, "issued mark lacks lifecycle JTI")
            signedObjects += safeObject(mark, "trust_mark", "active")

            // The status JWT is checked before the mark is stored or used by the real Sphereon resolver.
            val activeStatus = status(activeEnvironment, mark, lighthouseJwks, "active")
            assertTrue(activeStatus["kid"]?.isNotBlank() == true, "active trust-mark status omitted its signing key id")
            statusEvidence += activeStatus
            val received = activeEnvironment.admin(
                "POST", "/received-trust-marks",
                JSONObjectUtils.toJSONString(mapOf("trust_mark_type" to type, "jwt" to mark)),
            )
            assertEquals(201, received.status)
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement").status)

            val first = resolveSphereon(activeEnvironment)
            assertEquals(200, first.status, "Sphereon GET /resolve returned HTTP ${first.status}: ${LighthouseInteropTest().safeHttpError(first.body)}")
            val firstClaims = TrustedJwtVerifier.verify(first.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks)
            val firstMarks = firstClaims["trust_marks"] as? List<*> ?: error("resolve response omitted trust_marks")
            assertTrue(firstMarks.any { (it as? Map<*, *>)?.get("trust_mark") == mark })
            signedObjects += safeObject(first.body, "resolve_response", "active")

            val revoke = activeLighthouse.adminText(
                "PUT", "/api/v1/admin/trust-marks/issuance-spec/$specId/subjects/$subjectId/status", "blocked",
            )
            assertEquals(200, revoke.status)
            val revokedStatus = status(activeEnvironment, mark, lighthouseJwks, "revoked")
            assertEquals(activeStatus["alg"], revokedStatus["alg"])
            statusEvidence += revokedStatus

            // Restart the production receiver to clear process caches, then publish a fresh EC and resolve again.
            activeEnvironment.restartSphereon()
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement").status)
            val second = resolveSphereon(activeEnvironment)
            assertEquals(200, second.status, "Sphereon GET /resolve returned HTTP ${second.status}: ${LighthouseInteropTest().safeHttpError(second.body)}")
            val secondClaims = TrustedJwtVerifier.verify(second.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks)
            val secondMarks = secondClaims["trust_marks"] as? List<*> ?: error("resolve response omitted trust_marks")
            assertFalse(secondMarks.any { (it as? Map<*, *>)?.get("trust_mark") == mark },
                "Sphereon resolver returned a recognized mark whose issuer now reports revoked")
            signedObjects += safeObject(second.body, "resolve_response", "revoked_after_restart")
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            try { lighthouse?.close() } catch (failure: Throwable) { cleanupFailures += failure }
            try { environment?.close() } catch (failure: Throwable) { cleanupFailures += failure }
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try { writeEvidence(peer, outcome, signedObjects, statusEvidence) }
            catch (failure: Throwable) { cleanupFailures += failure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun status(
        environment: InteropEnvironment,
        compactMark: String,
        lighthouseJwks: Map<String, Any>,
        expected: String,
    ): Map<String, String> {
        val response = environment.requestForm(
            "POST", "https://lighthouse.test/trust-mark-status", mapOf("trust_mark" to compactMark),
        )
        assertEquals(200, response.status)
        val jwt = SignedJWT.parse(response.body)
        assertEquals("trust-mark-status-response+jwt", jwt.header.type?.type)
        val kid = requireNotNull(jwt.header.keyID).also { assertTrue(it.isNotBlank()) }
        val claims = jwt.jwtClaimsSet
        assertEquals(LIGHTHOUSE, claims.issuer)
        assertTrue(claims.getStringClaim("trust_mark") == compactMark, "status response did not echo the submitted mark")
        assertEquals(expected, claims.getStringClaim("status"))
        val now = Instant.now().epochSecond
        val issuedAt = requireNotNull(claims.issueTime).toInstant().epochSecond
        assertTrue(issuedAt <= now + 5, "status JWT iat is in the future")
        // Pinned LightHouse 20c4a4e uses {iss, iat, trust_mark, status}; it intentionally has no exp/aud/sub.
        assertFalse(claims.claims.containsKey("exp"), "unexpected status format drift; review before changing verifier")
        assertFalse(claims.claims.containsKey("aud"))
        assertFalse(claims.claims.containsKey("sub"))
        val key = publicJwk(lighthouseJwks, kid)
        val valid = when (jwt.header.algorithm) {
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512 ->
                (key as? RSAKey)?.toRSAPublicKey()?.let { jwt.verify(RSASSAVerifier(it)) } ?: false
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 ->
                (key as? ECKey)?.toECPublicKey()?.let { jwt.verify(ECDSAVerifier(it)) } ?: false
            else -> false
        }
        assertTrue(valid, "status JWT signature did not verify with the published LightHouse key")
        return mapOf("object" to "trust_mark_status_response", "issuer" to LIGHTHOUSE, "status" to expected, "kid" to kid, "alg" to jwt.header.algorithm.name, "hasExp" to claims.claims.containsKey("exp").toString(), "phase" to expected)
    }


    /** Call only after the relevant verifier has accepted the compact JWT; persist allowlisted header/identity fields. */
    private fun safeObject(compact: String, kind: String, phase: String): Map<String, String> {
        val jwt = SignedJWT.parse(compact)
        return linkedMapOf(
            "object" to kind,
            "issuer" to requireNotNull(jwt.jwtClaimsSet.issuer),
            "subject" to requireNotNull(jwt.jwtClaimsSet.subject),
            "alg" to jwt.header.algorithm.name,
            "kid" to requireNotNull(jwt.header.keyID),
            "phase" to phase,
        )
    }

    private fun writeEvidence(
        peer: PeerLock,
        outcome: String,
        signedObjects: List<Map<String, String>>,
        statusEvidence: List<Map<String, String>>,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val output = Path.of(directory).toAbsolutePath().normalize()
        Files.createDirectories(output)
        val document = mapOf(
            "scenario" to "lighthouse-trustmark-lifecycle",
            "outcome" to outcome,
            "peer" to peer.name,
            "peerSourceCommit" to peer.commit,
            "peerImageDigest" to peer.image.substringAfter("@sha256:"),
            "verifiedSignedObjects" to signedObjects,
            "verifiedStatusResponses" to statusEvidence,
            "compactJwtRecorded" to false,
            "claim" to "active issuer mark was returned; revoked mark was filtered after receiver restart",
        )
        Files.writeString(
            output.resolve("lighthouse-trustmark-lifecycle-${System.currentTimeMillis()}.json"),
            JSONObjectUtils.toJSONString(document), StandardCharsets.UTF_8,
        )
    }
    private fun publicJwk(jwks: Map<String, Any>, kid: String): JWK {
        val entries = jwks["keys"] as? List<*> ?: error("LightHouse JWKS omitted keys")
        val selected = entries.map { it as? Map<*, *> ?: error("invalid public JWK") }
            .single { it["kid"] == kid }
        require(selected.keys.none { it?.toString() in setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k") })
        return JWK.parse(selected.entries.associate { it.key.toString() to it.value })
    }

    private fun publishSphereonEntityConfiguration(environment: InteropEnvironment): SphereonIdentity {
        val created = environment.admin("POST", "/keys")
        assertEquals(201, created.status)
        val keyId = JSONObjectUtils.getString(JSONObjectUtils.parse(created.body), "id")
        environment.rememberSelectedKeyId(keyId)
        val current = environment.admin("GET", "/keys/signing-selection")
        assertEquals(200, current.status, "Sphereon GET /resolve returned HTTP ${current.status}: ${LighthouseInteropTest().safeHttpError(current.body)}")
        val revision = (JSONObjectUtils.parse(current.body)["revision"] as Number).toLong()
        assertEquals(200, environment.admin(
            "PUT", "/keys/signing-selection", """{"expectedRevision":$revision,"selectedKeyId":"$keyId"}""",
        ).status)
        assertEquals(201, environment.admin("POST", "/authority-hints", """{"identifier":"$LIGHTHOUSE"}""").status)
        // OpenID Federation 1.1 section 3.1.1: an Entity Configuration declares the Entity Types it takes part with.
        assertEquals(201, environment.admin(
            "POST", "/metadata", """{"key":"federation_entity","metadata":{"organization_name":"Sphereon interop trust mark holder"}}""",
        ).status)
        assertEquals(201, environment.admin("POST", "/entity-statement").status)
        val config = environment.request("GET", "https://sphereon.test/.well-known/openid-federation")
        assertEquals(200, config.status)
        val verified = PeerStatementVerifier.verify(config.body, SPHEREON)
        val claims = SignedJWT.parse(config.body).jwtClaimsSet
        return SphereonIdentity(JSONObjectUtils.getJSONObject(claims.toJSONObject(), "jwks"), verified.kid)
    }

    private fun resolveSphereon(environment: InteropEnvironment) = environment.request(
        "GET", "https://sphereon.test/resolve?sub=${url(SPHEREON)}&trust_anchor=${url(LIGHTHOUSE)}",
    )

    private fun url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private data class SphereonIdentity(val jwks: Map<String, Any>, val kid: String)

    private companion object {
        const val LIGHTHOUSE = "https://lighthouse.test"
        const val SPHEREON = InteropEnvironment.PUBLIC_ENTITY_ID
    }

    // Candidate insertion inside TrustMarkInteropTest. It requires the existing v7
    // imports/helpers and the pinned Lighthouse lifecycle admin adapter.

    @Test
    fun `Sphereon resolve accepts a leaf without marks and filters an unauthorized real issuer mark`() {
        val peer = PeerLock.load("lighthouse")
        val verifiedObjects = mutableListOf<Map<String, String>>()
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var outcome = "failed"
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            val type = "https://interop.example/marks/issuer-filter"
            assertEquals(201, activeLighthouse.registerEndpoint("fetch", "/fetch").status)
            assertEquals(201, activeLighthouse.registerEndpoint("trust_mark", "/trust-mark").status)

            val typeResponse = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/types/", mapOf("trust_mark_type" to type),
            )
            assertEquals(201, typeResponse.status)
            val typeId = JSONObjectUtils.parse(typeResponse.body)["id"].toString()
            assertEquals(201, activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/types/$typeId/issuers", mapOf("issuer" to LIGHTHOUSE),
            ).status)
            val issuance = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/issuance-spec",
                mapOf("trust_mark_type" to type, "lifetime" to 3600, "eligibility_config" to mapOf("mode" to "db_only")),
            )
            assertEquals(201, issuance.status)
            val specId = JSONObjectUtils.parse(issuance.body)["id"].toString()
            val subject = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/issuance-spec/$specId/subjects",
                mapOf("entity_id" to SPHEREON, "status" to "active"),
            )
            assertEquals(201, subject.status)

            val sphereon = publishSphereonEntityConfiguration(activeEnvironment)
            val relationship = activeLighthouse.addSubordinate(SPHEREON, sphereon.jwks)
            assertEquals(201, relationship.status)
            val lighthouseJwt = activeLighthouse.public(LighthousePeer.ENTITY_CONFIGURATION_PATH).also {
                assertEquals(200, it.status)
            }.body
            PeerStatementVerifier.verify(lighthouseJwt, LIGHTHOUSE)
            val lighthouseClaims = SignedJWT.parse(lighthouseJwt).jwtClaimsSet
            val lighthouseJwks = JSONObjectUtils.getJSONObject(lighthouseClaims.toJSONObject(), "jwks")
            verifiedObjects += safeObject(lighthouseJwt, "entity_configuration", "authorized_issuer_configuration")

            // OpenID Federation does not make trust marks mandatory. A fresh real resolve
            // without any leaf mark must succeed and carry no trust_marks entries.
            val withoutMark = resolveSphereon(activeEnvironment)
            assertEquals(200, withoutMark.status, "Sphereon GET /resolve returned HTTP ${withoutMark.status}: ${LighthouseInteropTest().safeHttpError(withoutMark.body)}")
            val withoutMarkClaims = TrustedJwtVerifier.verify(
                withoutMark.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks,
            )
            assertTrue((withoutMarkClaims["trust_marks"] as? List<*>)?.isEmpty() != false)
            verifiedObjects += safeObject(withoutMark.body, "resolve_response", "no_optional_mark")

            val issued = activeEnvironment.request(
                "GET", "https://lighthouse.test/trust-mark?sub=${url(SPHEREON)}&trust_mark_type=${url(type)}",
            )
            assertEquals(200, issued.status)
            val actualMark = issued.body
            val markClaims = TrustedJwtVerifier.verify(actualMark, "trust-mark+jwt", LIGHTHOUSE, SPHEREON, lighthouseJwks)
            assertEquals(type, markClaims["trust_mark_type"])
            verifiedObjects += safeObject(actualMark, "trust_mark", "real_issuer_light_house")
            assertEquals(201, activeEnvironment.admin(
                "POST", "/received-trust-marks",
                JSONObjectUtils.toJSONString(mapOf("trust_mark_type" to type, "jwt" to actualMark)),
            ).status)
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement").status)
            // Admin and public servers are separate processes; a republish invalidates only co-located resolver
            // caches, so the earlier resolve's copy of this Entity Configuration lives until its exp. Restart the
            // real servers (same PostgreSQL/PKCS12 state) so the resolve reads the republished configuration.
            activeEnvironment.restartSphereon()

            val accepted = resolveSphereon(activeEnvironment)
            assertEquals(200, accepted.status, "Sphereon GET /resolve returned HTTP ${accepted.status}: ${LighthouseInteropTest().safeHttpError(accepted.body)}")
            val acceptedClaims = TrustedJwtVerifier.verify(
                accepted.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks,
            )
            assertTrue((acceptedClaims["trust_marks"] as? List<*>)
                ?.any { (it as? Map<*, *>)?.get("trust_mark") == actualMark } == true)
            verifiedObjects += safeObject(accepted.body, "resolve_response", "authorized_mark_included")

            // Keep the type recognized, but replace its non-empty issuer allow-list with
            // a different real URI. An empty list would mean anyone may issue and would
            // not exercise TrustMarkIssuerNotAuthorizedError.
            val issuerPolicy = activeLighthouse.replaceTrustMarkIssuers(
                typeId, listOf("https://other-issuer.interop.example"),
            )
            assertEquals(200, issuerPolicy.status)
            val updatedLighthouseJwt = activeLighthouse.public(LighthousePeer.ENTITY_CONFIGURATION_PATH).also {
                assertEquals(200, it.status)
            }.body
            PeerStatementVerifier.verify(updatedLighthouseJwt, LIGHTHOUSE)
            val updatedPayload = SignedJWT.parse(updatedLighthouseJwt).jwtClaimsSet.toJSONObject()
            val updatedIssuerMap = updatedPayload["trust_mark_issuers"] as? Map<*, *>
                ?: error("updated LightHouse EC omitted trust_mark_issuers")
            val configuredIssuers = updatedIssuerMap[type] as? List<*>
                ?: error("updated LightHouse EC omitted the configured mark type")
            assertEquals(listOf("https://other-issuer.interop.example"), configuredIssuers)
            assertFalse(LIGHTHOUSE in configuredIssuers)
            verifiedObjects += safeObject(updatedLighthouseJwt, "entity_configuration", "issuer_allowlist_replaced")

            activeEnvironment.restartSphereon()
            val filtered = resolveSphereon(activeEnvironment)
            assertEquals(200, filtered.status, "Sphereon GET /resolve returned HTTP ${filtered.status}: ${LighthouseInteropTest().safeHttpError(filtered.body)}")
            val filteredClaims = TrustedJwtVerifier.verify(
                filtered.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks,
            )
            val filteredMarks = filteredClaims["trust_marks"] as? List<*> ?: emptyList<Any>()
            assertFalse(filteredMarks.any { (it as? Map<*, *>)?.get("trust_mark") == actualMark })
            verifiedObjects += safeObject(filtered.body, "resolve_response", "unauthorized_real_issuer_filtered")
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (primaryFailure != null) {
                try { environment?.retainSafeServerErrorLogs("trustmark-filtering") }
                catch (diagnosticFailure: Throwable) { primaryFailure!!.addSuppressed(diagnosticFailure) }
            }
            val cleanupFailures = mutableListOf<Throwable>()
            try { lighthouse?.close() } catch (failure: Throwable) { cleanupFailures += failure }
            try { environment?.close() } catch (failure: Throwable) { cleanupFailures += failure }
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try { writeFilterEvidence(peer, outcome, verifiedObjects) }
            catch (failure: Throwable) { cleanupFailures += failure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun writeFilterEvidence(
        peer: PeerLock,
        outcome: String,
        verifiedObjects: List<Map<String, String>>,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val output = Path.of(directory).toAbsolutePath().normalize()
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("lighthouse-sphereon-trustmark-filtering.json"),
            JSONObjectUtils.toJSONString(mapOf(
                "scenario" to "optional-and-unauthorized-trust-mark-filtering",
                "outcome" to outcome,
                "peer" to peer.name,
                "peerSourceCommit" to peer.commit,
                "peerImageDigest" to peer.image.substringAfter("@sha256:"),
                "verifiedSignedObjects" to verifiedObjects,
                "compactJwtRecorded" to false,
                "missingMarkSemantics" to "successful_resolve_with_empty_trust_marks",
                "unauthorizedIssuerSemantics" to "successful_resolve_with_mark_filtered",
            )),
            StandardCharsets.UTF_8,
        )
    }

    // Candidate extra @Test method inside TrustMarkInteropTest. This is separate
    // from the unauthorized-issuer method so each outcome remains attributable.

    @Test
    fun `Sphereon resolver omits a real product-signed trust mark after its exp`() {
        val peer = PeerLock.load("lighthouse")
        val verifiedObjects = mutableListOf<Map<String, String>>()
        var environment: InteropEnvironment? = null
        var lighthouse: LighthousePeer? = null
        var primaryFailure: Throwable? = null
        var outcome = "failed"
        var expiredAfter: Long? = null
        try {
            val activeEnvironment = InteropEnvironment.start().also { environment = it }
            val activeLighthouse = LighthousePeer.start(activeEnvironment).also { lighthouse = it }
            assertEquals(201, activeLighthouse.registerEndpoint("fetch", "/fetch").status)

            // Lighthouse recognizes this type and authorizes the real Sphereon root
            // account as issuer. The mark itself is signed by the product's selected key.
            val type = "https://interop.example/marks/short-lived"
            val lighthouseType = activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/types/", mapOf("trust_mark_type" to type),
            )
            assertEquals(201, lighthouseType.status)
            val lighthouseTypeId = JSONObjectUtils.parse(lighthouseType.body)["id"].toString()
            assertEquals(201, activeLighthouse.admin(
                "POST", "/api/v1/admin/trust-marks/types/$lighthouseTypeId/issuers", mapOf("issuer" to SPHEREON),
            ).status)

            val sphereon = publishSphereonEntityConfiguration(activeEnvironment)
            assertEquals(201, activeLighthouse.addSubordinate(SPHEREON, sphereon.jwks).status)
            val lighthouseJwt = activeLighthouse.public(LighthousePeer.ENTITY_CONFIGURATION_PATH).also {
                assertEquals(200, it.status)
            }.body
            PeerStatementVerifier.verify(lighthouseJwt, LIGHTHOUSE)
            val lighthouseJwks = JSONObjectUtils.getJSONObject(
                SignedJWT.parse(lighthouseJwt).jwtClaimsSet.toJSONObject(), "jwks",
            )
            val markIssuerMap = SignedJWT.parse(lighthouseJwt).jwtClaimsSet.toJSONObject()["trust_mark_issuers"] as? Map<*, *>
                ?: error("LightHouse EC omitted trust_mark_issuers")
            assertEquals(listOf(SPHEREON), markIssuerMap[type])
            verifiedObjects += safeObject(lighthouseJwt, "entity_configuration", "mark_issuer_configuration")

            val withoutMark = resolveSphereon(activeEnvironment)
            assertEquals(200, withoutMark.status, "Sphereon GET /resolve returned HTTP ${withoutMark.status}: ${LighthouseInteropTest().safeHttpError(withoutMark.body)}")
            val withoutMarkClaims = TrustedJwtVerifier.verify(
                withoutMark.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks,
            )
            assertTrue((withoutMarkClaims["trust_marks"] as? List<*>)?.isEmpty() != false)
            verifiedObjects += safeObject(withoutMark.body, "resolve_response", "before_mark_attachment")

            val typeResponse = activeEnvironment.admin(
                "POST", "/trust-mark-types",
                JSONObjectUtils.toJSONString(mapOf("identifier" to type)),
                account = "root",
            )
            assertEquals(201, typeResponse.status)
            val expiresAt = (Instant.now().epochSecond + 120).toDouble()
            val createMark = activeEnvironment.admin(
                "POST", "/trust-marks",
                JSONObjectUtils.toJSONString(mapOf(
                    "sub" to SPHEREON,
                    "trust_mark_type" to type,
                    "exp" to expiresAt,
                )),
                account = "root",
            )
            assertEquals(201, createMark.status)
            val mark = JSONObjectUtils.getString(JSONObjectUtils.parse(createMark.body), "trust_mark_value")
            val markClaims = TrustedJwtVerifier.verify(mark, "trust-mark+jwt", SPHEREON, SPHEREON, sphereon.jwks)
            assertEquals(type, markClaims["trust_mark_type"])
            assertTrue((markClaims["exp"] as Number).toDouble() == expiresAt)
            verifiedObjects += safeObject(mark, "trust_mark", "product_signed_short_lived")

            assertEquals(201, activeEnvironment.admin(
                "POST", "/received-trust-marks",
                JSONObjectUtils.toJSONString(mapOf("trust_mark_type" to type, "jwt" to mark)),
                account = "root",
            ).status)
            assertEquals(201, activeEnvironment.admin("POST", "/entity-statement", account = "root").status)
            // Admin and public servers are separate processes; a republish invalidates only co-located resolver
            // caches, so the earlier resolve's copy of this Entity Configuration lives until its exp. Restart the
            // real servers (same PostgreSQL/PKCS12 state) so the resolve reads the republished configuration.
            activeEnvironment.restartSphereon()
            val current = resolveSphereon(activeEnvironment)
            assertEquals(200, current.status, "Sphereon GET /resolve returned HTTP ${current.status}: ${LighthouseInteropTest().safeHttpError(current.body)}")
            val currentClaims = TrustedJwtVerifier.verify(
                current.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks,
            )
            assertTrue((currentClaims["trust_marks"] as? List<*>)
                ?.any { (it as? Map<*, *>)?.get("trust_mark") == mark } == true)
            verifiedObjects += safeObject(current.body, "resolve_response", "short_lived_mark_current")

            // The preceding real resolve proves inclusion while the signed exp still has
            // a minimum 20-second margin. Then wait through the peer's five-second skew
            // using a monotonic bound so wall-clock changes cannot make this unbounded.
            val remainingAtPositiveResolve = expiresAt.toLong() - Instant.now().epochSecond
            assertTrue(remainingAtPositiveResolve >= 20, "mark expired before the positive resolve completed")
            val expiryBoundary = expiresAt.toLong() + 7
            val secondsUntilBoundary = (expiryBoundary - Instant.now().epochSecond).coerceAtLeast(0) + 25
            val monotonicDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(secondsUntilBoundary)
            while (Instant.now().epochSecond <= expiryBoundary) {
                check(System.nanoTime() < monotonicDeadline) { "real trust mark did not reach its bounded expiry boundary" }
                Thread.sleep(250)
            }
            expiredAfter = Instant.now().epochSecond
            activeEnvironment.restartSphereon()
            val expired = resolveSphereon(activeEnvironment)
            assertEquals(200, expired.status, "Sphereon GET /resolve returned HTTP ${expired.status}: ${LighthouseInteropTest().safeHttpError(expired.body)}")
            val expiredClaims = TrustedJwtVerifier.verify(
                expired.body, "resolve-response+jwt", SPHEREON, SPHEREON, sphereon.jwks,
            )
            val remaining = expiredClaims["trust_marks"] as? List<*> ?: emptyList<Any>()
            assertFalse(remaining.any { (it as? Map<*, *>)?.get("trust_mark") == mark })
            verifiedObjects += safeObject(expired.body, "resolve_response", "expired_mark_filtered_after_restart")
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            try { lighthouse?.close() } catch (failure: Throwable) { cleanupFailures += failure }
            try { environment?.close() } catch (failure: Throwable) { cleanupFailures += failure }
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            try { writeExpiryEvidence(peer, outcome, expiresAfter = expiredAfter, verifiedObjects = verifiedObjects) }
            catch (failure: Throwable) { cleanupFailures += failure }
            if (primaryFailure != null) cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun writeExpiryEvidence(
        peer: PeerLock,
        outcome: String,
        expiresAfter: Long?,
        verifiedObjects: List<Map<String, String>>,
    ) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val output = Path.of(directory).toAbsolutePath().normalize()
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("lighthouse-sphereon-expired-trustmark.json"),
            JSONObjectUtils.toJSONString(mapOf(
                "scenario" to "expired-real-product-signed-trust-mark-filtering",
                "outcome" to outcome,
                "peer" to peer.name,
                "peerSourceCommit" to peer.commit,
                "peerImageDigest" to peer.image.substringAfter("@sha256:"),
                "resolverExpirySkewSeconds" to 5,
                "receiverRestartBeforeExpiredResolve" to true,
                "expiredObservedAtEpochSeconds" to expiresAfter,
                "verifiedSignedObjects" to verifiedObjects,
                "compactJwtRecorded" to false,
            ).filterValues { it != null }),
            StandardCharsets.UTF_8,
        )
    }
}
