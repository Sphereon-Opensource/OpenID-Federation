package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.util.JSONObjectUtils
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Runs the source-pinned OFCLI structural probe beside the real peers. The peer's Nimbus verifier
 * remains authoritative for signatures and policy; OFCLI is used only for chain-link discovery.
 */
internal class OfcliStructuralProbe private constructor(
    private val container: OfcliContainer,
) : AutoCloseable {
    private var closed = false

    fun inspect(startingEntity: String, expectedPath: List<String>, servedEntityAnchorControl: String): OfcliStructuralEvidence {
        check(!closed) { "OFCLI probe container is closed" }
        require(expectedPath.size == 3 && expectedPath.first() == startingEntity) {
            "OFCLI scenario requires the actual three-entity path from its starting entity"
        }
        require(expectedPath.last() == LighthousePeer.ENTITY_ID) {
            "OFCLI selected anchor must be the pinned LightHouse entity"
        }
        require(servedEntityAnchorControl == startingEntity && servedEntityAnchorControl == expectedPath.first()) {
            "The control must use the already fetched starting entity as its requested anchor"
        }
        (expectedPath + servedEntityAnchorControl).forEach { value ->
            require(value.length <= MAX_ENTITY_ID_CHARS && value.startsWith("https://")) {
                "OFCLI identifiers must be bounded HTTPS URLs"
            }
        }

        val arguments = mutableListOf(
            "python", PROBE_PATH,
            "--scenario-id", SCENARIO_ID,
            "--entity", startingEntity,
            "--anchor", expectedPath.last(),
        )
        expectedPath.forEach { arguments += listOf("--expected-chain", it) }
        arguments += listOf(
            "--negative-anchor", servedEntityAnchorControl,
            "--ca-file", CA_PATH,
            "--output", RESULT_PATH,
        )
        val execution = container.execInContainer(*arguments.toTypedArray())
        check(execution.stdout.toByteArray(StandardCharsets.UTF_8).size <= MAX_WRAPPER_OUTPUT_BYTES) {
            "OFCLI wrapper stdout exceeded its output bound"
        }
        check(execution.stderr.toByteArray(StandardCharsets.UTF_8).size <= MAX_WRAPPER_OUTPUT_BYTES) {
            "OFCLI wrapper stderr exceeded its output bound"
        }
        check(execution.exitCode == 0) {
            // The wrapper prints only its own classified reason (never URLs or response material).
            val reason = execution.stderr.lineSequence().firstOrNull { it.startsWith("ofcli structural probe failed") }
                ?.filter { !it.isISOControl() }?.take(200)
            "Pinned OFCLI structural probe failed (exit=${execution.exitCode})" + (reason?.let { ": $it" } ?: "")
        }

        val resultFile = container.execInContainer("head", "-c", (MAX_RESULT_BYTES + 1).toString(), RESULT_PATH)
        check(resultFile.exitCode == 0) { "OFCLI structural result was not produced" }
        val encoded = resultFile.stdout.toByteArray(StandardCharsets.UTF_8)
        check(encoded.isNotEmpty() && encoded.size <= MAX_RESULT_BYTES) {
            "OFCLI structural result was empty or exceeded its byte limit"
        }
        val root = JSONObjectUtils.parse(resultFile.stdout) as? Map<*, *>
            ?: error("OFCLI structural result was not a JSON object")
        check(root["scenarioId"] == SCENARIO_ID && root["status"] == "structural-chain-present") {
            "OFCLI structural result had an unexpected scenario status"
        }
        val tool = root["tool"] as? Map<*, *> ?: error("OFCLI result omitted tool identity")
        check(tool["name"] == TOOL_NAME && tool["version"] == TOOL_VERSION && tool["commit"] == TOOL_COMMIT) {
            "OFCLI result source identity did not match the pinned recipe"
        }
        val chain = root["chain"] as? Map<*, *> ?: error("OFCLI result omitted chain structure")
        val outputPath = (chain["path"] as? List<*>)?.map { it as? String ?: error("OFCLI path item was malformed") }
            ?: error("OFCLI result omitted the structural path")
        check(outputPath == expectedPath) { "OFCLI result path differed from the independently declared peer path" }
        val links = (chain["links"] as? List<*>)?.map { raw ->
            val link = raw as? Map<*, *> ?: error("OFCLI link was malformed")
            val issuer = link["iss"] as? String ?: error("OFCLI link omitted issuer")
            val subject = link["sub"] as? String ?: error("OFCLI link omitted subject")
            issuer to subject
        } ?: error("OFCLI result omitted structural issuer/subject links")
        // OpenID Federation 1.1 section 4: leaf EC, one Subordinate Statement per hop, Trust Anchor EC.
        // The intermediate's own Entity Configuration is not part of a Trust Chain.
        check(links.size == 4 && (chain["chainLength"] as? Number)?.toInt() == links.size) {
            "OFCLI link count did not match the three-entity path"
        }
        val expectedLinks = listOf(
            expectedPath[0] to expectedPath[0],
            expectedPath[1] to expectedPath[0],
            expectedPath[2] to expectedPath[1],
            expectedPath[2] to expectedPath[2],
        )
        check(links == expectedLinks && chain["terminalAnchor"] == expectedPath.last()) {
            "OFCLI links or terminal anchor differed from the independently declared path"
        }
        val boundary = root["evidenceBoundary"] as? Map<*, *> ?: error("OFCLI result omitted evidence limits")
        val notVerified = boundary["notVerified"] as? List<*> ?: error("OFCLI result omitted limitations")
        check("entity-statement signatures" in notVerified && "metadata policy" in notVerified && "TLS validation of every native retrieval" in notVerified) {
            "OFCLI result did not preserve its structural-only limitation"
        }
        val negative = root["negativeControl"] as? Map<*, *>
            ?: error("OFCLI result omitted its served-entity anchor control")
        check(negative["anchor"] == servedEntityAnchorControl && negative["kind"] == NEGATIVE_KIND && negative["status"] == NEGATIVE_STATUS && negative["entityMatchesStartingEntity"] == true && negative["rawCliOutputRetained"] == false) {
            "OFCLI served-entity anchor control did not match its bounded expected result"
        }
        return OfcliStructuralEvidence(
            startingEntity = startingEntity,
            selectedAnchor = expectedPath.last(),
            expectedPath = expectedPath,
            observedLinks = links,
            chainLength = links.size,
            servedEntityAnchorControl = servedEntityAnchorControl,
            negativeControlStatus = NEGATIVE_STATUS,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        container.stop()
    }

    companion object {
        const val TOOL_NAME = "ofcli"
        const val TOOL_VERSION = "0.7.2"
        const val TOOL_COMMIT = "289e97f45d1fd225f16ff77976a7ede1ea6390f0"
        const val NEGATIVE_STATUS = "no-chain-for-live-starting-entity-anchor"
        const val NEGATIVE_KIND = "starting-entity-as-requested-anchor"
        private const val RESOURCE_ROOT = "interop/probes/ofcli/v4"
        private const val CA_PATH = "/run/interop-ca/ca.pem"
        private const val PROBE_PATH = "/opt/ofcli-probe/ofcli_structural_probe.py"
        private const val RESULT_PATH = "/tmp/ofcli-structural-result.json"
        private const val SCENARIO_ID = "sphereon-leaf-through-root-to-lighthouse"
        private const val MAX_ENTITY_ID_CHARS = 2_048
        private const val MAX_WRAPPER_OUTPUT_BYTES = 16 * 1024
        private const val MAX_RESULT_BYTES = 32 * 1024
        private const val IMAGE_TAG = "oidfed/ofcli-structural-probe:0.7.2"

        fun start(environment: InteropEnvironment): OfcliStructuralProbe {
            val image = ImageFromDockerfile(IMAGE_TAG, false)
                .withFileFromClasspath("Dockerfile", "$RESOURCE_ROOT/Dockerfile")
                .withFileFromClasspath("runtime-requirements.txt", "$RESOURCE_ROOT/runtime-requirements.txt")
                .withFileFromClasspath(
                    "sources/ofcli-289e97f45d1fd225f16ff77976a7ede1ea6390f0.tar.gz",
                    "$RESOURCE_ROOT/sources/ofcli-289e97f45d1fd225f16ff77976a7ede1ea6390f0.tar.gz",
                )
                .withFileFromClasspath("ofcli_structural_probe.py", "$RESOURCE_ROOT/ofcli_structural_probe.py")
            val container = OfcliContainer(image)
                .withNetwork(environment.network)
                .withCopyToContainer(
                    Transferable.of(environment.tls.caPem.toByteArray(StandardCharsets.UTF_8), 0b100100100),
                    CA_PATH,
                )
                .withCreateContainerCmdModifier { command ->
                    command.withEntrypoint("/bin/sh", "-c", "exec sleep infinity")
                }
                .withStartupTimeout(Duration.ofSeconds(30))
            try {
                container.start()
                return OfcliStructuralProbe(container)
            } catch (failure: Throwable) {
                runCatching { container.stop() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

internal data class OfcliStructuralEvidence(
    val startingEntity: String,
    val selectedAnchor: String,
    val expectedPath: List<String>,
    val observedLinks: List<Pair<String, String>>,
    val chainLength: Int,
    val servedEntityAnchorControl: String,
    val negativeControlStatus: String,
)

private class OfcliContainer(image: ImageFromDockerfile) : GenericContainer<OfcliContainer>(image)