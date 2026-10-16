package com.sphereon.openid.fed.interop.ofcli

import com.nimbusds.jose.util.JSONObjectUtils
import com.sphereon.openid.fed.interop.core.LighthouseInteropTest
import com.sphereon.openid.fed.interop.harness.InteropEnvironment
import com.sphereon.openid.fed.interop.harness.LighthousePeer
import com.sphereon.openid.fed.interop.harness.OfcliStructuralEvidence
import com.sphereon.openid.fed.interop.harness.OfcliStructuralProbe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/** Structural discovery supplement to the real Nimbus-verified Sphereon/LightHouse scenario. */
@Tag("ofcli")
class OfcliStructuralInteropTest {
    @Test
    fun `source pinned OFCLI discovers the real three entity chain after Nimbus verification`() {
        val startedAt = Instant.now()
        val runId = UUID.randomUUID().toString()
        var summary: OfcliStructuralEvidence? = null
        var nimbusVerifiedBeforeProbe = false
        var outcome = "failed"
        var primaryFailure: Throwable? = null
        try {
            LighthouseInteropTest().runTwoLevelPolicyScenario(structuralProbe = { environment, leafEntityId ->
                nimbusVerifiedBeforeProbe = true
                val expectedPath = listOf(leafEntityId, InteropEnvironment.PUBLIC_ENTITY_ID, LighthousePeer.ENTITY_ID)
                summary = OfcliStructuralProbe.start(environment).use { probe ->
                    probe.inspect(
                        startingEntity = leafEntityId,
                        expectedPath = expectedPath,
                        servedEntityAnchorControl = leafEntityId,
                    )
                }
            })
            checkNotNull(summary) { "OFCLI probe was not reached after the verified resolver scenario" }
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                writeEvidence(runId, startedAt, outcome, nimbusVerifiedBeforeProbe, summary)
            } catch (evidenceFailure: Throwable) {
                if (primaryFailure != null) primaryFailure!!.addSuppressed(evidenceFailure) else throw evidenceFailure
            }
        }
    }

    private fun writeEvidence(
        runId: String,
        startedAt: Instant,
        outcome: String,
        nimbusVerifiedBeforeProbe: Boolean,
        result: OfcliStructuralEvidence?,
    ) {
        val configured = System.getProperty("interop.evidence.directory")
            ?: error("OFCLI evidence directory was not configured")
        val directory = Path.of(configured).toAbsolutePath().normalize()
        require(directory.fileName.toString() == "ofcli" && directory.parent.fileName.toString() == "interop") {
            "OFCLI evidence must use the dedicated build/interop/ofcli lane directory"
        }
        Files.createDirectories(directory)
        var current: Path? = directory
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "OFCLI evidence path contains a symbolic link" }
            current = current.parent
        }
        require(directory.toRealPath() == directory) { "OFCLI evidence path is not canonical" }

        val fields = linkedMapOf<String, Any?>(
            "scenario" to "ofcli-structural-three-entity-mixed-chain",
            "runId" to runId,
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
            "outcome" to outcome,
            "tool" to mapOf(
                "name" to OfcliStructuralProbe.TOOL_NAME,
                "version" to OfcliStructuralProbe.TOOL_VERSION,
                "sourceCommit" to OfcliStructuralProbe.TOOL_COMMIT,
            ),
            "startingEntity" to result?.startingEntity,
            "selectedAnchor" to result?.selectedAnchor,
            "expectedPath" to result?.expectedPath,
            "observedLinks" to result?.observedLinks?.map { (issuer, subject) -> mapOf("iss" to issuer, "sub" to subject) },
            "chainLength" to result?.chainLength,
            "positiveStructuralMatch" to (result != null),
            "servedEntityAnchorControl" to result?.servedEntityAnchorControl,
            "negativeControlKind" to OfcliStructuralProbe.NEGATIVE_KIND,
            "negativeControlEntityMatchesStartingEntity" to (result?.servedEntityAnchorControl == result?.startingEntity),
            "negativeControlStatus" to result?.negativeControlStatus,
            "nimbusResolveVerifiedBeforeProbe" to nimbusVerifiedBeforeProbe,
            "signatureValidationByOfcli" to false,
            "metadataPolicyOracle" to false,
            "metadataDirectedTlsUnproven" to true,
            "rawCliOutputRetained" to false,
            "compactJwtRecorded" to false,
        )
        val evidence = directory.resolve("ofcli-structural-$runId.json")
        Files.writeString(
            evidence,
            JSONObjectUtils.toJSONString(fields),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }
}