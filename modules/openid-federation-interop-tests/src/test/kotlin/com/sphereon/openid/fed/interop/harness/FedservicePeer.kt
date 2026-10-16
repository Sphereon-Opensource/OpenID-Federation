package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.util.JSONObjectUtils
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import org.testcontainers.DockerClientFactory
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Future

/**
 * Source-built pinned experimental SUNET/fedservice edu_federation Trust Anchor on the same private
 * network and CA as the Sphereon and LightHouse peers. This adapter uses upstream provisioning
 * scripts, not an invented HTTP administration API, and never seeds statements or keys directly.
 */
internal class FedservicePeer private constructor(
    private val container: FedserviceContainer,
    private val client: GenericContainer<*>,
) : AutoCloseable {
    private var closed = false

    val entityId: String = ENTITY_ID
    internal val egressContainer: GenericContainer<*> get() = container
    val dockerIdentity: String
        get() = requireNotNull(container.containerId) { "fedservice has no Docker identity" }

    fun awaitReady(timeout: Duration = READY_TIMEOUT) {
        check(!closed) { "fedservice peer is closed" }
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val response = runCatching { request("GET", ENTITY_CONFIGURATION_PATH) }.getOrNull()
            if (response?.status == 200 && response.body.split('.').size == 3) return
            Thread.sleep(READY_POLL.toMillis())
        }
        error("Pinned fedservice did not publish its Entity Configuration over verified HTTPS")
    }

    /** Fetch the real signed Entity Configuration through the isolated CA-verifying client. */
    fun entityConfiguration(): FedserviceHttpResponse = request("GET", ENTITY_CONFIGURATION_PATH)
        .also { response ->
            check(response.status == 200 && response.body.split('.').size == 3) {
                "fedservice Entity Configuration request did not return a compact signed JWT (HTTP ${response.status})"
            }
        }

    /** Resolve through the pinned fedservice GET route using its actual query fields. */
    fun resolve(subordinateEntityId: String, trustAnchorEntityId: String): FedserviceHttpResponse {
        require(subordinateEntityId.startsWith("https://") && subordinateEntityId.length <= MAX_ENTITY_ID_CHARS) {
            "subordinate entity ID must be a bounded HTTPS URL"
        }
        require(trustAnchorEntityId.startsWith("https://") && trustAnchorEntityId.length <= MAX_ENTITY_ID_CHARS) {
            "trust anchor entity ID must be a bounded HTTPS URL"
        }
        val anchor = java.net.URLEncoder.encode(trustAnchorEntityId, StandardCharsets.UTF_8)
        val subordinate = java.net.URLEncoder.encode(subordinateEntityId, StandardCharsets.UTF_8)
        return request("GET", "/resolve?anchor=$anchor&sub=$subordinate")
    }

    /** Run upstream get_info.py and add_info.py against a real verified peer Entity Configuration. */
    fun provisionSubordinate(entityId: String) {
        check(!closed) { "fedservice peer is closed" }
        require(entityId.startsWith("https://") && entityId.length <= MAX_ENTITY_ID_CHARS) {
            "subordinate entity ID must be a bounded HTTPS URL"
        }
        val result = container.execInContainer(
            "/usr/local/bin/provision-subordinate", entityId, PEER_CA_PATH, STATE_PATH,
        )
        check(result.exitCode == 0) {
            "upstream fedservice subordinate provisioning failed (exit=${result.exitCode})"
        }
    }

    /** Restart this exact Docker container, preserving its image-declared state volume. */
    fun restart() {
        check(!closed) { "fedservice peer is closed" }
        val id = dockerIdentity
        val docker = DockerClientFactory.instance().client()
        docker.stopContainerCmd(id).withTimeout(10).exec()
        docker.startContainerCmd(id).exec()
        check(dockerIdentity == id) { "fedservice restart replaced the container identity" }
        awaitReady()
    }

    override fun close() {
        if (closed) return
        closed = true
        container.stop()
    }

    private fun request(method: String, path: String, jsonBody: String? = null): FedserviceHttpResponse {
        check(!closed) { "fedservice peer is closed" }
        require(method.matches(Regex("[A-Z]{3,10}"))) { "invalid HTTP method" }
        require(path.startsWith('/') && !path.startsWith("//")) { "path must be absolute" }
        val command = arrayOf(
            "curl", "--silent", "--show-error", "--max-time", "20", "--connect-timeout", "5",
            "--max-filesize", MAX_BODY_BYTES.toString(), "--cacert", CURL_CA_PATH,
            "--request", method, "--write-out", "\\n__INTEROP_HTTP_METADATA__:%{http_code}|%{content_type}",
            "https://fedservice.test:7003$path",
        )
        val fullCommand = if (jsonBody == null) command else command.toMutableList().let { values ->
            val url = values.removeAt(values.lastIndex)
            values += listOf("--header", "Content-Type: application/json", "--data-binary", jsonBody, url)
            values.toTypedArray()
        }
        val result = client.execInContainer(*fullCommand)
        val marker = "\n__INTEROP_HTTP_METADATA__:"
        val markerAt = result.stdout.lastIndexOf(marker)
        check(result.exitCode == 0 && markerAt >= 0) {
            "fedservice HTTPS request failed (curl exit=${result.exitCode})"
        }
        val body = result.stdout.substring(0, markerAt)
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_BODY_BYTES) {
            "fedservice response exceeded the 1 MiB limit"
        }
        val metadata = result.stdout.substring(markerAt + marker.length).trim().split('|', limit = 2)
        val status = metadata.firstOrNull()?.toIntOrNull()
            ?: error("fedservice request returned no HTTP status")
        val contentType = metadata.getOrNull(1)?.takeIf { it.length <= MAX_CONTENT_TYPE_CHARS }
            ?: error("fedservice request returned no Content-Type metadata")
        return FedserviceHttpResponse(status, contentType, body)
    }

    companion object {
        const val ENTITY_ID = "https://fedservice.test:7003"
        const val ENTITY_CONFIGURATION_PATH = "/.well-known/openid-federation"
        private const val MAX_BODY_BYTES = 1_048_576
        private const val MAX_CONTENT_TYPE_CHARS = 256
        private const val MAX_ENTITY_ID_CHARS = 2_048
        private val READY_TIMEOUT = Duration.ofSeconds(90)
        private val READY_POLL = Duration.ofMillis(500)
        private const val CURL_CA_PATH = "/run/interop/ca.pem"
        private const val PEER_CA_PATH = "/run/peer-ca/ca-bundle.pem"
        private const val STATE_PATH = "/var/lib/fedservice"
        private const val IMAGE_TAG = "oidfed/fedservice-interop:exp-rh-new-config-v4-edu"
        private const val IMAGE_RESOURCE = "interop/peers/fedservice/recipe-v4"
        private const val CONFIG_RESOURCE = "interop/peers/fedservice/recipe-v4/trust-anchor-config.rs256.json"
        private const val SUBORDINATE_CONFIG_RESOURCE = "interop/peers/fedservice/recipe-v4/trust-anchor-config.rs256.sphereon-subordinate.json"
        private const val CONFIG_PATH = "/opt/fedservice/edu_federation/trust_anchor/conf.json"
        private const val SOURCE_LOCK_LABEL = "0304f72453214c4f85bed7f792a6b5ad39f357d3,1a66289b5f788d5b54024de94e9cfd860fb36a8c,dfcea1c10a038f67766d453433840c64a9aa2b10,de1715ef89c7f3db5daf3cdc53fec26d6ade03d9"
        private const val PRODUCER_RECIPE_LABEL = "fedservice-rh-new-config-producer-recipe-v4"
        internal const val UPSTREAM_SOURCE_COMMIT = "0304f72453214c4f85bed7f792a6b5ad39f357d3"
        internal const val PRODUCER_RECIPE = "fedservice-rh-new-config-producer-recipe-v4"
        private val SOURCE_ARCHIVES = listOf(
            "fedservice-0304f72453214c4f85bed7f792a6b5ad39f357d3.tar.gz",
            "idpy-oidc-1a66289b5f788d5b54024de94e9cfd860fb36a8c.tar.gz",
            "openid4v-dfcea1c10a038f67766d453433840c64a9aa2b10.tar.gz",
            "idpy-sdjwt-de1715ef89c7f3db5daf3cdc53fec26d6ade03d9.tar.gz",
        )

        fun start(environment: InteropEnvironment): FedservicePeer = startWithConfig(environment, CONFIG_RESOURCE)

        /** Uses the pinned FederationEntity authority_hints field to run as Sphereon's child. */
        fun startAsSphereonSubordinate(environment: InteropEnvironment): FedservicePeer =
            startWithConfig(environment, SUBORDINATE_CONFIG_RESOURCE)

        private fun startWithConfig(environment: InteropEnvironment, configResource: String): FedservicePeer {
            val container = FedserviceContainer(sourceBuiltImage())
                .withNetwork(environment.network)
                .withNetworkAliases("fedservice.test")
                .withCopyToContainer(Transferable.of(resourceBytes(configResource), 0b100100100), CONFIG_PATH)
                .withCopyToContainer(
                    Transferable.of(environment.tls.caPem.toByteArray(StandardCharsets.UTF_8), 0b100100100), PEER_CA_PATH,
                )
                .withCopyToContainer(
                    Transferable.of(environment.tls.caPem.toByteArray(StandardCharsets.UTF_8), 0b100100100), CURL_CA_PATH,
                )
                .withCopyToContainer(
                    Transferable.of(environment.tls.leafPem.toByteArray(StandardCharsets.UTF_8), 0b100100100), "/run/tls/server.crt",
                )
                .withCopyToContainer(
                    Transferable.of(environment.tls.keyPem.toByteArray(StandardCharsets.UTF_8), 0b100000000), "/run/tls/server.key",
                )
                .withLabel("codex.interop.source.lock", SOURCE_LOCK_LABEL)
                .withLabel("codex.interop.producer.recipe", PRODUCER_RECIPE_LABEL)
            val adapter = FedservicePeer(container, environment.client)
            try {
                container.start()
                adapter.awaitReady()
                return adapter
            } catch (failure: Throwable) {
                try { adapter.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun sourceBuiltImage(): ImageFromDockerfile {
            val image = ImageFromDockerfile(IMAGE_TAG, false)
                .withFileFromClasspath("Dockerfile", "$IMAGE_RESOURCE/Dockerfile")
                .withFileFromClasspath("sources.sha256", "$IMAGE_RESOURCE/sources.sha256")
                .withFileFromClasspath("fedservice-entrypoint.py", "$IMAGE_RESOURCE/fedservice-entrypoint.py")
                .withFileFromClasspath("provision-subordinate.sh", "$IMAGE_RESOURCE/provision-subordinate.sh")
                .withFileFromClasspath("build-closure/requirements.txt", "$IMAGE_RESOURCE/build-closure/requirements.txt")
                .withFileFromClasspath("pypi-closure/requirements.txt", "$IMAGE_RESOURCE/pypi-closure/requirements.txt")
            SOURCE_ARCHIVES.forEach { archive ->
                image.withFileFromClasspath("sources/$archive", "$IMAGE_RESOURCE/sources/$archive")
            }
            return image
        }

        private fun resourceBytes(path: String): ByteArray =
            FedservicePeer::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
                ?: error("Missing pinned fedservice test resource")
    }
}

internal data class FedserviceHttpResponse(val status: Int, val contentType: String, val body: String)

private class FedserviceContainer(image: Future<String>) : GenericContainer<FedserviceContainer>(image)




