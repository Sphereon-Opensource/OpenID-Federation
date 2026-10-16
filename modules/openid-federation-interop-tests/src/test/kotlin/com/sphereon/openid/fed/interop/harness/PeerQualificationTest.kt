package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.util.JSONObjectUtils
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.images.builder.Transferable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ENTITY_ID = "https://lighthouse.test"
private const val STATEMENT_PATH = "/.well-known/openid-federation"
private const val MAX_BODY_BYTES = 1_048_576

private class InteropContainer(imageName: DockerImageName) : GenericContainer<InteropContainer>(imageName)

@Tag("peer-qualification")
class PeerQualificationTest {
    @Test
    fun `pinned LightHouse publishes a verifiable entity configuration and retains identity after container restart`() {
        val startedAt = Instant.now()
        val peer = PeerLock.load("lighthouse")
        val curlImage = PeerLock.loadHarnessImage("curl")
        val tls = PeerTlsCertificateFactory.create("lighthouse.test")
        val network = InteropNetwork.create()
        var lighthouse: GenericContainer<*>? = null
        var curl: GenericContainer<*>? = null
        var outcome = "failed"
        var verified: VerifiedStatement? = null
        var restarted: VerifiedStatement? = null
        var primaryFailure: Throwable? = null
        try {
            val peerContainer = InteropContainer(DockerImageName.parse(peer.image))
                .withNetwork(network)
                .withNetworkAliases("lighthouse.test")
                .withCreateContainerCmdModifier { cmd ->
                    cmd.withEntrypoint("/bin/sh", "-c", "mkdir -p /data/keys && exec /entrypoint.sh")
                }
                .withEnv(
                    mapOf(
                        "LH_ENTITY_ID" to ENTITY_ID,
                        "LH_SERVER_PORT" to "443",
                        "LH_SERVER_TLS_ENABLED" to "true",
                        "LH_SERVER_TLS_CERT" to "/peer.crt",
                        "LH_SERVER_TLS_KEY" to "/peer.key",
                        "LH_SERVER_TLS_REDIRECT_HTTP" to "false",
                        "LH_SIGNING_KMS" to "filesystem",
                        "LH_SIGNING_PK_BACKEND" to "db",
                        "LH_SIGNING_FILESYSTEM_KEY_DIR" to "/data/keys",
                        "LH_SIGNING_AUTO_GENERATE_KEYS" to "true",
                        "LH_STORAGE_DRIVER" to "sqlite",
                        "LH_STORAGE_DATA_DIR" to "/data",
                    ),
                )
                .withCopyToContainer(Transferable.of(tls.caPem.toByteArray(StandardCharsets.UTF_8), 0b110100100), "/usr/local/share/ca-certificates/interop-ca.crt")
                .withCopyToContainer(Transferable.of(tls.leafPem.toByteArray(StandardCharsets.UTF_8), 0b110100100), "/peer.crt")
                .withCopyToContainer(Transferable.of(tls.keyPem.toByteArray(StandardCharsets.UTF_8), 0b110100000), "/peer.key")
            lighthouse = peerContainer

            val curlContainer = InteropContainer(DockerImageName.parse(curlImage))
                .withNetwork(network)
                .withCreateContainerCmdModifier { cmd -> cmd.withEntrypoint("/bin/sh") }
                .withCommand("-c", "while :; do sleep 3600; done")
                .withCopyToContainer(Transferable.of(tls.caPem.toByteArray(StandardCharsets.UTF_8), 0b110100100), "/tmp/interop-ca.pem")
            curl = curlContainer

            curlContainer.start()
            peerContainer.start()
            awaitPeer(curlContainer)
            val firstJwt = fetchStatement(curlContainer)
            verified = PeerStatementVerifier.verify(firstJwt, ENTITY_ID)

            // Stop/start the existing Docker container so the same /data filesystem is exercised.
            val docker = DockerClientFactory.instance().client()
            docker.stopContainerCmd(peerContainer.containerId).withTimeout(10).exec()
            docker.startContainerCmd(peerContainer.containerId).exec()
            awaitPeer(curlContainer)
            val afterRestartJwt = fetchStatement(curlContainer)
            restarted = PeerStatementVerifier.verify(afterRestartJwt, ENTITY_ID)
            assertEquals(verified!!.issuer, restarted!!.issuer)
            assertEquals(verified!!.jwks, restarted!!.jwks)
            assertEquals(verified!!.kid, restarted!!.kid)

            val unknownCa = PeerTlsCertificateFactory.create("untrusted.test").caPem
            val unknownCaPath = "/tmp/interop-wrong-ca.pem"
            curlContainer.copyFileToContainer(Transferable.of(unknownCa.toByteArray(StandardCharsets.UTF_8), 0b110100100), unknownCaPath)
            val rejected = curlContainer.execInContainer(
                "curl", "--silent", "--show-error", "--connect-timeout", "2", "--max-time", "5",
                "--output", "/dev/null", "--write-out", "%{ssl_verify_result}",
                "--cacert", unknownCaPath, "https://lighthouse.test$STATEMENT_PATH",
            )
            val sslVerifyResult = rejected.stdout.trim().toIntOrNull()
            assertTrue(
                rejected.exitCode in setOf(35, 60) && sslVerifyResult != null && sslVerifyResult != 0,
                "TLS with an unrelated CA must fail certificate verification " +
                    "(curl exit=${rejected.exitCode}, ssl_verify_result=${rejected.stdout.trim()}, stderr=${rejected.stderr.take(512)})",
            )
            val trustedAfterRejection = fetchStatement(curlContainer)
            PeerStatementVerifier.verify(trustedAfterRejection, ENTITY_ID)
            outcome = "passed"
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) { cleanupFailures += failure }
            }
            cleanup { curl?.stop() }
            cleanup { lighthouse?.stop() }
            cleanup { network.close() }
            if (cleanupFailures.isNotEmpty()) outcome = "failed"
            cleanup { writeEvidence(peer, startedAt, outcome, verified, restarted) }
            if (primaryFailure != null) {
                cleanupFailures.forEach(primaryFailure!!::addSuppressed)
            } else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun awaitPeer(curl: GenericContainer<*>) {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (System.nanoTime() < deadline) {
            val result = runCatching {
                curl.execInContainer(
                    "curl", "--silent", "--connect-timeout", "2", "--max-time", "5", "--max-filesize", MAX_BODY_BYTES.toString(),
                    "--fail", "--cacert", "/tmp/interop-ca.pem", "https://lighthouse.test$STATEMENT_PATH",
                )
            }.getOrNull()
            if (result?.exitCode == 0 && result.stdout.toByteArray(StandardCharsets.UTF_8).size <= MAX_BODY_BYTES) return
            Thread.sleep(500)
        }
        error("Pinned peer did not become ready over verified TLS within 90 seconds")
    }

    private fun fetchStatement(curl: GenericContainer<*>): String {
        val result = curl.execInContainer(
            "curl", "--silent", "--connect-timeout", "2", "--max-time", "10", "--max-filesize", MAX_BODY_BYTES.toString(),
            "--fail", "--cacert", "/tmp/interop-ca.pem", "https://lighthouse.test$STATEMENT_PATH",
        )
        check(result.exitCode == 0) { "peer statement HTTPS request failed with code ${result.exitCode}" }
        check(result.stdout.toByteArray(StandardCharsets.UTF_8).size <= MAX_BODY_BYTES) { "peer statement exceeded response body limit" }
        check(result.stderr.isBlank()) { "curl reported an unexpected diagnostic" }
        return result.stdout.trim()
    }

    private fun writeEvidence(peer: PeerLock, startedAt: Instant, outcome: String, initial: VerifiedStatement?, afterRestart: VerifiedStatement?) {
        val directory = System.getProperty("interop.evidence.directory") ?: return
        val output = Path.of(directory)
        Files.createDirectories(output)
        val fields: Map<String, Any> = linkedMapOf<String, Any?>(
            "scenario" to "lighthouse-entity-configuration-and-restart",
            "signedObject" to "entity_configuration",
            "outcome" to outcome,
            "peer" to peer.name,
            "sourceCommit" to peer.commit,
            "imageDigest" to peer.image.substringAfter("@sha256:"),
            "startedAt" to startedAt.toString(),
            "finishedAt" to Instant.now().toString(),
            "entityId" to ENTITY_ID,
            "firstAlgorithm" to initial?.algorithm,
            "restartAlgorithm" to afterRestart?.algorithm,
            "firstKid" to initial?.kid,
            "restartKid" to afterRestart?.kid,
            "publicJwksStable" to if (initial != null && afterRestart != null) (initial.jwks == afterRestart.jwks).toString() else null,
            "claim" to "independent-peer qualification only; no mixed-implementation acceptance claim",
        ).filterValues { it != null }.mapValues { requireNotNull(it.value) }
        Files.writeString(output.resolve("lighthouse-peer-qualification.json"), JSONObjectUtils.toJSONString(fields), StandardCharsets.UTF_8)
    }
}
