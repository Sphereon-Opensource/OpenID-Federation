package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.util.JSONObjectUtils
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.DockerClientFactory
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Pinned LightHouse participant, controlled only through its HTTPS public and admin APIs. */
internal class LighthousePeer private constructor(
    private val container: LighthouseContainer,
    private val client: GenericContainer<*>,
    val lock: PeerLock,
    val adminUsername: String,
    private val adminPassword: String,
) : AutoCloseable {
    private var closed = false

    internal val egressContainer: GenericContainer<*> get() = container
    val entityId: String = ENTITY_ID

    fun createInitialAdmin() {
        val response = request("POST", "/api/v1/admin/users/", json = mapOf(
            "username" to adminUsername,
            "password" to adminPassword,
            "display_name" to "Interop Administrator",
        ))
        check(response.status == 201) { "LightHouse initial administrator creation returned HTTP ${response.status}" }
    }

    fun admin(method: String, path: String, json: Map<String, Any?>? = null): PeerHttpResponse =
        request(method, path, json, adminUsername, adminPassword)

    /** Replaces a trust-mark issuer allow-list using LightHouse's JSON-array API body. */
    fun replaceTrustMarkIssuers(typeId: String, issuerIds: List<String>): PeerHttpResponse {
        // LightHouse 0.23.0 returns numeric row IDs; older builds used UUIDs.
        require(typeId.matches(Regex("[0-9]{1,18}|[0-9a-fA-F-]{36}"))) { "invalid LightHouse trust-mark type ID" }
        require(issuerIds.isNotEmpty() && issuerIds.size <= 8) { "issuer allow-list is outside its bounded range" }
        require(issuerIds.all { it.startsWith("https://") && it.length <= 2048 && it.none(Char::isISOControl) }) {
            "issuer allow-list contains an invalid bounded HTTPS identifier"
        }
        val jsonArray = issuerIds.joinToString(separator = ",", prefix = "[", postfix = "]") {
            JSONObjectUtils.toJSONString(mapOf("issuer" to it))
        }
        return request(
            "PUT", "/api/v1/admin/trust-marks/types/$typeId/issuers",
            username = adminUsername, password = adminPassword, rawJsonBody = jsonArray,
        )
    }
    fun adminText(method: String, path: String, body: String): PeerHttpResponse {
        require(body.length <= 64 && body.all { it.isLetter() }) { "invalid bounded status value" }
        return request(method, path, username = adminUsername, password = adminPassword, rawBody = body, contentType = "text/plain")
    }

    fun setEntityConfigurationLifetime(seconds: Int): PeerHttpResponse {
        require(seconds in 1..3_600) { "test Entity Configuration lifetime is outside the bounded range" }
        return request("PUT", "/api/v1/admin/entity-configuration/lifetime", username = adminUsername, password = adminPassword,
            rawBody = seconds.toString(), contentType = "application/json")
    }

    fun public(path: String): PeerHttpResponse = request("GET", path)

    fun addTrustAnchor(entityId: String, jwks: Map<String, Any?>): PeerHttpResponse = admin(
        "POST", "/api/v1/admin/trust-anchors/",
        mapOf("entity_id" to entityId, "jwks" to jwks),
    )

    fun addAuthorityHint(entityId: String): PeerHttpResponse = admin(
        "POST", "/api/v1/admin/entity-configuration/authority-hints/",
        mapOf("entity_id" to entityId),
    )

    fun addSubordinate(entityId: String, jwks: Map<String, Any?>): PeerHttpResponse = admin(
        "POST", "/api/v1/admin/subordinates/",
        mapOf("entity_id" to entityId, "status" to "active", "jwks" to jwks),
    )

    fun registerEndpoint(type: String, path: String, config: String? = null): PeerHttpResponse {
        require(type in setOf("fetch", "resolve", "trust_mark", "trust_mark_status"))
        val body = linkedMapOf<String, Any?>("type" to type, "path" to path, "auth_enabled" to false)
        if (config != null) body["config"] = config
        return admin("POST", "/api/v1/admin/federation-endpoints/", body)
    }

    fun resolve(entityId: String, trustAnchor: String): PeerHttpResponse =
        public("/resolve?sub=${encode(entityId)}&trust_anchor=${encode(trustAnchor)}")

    /** Restarts the same Docker container so persisted SQLite/filesystem state remains in place. */
    fun restart() {
        check(!closed) { "LightHouse peer is closed" }
        val docker = DockerClientFactory.instance().client()
        val id = requireNotNull(container.containerId) { "LightHouse container has not started" }
        docker.stopContainerCmd(id).exec()
        docker.startContainerCmd(id).exec()
        awaitReady()
    }

    fun awaitReady(timeout: Duration = Duration.ofSeconds(90)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val response = runCatching { public(ENTITY_CONFIGURATION_PATH) }.getOrNull()
            if (response?.status == 200 && response.body.split('.').size == 3) return
            Thread.sleep(500)
        }
        error("Pinned LightHouse did not publish its Entity Configuration over verified HTTPS")
    }

    override fun close() {
        if (closed) return
        closed = true
        container.stop()
    }

    private fun request(
        method: String,
        path: String,
        json: Map<String, Any?>? = null,
        username: String? = null,
        password: String? = null,
        rawBody: String? = null,
        contentType: String = "application/json",
        rawJsonBody: String? = null,
    ): PeerHttpResponse {
        check(!closed) { "LightHouse peer is closed" }
        require(method.matches(Regex("[A-Z]{3,10}")))
        require(path.startsWith('/') && !path.startsWith("//"))
        require(listOf(json != null, rawBody != null, rawJsonBody != null).count { it } <= 1) { "only one LightHouse request body form may be supplied" }
        val command = mutableListOf(
            "curl", "--silent", "--show-error", "--max-time", "20", "--connect-timeout", "5",
            "--max-filesize", MAX_BODY_BYTES.toString(), "--cacert", "/run/interop/ca.pem",
            "--request", method, "--write-out", "\\n__INTEROP_HTTP_STATUS__:%{http_code}",
        )
        if (username != null) {
            command += listOf("--user", "$username:${requireNotNull(password)}")
        }
        if (rawBody != null) {
            require(json == null && rawBody.length <= 64 && rawBody.none(Char::isISOControl)) { "invalid bounded LightHouse raw body" }
            require(contentType in setOf("application/json", "text/plain")) { "unsupported LightHouse raw content type" }
            command += listOf("--header", "Content-Type: $contentType", "--data-binary", rawBody)
        }
        if (json != null) {
            command += listOf("--header", "Content-Type: application/json", "--data-binary", JSONObjectUtils.toJSONString(json))
        }
        if (rawJsonBody != null) {
            require(rawJsonBody.length <= MAX_BODY_BYTES && rawJsonBody.startsWith("[") && rawJsonBody.endsWith("]") && rawJsonBody.none(Char::isISOControl)) {
                "invalid bounded LightHouse JSON-array body"
            }
            command += listOf("--header", "Content-Type: application/json", "--data-binary", rawJsonBody)
        }
        command += "https://lighthouse.test$path"
        val result = client.execInContainer(*command.toTypedArray())
        val marker = "\n__INTEROP_HTTP_STATUS__:"
        val markerAt = result.stdout.lastIndexOf(marker)
        check(result.exitCode == 0 && markerAt >= 0) { "LightHouse HTTPS request failed (curl exit=${result.exitCode})" }
        val body = result.stdout.substring(0, markerAt)
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_BODY_BYTES) { "LightHouse response exceeded the 1 MiB limit" }
        val status = result.stdout.substring(markerAt + marker.length).trim().toIntOrNull()
            ?: error("LightHouse request returned no HTTP status")
        return PeerHttpResponse(status, body)
    }

    companion object {
        const val ENTITY_ID = "https://lighthouse.test"
        const val ENTITY_CONFIGURATION_PATH = "/.well-known/openid-federation"
        private const val MAX_BODY_BYTES = 1_048_576

        fun start(environment: InteropEnvironment, configureCounterpartyTargets: Boolean = true): LighthousePeer {
            val lock = PeerLock.load("lighthouse")
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val tls = environment.tls
            val peer = LighthouseContainer(DockerImageName.parse(lock.image))
                .withNetwork(environment.network)
                .withNetworkAliases("lighthouse.test")
                .withCreateContainerCmdModifier { cmd ->
                    cmd.withEntrypoint("/bin/sh", "-c", "mkdir -p /data/keys && exec /entrypoint.sh")
                }
                .withEnv(mapOf(
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
                    // Go's standard TLS client honors SSL_CERT_FILE for the outbound resolver.
                    "SSL_CERT_FILE" to "/ca.pem",
                ))
                .withCopyToContainer(Transferable.of(tls.caPem.toByteArray(StandardCharsets.UTF_8), 0b110100100), "/ca.pem")
                .withCopyToContainer(Transferable.of(tls.leafPem.toByteArray(StandardCharsets.UTF_8), 0b110100100), "/peer.crt")
                .withCopyToContainer(Transferable.of(tls.keyPem.toByteArray(StandardCharsets.UTF_8), 0b110100000), "/peer.key")
            val adapter = LighthousePeer(peer, environment.client, lock, "interop-admin", password)
            try {
                peer.start()
                adapter.awaitReady()
                adapter.createInitialAdmin()
                if (configureCounterpartyTargets) {
                    environment.configureCounterpartyPrivateTargets(mapOf("lighthouse.test:443" to peer))
                }
                return adapter
            } catch (failure: Throwable) {
                try { adapter.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

internal data class PeerHttpResponse(val status: Int, val body: String)

private class LighthouseContainer(image: DockerImageName) : GenericContainer<LighthouseContainer>(image)
