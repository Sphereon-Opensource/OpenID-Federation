package com.sphereon.openid.fed.interop.core

import com.nimbusds.jose.util.JSONObjectUtils
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Separate execution lane for the pinned, unadopted GÉANT R&E pilot draft. */
@Tag("profile")
class ResearchEducationProfileInteropTest {
    @Test
    fun `real signed three entity chain satisfies pinned draft metadata object assertion`() {
        val evidenceDirectory = Path.of(
            requireNotNull(System.getProperty("interop.evidence.directory")) {
                "profile lane must configure a dedicated evidence directory"
            },
        )
        LighthouseInteropTest().runTwoLevelPolicyScenario(profileAssertions = true)

        val policyEvidencePath = evidenceDirectory.resolve("lighthouse-sphereon-two-level-metadata-policy.json")
        require(Files.isRegularFile(policyEvidencePath)) { "real policy scenario evidence was not written" }
        val policy = JSONObjectUtils.parse(Files.readString(policyEvidencePath, StandardCharsets.UTF_8))
        assertEquals("passed", policy["outcome"])
        assertEquals(true, policy["compatibleTwoLevelResolvePassed"])
        assertEquals(true, policy["bothRealSignedPolicyStatementsVerified"])
        assertEquals(true, policy["twoLevelConflictRejectedAsInvalidMetadata"])

        val objects = policy["verifiedSignedObjects"] as? List<*>
            ?: error("real policy evidence omitted verified signed objects")
        val profileEntities = listOf(
            "trust_anchor" to "edugain_re_draft_lighthouse_anchor_ec",
            "intermediate" to "edugain_re_draft_sphereon_intermediate_ec",
            "leaf" to "edugain_re_draft_leaf_ec",
        ).map { (role, phase) ->
            val record = objects.asSequence()
                .mapNotNull { it as? Map<*, *> }
                .firstOrNull { it["object"] == "entity_configuration" && it["phase"] == phase }
                ?: error("missing cryptographically verified $role Entity Configuration evidence")
            mapOf(
                "role" to role,
                "entityId" to record["subject"],
                "issuer" to record["issuer"],
                "algorithm" to record["alg"],
                "kid" to record["kid"],
                "signedObjectEvidencePhase" to phase,
                "signatureIdentityTimeAndPublicJwksVerified" to true,
                "metadataClaimIsJsonObject" to true,
            )
        }
        assertEquals(3, profileEntities.size)

        val report = linkedMapOf<String, Any?>(
            "scenario" to "research-education-pilot-draft-profile-metadata-claim",
            "outcome" to "passed",
            "profileStatus" to "pinned pilot draft; not an adopted production profile",
            "source" to mapOf(
                "repository" to "GEANT/edugain-openidfed",
                "commit" to "589f2aa18a695d2b9a64e04b9146d7f7f2630c8b",
                "path" to "OpenID4RS-OpenIDFederation.md",
                "blob" to "bc098ad643eed8020d88db53990f9e54b5352865",
                "downloadedFileSha256" to "c9dccda510e257e4d683e472d56940456e02e57b4acf66bd261343c428b6c38d",
                "requirementLocation" to "common Entity Configuration table, lines 191-200 at pinned commit",
            ),
            "requirementsToEvidence" to listOf(
                mapOf(
                    "requirement" to "The common Entity Configuration metadata claim is required and is a JSON object",
                    "test" to "ResearchEducationProfileVerifier.verify, after PeerStatementVerifier verifies the signed statement",
                    "evidenceField" to "entities[].metadataClaimIsJsonObject",
                    "assertedFor" to listOf("trust anchor", "intermediate", "leaf"),
                ),
            ),
            "entities" to profileEntities,
            "realTopology" to mapOf(
                "trustAnchor" to "LightHouse",
                "intermediate" to "Sphereon root account",
                "leaf" to "Sphereon tenant account",
                "compatibleTwoLevelPolicyResolution" to true,
                "realSignedConflictingPolicyRejectedAsInvalidMetadata" to true,
                "receiverCacheBoundary" to "Sphereon process restart before resolution",
            ),
            "scope" to mapOf(
                "genericVerifierRemainsProfileAgnostic" to true,
                "metadataValuesInventedOrAsserted" to false,
                "mandatoryTrustMarkClaimed" to false,
                "warmCacheInvalidationTested" to false,
            ),
            "sourcePolicyEvidence" to policyEvidencePath.fileName.toString(),
            "compactJwtRecorded" to false,
        )
        Files.createDirectories(evidenceDirectory)
        Files.writeString(
            evidenceDirectory.resolve("research-education-pilot-draft-profile.json"),
            JSONObjectUtils.toJSONString(report),
            StandardCharsets.UTF_8,
        )
        assertTrue(Files.isRegularFile(evidenceDirectory.resolve("research-education-pilot-draft-profile.json")))
    }
}
