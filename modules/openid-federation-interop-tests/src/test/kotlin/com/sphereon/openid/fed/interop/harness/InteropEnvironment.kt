package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.util.JSONObjectUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** A real packaged Sphereon deployment and its private test-only HTTPS edge. */
class InteropEnvironment private constructor(
    val network: Network,
    internal val tls: PeerTlsMaterial,
    val client: GenericContainer<*>,
    private val gateway: GenericContainer<*>,
    private val keycloak: GenericContainer<*>,
    private val postgres: GenericContainer<*>,
    private val publicServer: GenericContainer<*>,
    private val adminServer: GenericContainer<*>,
    private val workDirectory: Path,
    private val workRoot: Path,
    private val keystoreRootPath: Path,
    private val kmsPassword: String,
    private val adminLibDirectory: Path,
    private val publicLibDirectory: Path,
    private val clientId: String,
    private val clientSecret: String,
    private val diagnosticSecrets: List<String>,
) : AutoCloseable {
    val publicEntityId: String = PUBLIC_ENTITY_ID
    private var selectedKeyId: String? = null
    private var counterpartyTargetsConfigured = false
    private val runtimeStateChecks = mutableListOf<Map<String, Any?>>()

    /** Hash the exact installDist runtime JARs used by the packaged JVMs. */
    fun artifactIdentity(): Map<String, String> = linkedMapOf(
        "adminInstallDistJarSha256" to hashJarDirectory(adminLibDirectory),
        "publicInstallDistJarSha256" to hashJarDirectory(publicLibDirectory),
    ).apply {
        System.getProperty("interop.source.aggregateSha")?.takeIf(String::isNotBlank)?.let { put("sourceAggregateSha", it) }
        System.getProperty("interop.source.head")?.takeIf(String::isNotBlank)?.let { put("sourceHead", it) }
    }

    /** Verify the real Postgres selection and PKCS12 custody for the created signing key. */
    fun verifyDurableState(phase: String, expectedPublicJwks: Map<String, Any?>) {
        require(phase in setOf("after-initial-publication", "after-restart-publication")) { "unsupported runtime-state phase" }
        check(runtimeStateChecks.none { it["phase"] == phase }) { "runtime-state phase was already recorded" }
        val keyId = selectedKeyId ?: error("no interop-created signing key has been selected")
        val evidence = DurableStateProbe.inspect(postgres, keystoreRootPath, kmsPassword, keyId, expectedPublicJwks)
        val previous = runtimeStateChecks.lastOrNull()
        if (previous != null) {
            check(previous["selectedKeyId"] == evidence.selectedKeyId) { "selected key ID changed across the server restart" }
            check(previous["revision"] == evidence.revision) { "selected-key revision changed across the server restart" }
        }
        runtimeStateChecks += linkedMapOf(
            "phase" to phase,
            "rootAccountCount" to evidence.rootAccountCount,
            "selectedSigningRowCount" to evidence.selectedSigningRowCount,
            "selectedJwkRowCount" to evidence.selectedJwkRowCount,
            "revision" to evidence.revision,
            "selectedKeyId" to evidence.selectedKeyId,
            "privateKeyEntryCertificateMatched" to evidence.privateKeyEntryCertificateMatched,
            "keystoreType" to evidence.keystoreType,
            "publicKeyThumbprint" to evidence.publicKeyThumbprint,
        )
    }

    /** Safe allowlisted runtime checks captured by the interop test in memory only. */
    fun runtimeStateEvidence(): List<Map<String, Any?>> = runtimeStateChecks.map { it.toMap() }

    fun rememberSelectedKeyId(keyId: String) {
        check(selectedKeyId == null || selectedKeyId == keyId) { "interop scenario attempted to replace its selected signing key" }
        selectedKeyId = keyId
    }

    private var closed = false

    /** Make one bounded HTTPS request through the isolated edge, retaining the real HTTP status. */
    fun request(
        method: String,
        url: String,
        body: String? = null,
        bearer: String? = null,
        account: String? = null,
    ): InteropHttpResponse {
        require(url.startsWith("https://")) { "interop requests must use HTTPS" }
        require(url.length <= MAX_URL_LENGTH) { "interop URL exceeds the length limit" }
        require(method.matches(Regex("[A-Z]{3,10}"))) { "invalid HTTP method" }
        require(body == null || body.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "interop request body exceeds the 1 MiB limit"
        }
        require(account == null || (account.length <= 256 && account.none(Char::isISOControl))) {
            "invalid account header value"
        }
        require(bearer == null || (bearer.length <= 16_384 && bearer.none(Char::isISOControl))) {
            "invalid bearer token value"
        }
        return curlRequest(method, url, body, bearer, account)
    }

    /** Bounded URL-encoded form request for LightHouse Trust Mark Status. */
    fun requestForm(method: String, url: String, fields: Map<String, String>): InteropHttpResponse {
        require(url.startsWith("https://")) { "interop requests must use HTTPS" }
        require(url.length <= MAX_URL_LENGTH) { "interop URL exceeds the length limit" }
        require(method.matches(Regex("[A-Z]{3,10}"))) { "invalid HTTP method" }
        require(fields.isNotEmpty() && fields.size <= 8) { "invalid form field count" }
        require(fields.all { (name, value) -> name.matches(Regex("[a-z_]{1,64}")) && value.length <= 16_384 && value.none(Char::isISOControl) }) {
            "invalid bounded form fields"
        }
        return curlRequest(method, url, null, null, null, fields.map { (name, value) -> "$name=$value" })
    }

    /** Fetch an actual Keycloak client-credentials JWT and use it against the admin endpoint. */
    fun admin(method: String, path: String, body: String? = null, account: String? = null): InteropHttpResponse {
        return adminInternal(method, path, body, account, captureWire = false, explicitZeroLengthDelete = false)
    }

    /** Capture only private curl wire diagnostics for the real bodyless DELETE acceptance request. */
    fun adminDeleteWithWireCapture(path: String): InteropHttpResponse {
        require(isSubordinateDeletePath(path)) { "unsupported diagnostic DELETE path" }
        return adminInternal("DELETE", path, null, null, captureWire = true, explicitZeroLengthDelete = false)
    }

    /** Run an explicit-empty-framing control after the unframed acceptance DELETE has failed. */
    fun adminDeleteZeroLengthControl(path: String): InteropHttpResponse {
        require(isSubordinateDeletePath(path)) { "unsupported diagnostic DELETE path" }
        return adminInternal("DELETE", path, null, null, captureWire = true, explicitZeroLengthDelete = true)
    }

    private fun adminInternal(
        method: String,
        path: String,
        body: String?,
        account: String?,
        captureWire: Boolean,
        explicitZeroLengthDelete: Boolean,
    ): InteropHttpResponse {
        require(path.startsWith('/') && !path.startsWith("//")) { "admin path must be absolute" }
        val tokenUrl = "https://auth.test/realms/interop/protocol/openid-connect/token"
        val tokenResponse = curlRequest(
            method = "POST",
            url = tokenUrl,
            body = null,
            bearer = null,
            account = null,
            form = listOf("grant_type=client_credentials", "client_id=$clientId", "client_secret=$clientSecret"),
        )
        check(tokenResponse.status in 200..299) { "Keycloak client-credentials request returned HTTP ${tokenResponse.status}" }
        val token = JSONObjectUtils.getString(JSONObjectUtils.parse(tokenResponse.body), "access_token")
        val headerType = runCatching {
            val segments = token.split('.')
            require(segments.size == 3) { "Keycloak access token is not a compact signed JWT" }
            val headerBytes = Base64.getUrlDecoder().decode(segments.first())
            JSONObjectUtils.getString(JSONObjectUtils.parse(String(headerBytes, StandardCharsets.UTF_8)), "typ")
        }.getOrElse { error("Keycloak access token has no readable JOSE type") }
        check(headerType == "at+jwt") {
            "Keycloak access token did not use RFC 9068 typ required by the OIDF admin validator"
        }
        // Keep bodyless admin command POSTs aligned with the JSON request form used by
        // the admin API integration tests. The client-credentials form request above
        // deliberately remains a form POST with no JSON body.
        val commandBody = if (method == "POST" && body == null) "{}" else body
        return curlRequest(
            method = method,
            url = "https://admin.test$path",
            body = commandBody,
            bearer = token,
            account = account,
            captureWire = captureWire,
            explicitZeroLengthBody = explicitZeroLengthDelete,
        )
    }

    /** Restart only the two production JVMs; their DB and shared PKCS12 bind mount stay in place. */
    fun restartSphereon() {
        check(!closed) { "interop environment is closed" }
        val docker = DockerClientFactory.instance().client()
        listOf(publicServer, adminServer).forEach { container ->
            val id = container.containerId ?: error("Sphereon container has no Docker identity")
            docker.stopContainerCmd(id).withTimeout(10).exec()
        }
        listOf(publicServer, adminServer).forEach { container ->
            val id = container.containerId ?: error("Sphereon container has no Docker identity")
            docker.startContainerCmd(id).exec()
        }
        awaitHttps("https://sphereon.test/health")
        awaitHttps("https://admin.test/health")
    }

    /**
     * Configure the exact outbound HTTPS counterpart identities before provisioning starts.
     * The JVM containers receive a fresh copy of the normal full operator YAML because their
     * startup configuration is copied into the container rather than mounted from the host.
     */
    internal fun configureCounterpartyPrivateTargets(
        targets: Map<String, GenericContainer<*>>,
        sphereonHttpsTarget: GenericContainer<*> = gateway,
        requiredAuthorities: Set<String> = setOf("lighthouse.test:443"),
    ) {
        check(!closed) { "interop environment is closed" }
        check(!counterpartyTargetsConfigured) { "counterparty target snapshot has already been configured" }
        check(selectedKeyId == null && runtimeStateChecks.isEmpty()) {
            "counterparty operator configuration must precede federation provisioning"
        }
        val snapshot = targets.entries.map { it.key to it.value }
        require(snapshot.isNotEmpty()) { "at least one counterparty target is required" }
        val authorities = snapshot.map { (authority, _) -> authority }
        require(authorities.distinct().size == authorities.size) { "counterparty authorities must be unique" }
        require(authorities.none { it == "sphereon.test:443" }) { "Sphereon HTTPS target is selected separately" }
        require(authorities.all(::isSupportedCounterpartyAuthority)) { "counterparty authority is not allowlisted" }
        require("lighthouse.test:443" in requiredAuthorities) {
            "the target snapshot must require the Lighthouse peer"
        }
        require(requiredAuthorities.all(::isSupportedCounterpartyAuthority)) {
            "required target authority is not allowlisted"
        }
        require(authorities.containsAll(requiredAuthorities)) {
            "the target snapshot is missing a required peer authority"
        }

        val docker = DockerClientFactory.instance().client()
        val ownNetworkId = network.getId()
        fun ownNetworkIpv4(container: GenericContainer<*>): String {
            val containerId = requireNotNull(container.containerId) { "counterparty container has no Docker identity" }
            val inspected = docker.inspectContainerCmd(containerId).exec()
            check(inspected.getState()?.getRunning() == true) { "counterparty container must be running" }
            val ownedEndpoints = inspected.getNetworkSettings()?.getNetworks()?.values
                ?.filter { it.getNetworkID() == ownNetworkId }
                .orEmpty()
            check(ownedEndpoints.size == 1) {
                "counterparty must have exactly one endpoint on the owned interop network"
            }
            val address = ownedEndpoints.single().getIpAddress()
                ?: error("counterparty has no owned-network IPv4 address")
            val parts = address.split('.')
            require(parts.size == 4) { "counterparty requires an IPv4 address" }
            val octets = parts.map { part ->
                val value = part.toIntOrNull()
                require(value != null && value in 0..255 && value.toString() == part) {
                    "counterparty IPv4 address is not canonical"
                }
                value
            }
            require(
                octets[0] == 10 ||
                    (octets[0] == 172 && octets[1] in 16..31) ||
                    (octets[0] == 192 && octets[1] == 168),
            ) { "container target must use an RFC1918 IPv4 address" }
            return address
        }

        val resolved = snapshot.associate { (authority, container) -> authority to ownNetworkIpv4(container) }
        val sphereonAddress = ownNetworkIpv4(sphereonHttpsTarget)
        val orderedTargets = (resolved + ("sphereon.test:443" to sphereonAddress)).toSortedMap()
        require(orderedTargets.values.distinct().size == orderedTargets.size) {
            "counterparty authorities must resolve to distinct owned peer endpoints"
        }
        val targetsValue = orderedTargets.entries.joinToString(";") { (authority, address) -> "$authority=$address" }
        val configuration = kmsConfiguration(kmsPassword) + "\n" +
            """
            oidf:
              client:
                counterparty:
                  egress:
                    private:
                      ipv4:
                        targets: '$targetsValue'
            """.trimIndent() + "\n"

        // Freeze the complete map before mutating the copied Sphereon startup configuration.
        // A partial failure aborts this environment; a later call may not replace the snapshot.
        counterpartyTargetsConfigured = true
        val servers = listOf(publicServer, adminServer)
        servers.forEach { server ->
            val id = requireNotNull(server.containerId) { "Sphereon container has no Docker identity" }
            docker.stopContainerCmd(id).withTimeout(10).exec()
        }
        servers.forEach { server ->
            server.copyFileToContainer(
                Transferable.of(configuration.toByteArray(StandardCharsets.UTF_8), 0b110000000),
                "/app/config/application.yaml",
            )
        }
        servers.forEach { server ->
            val id = requireNotNull(server.containerId) { "Sphereon container has no Docker identity" }
            docker.startContainerCmd(id).exec()
        }
        awaitHttps("https://sphereon.test/health")
        awaitHttps("https://admin.test/health")
    }

    private fun isSupportedCounterpartyAuthority(authority: String): Boolean {
        val splitAt = authority.lastIndexOf(':')
        if (splitAt <= 0 || splitAt == authority.lastIndex) return false
        val host = authority.substring(0, splitAt)
        val portText = authority.substring(splitAt + 1)
        val port = portText.toIntOrNull() ?: return false
        if (port !in 1..65535 || port.toString() != portText) return false
        return when (host) {
            "lighthouse.test" -> port == 443
            "fedservice.test" -> port == 7003
            "offa.test" -> port == 443
            else -> false
        }
    }

    /** Retain bounded, redacted Sphereon server logs outside the normal interop evidence directory. */
    fun retainSafeServerErrorLogs(scenario: String): Path {
        require(scenario in setOf("sphereon-resolves-under-lighthouse-anchor", "lighthouse-resolves-under-sphereon-anchor", "lighthouse-kms-key-rollover-overlap", "trustmark-filtering", "two-level-policy"))
        val directory = workRoot.parent.resolve("private-diagnostics").normalize()
        require(directory.parent == workRoot.parent) { "private diagnostics path escaped the interop build directory" }
        Files.createDirectories(directory)

        val output = directory.resolve("$scenario-${System.currentTimeMillis()}.log")
        val logs = listOf("admin" to adminServer, "public" to publicServer)
        val content = logs.joinToString("\n") { (name, container) ->
            val raw = container.getLogs()
            val capturedAt = Instant.now()
            val redactedLog = redactDiagnosticSecrets(raw)
            val lines = redactedLog.lineSequence().toList().takeLast(400).map { line ->
                line.take(2_048)
            }
            "--- $name server capturedAt=$capturedAt (last ${lines.size} lines) ---\n${lines.joinToString("\n")}"
        }
        Files.writeString(output, content, StandardCharsets.UTF_8)
        System.err.println("Sanitized interop server diagnostics retained outside the evidence directory: $output")
        return output
    }

    /** Retain a bounded, redacted failed response for the one known core bootstrap operation. */
    fun retainSafeHttpFailure(
        scenario: String,
        method: String,
        path: String,
        response: InteropHttpResponse,
    ): Path {
        require(scenario in setOf(
            "sphereon-under-lighthouse-initial-publication",
            "sphereon-under-sphereon-initial-publication",
            "sphereon-under-lighthouse-resolve",
        )) { "unsupported private HTTP diagnostic scenario" }
        require(
            (method == "POST" && path == "/entity-statement") ||
                (method == "GET" && path == "/resolve" && scenario == "sphereon-under-lighthouse-resolve"),
        ) { "private HTTP diagnostics are restricted to initial publication and one pinned resolve request" }
        require(response.status !in 200..299) { "private HTTP diagnostics only retain failed responses" }

        val directory = workRoot.parent.resolve("private-diagnostics").normalize()
        require(directory.parent == workRoot.parent) { "private diagnostics path escaped the interop build directory" }
        Files.createDirectories(directory)
        val output = directory.resolve("$scenario-${System.currentTimeMillis()}.http.log")
        val safeLines = redactDiagnosticSecrets(response.body)
            .lineSequence()
            .take(MAX_PRIVATE_HTTP_DIAGNOSTIC_LINES)
            .map { it.take(MAX_PRIVATE_HTTP_DIAGNOSTIC_LINE_CHARS) }
            .toList()
        val content = buildString {
            appendLine("scenario=$scenario")
            appendLine("request=$method $path")
            appendLine("status=${response.status}")
            appendLine("redactedResponseBodyLines=${safeLines.size}")
            safeLines.forEach(::appendLine)
        }
        Files.writeString(output, content, StandardCharsets.UTF_8)
        System.err.println("Redacted failed interop response retained outside the evidence directory: $output")
        return output
    }

    /** Retain bounded redacted curl stderr for a failed unframed DELETE and its diagnostic control. */
    fun retainSafeAdminDeleteWireFailure(
        path: String,
        baseline: InteropHttpResponse,
        zeroLengthControl: InteropHttpResponse?,
        zeroLengthControlAttempted: Boolean,
    ): Path {
        require(isSubordinateDeletePath(path)) { "unsupported private DELETE diagnostic path" }
        require(baseline.status !in 200..299 || baseline.curlExitCode != 0) {
            "private DELETE diagnostics only retain a failed baseline request"
        }
        val directory = workRoot.parent.resolve("private-diagnostics").normalize()
        require(directory.parent == workRoot.parent) { "private diagnostics path escaped the interop build directory" }
        Files.createDirectories(directory)
        val output = directory.resolve("sphereon-subordinate-delete-${System.currentTimeMillis()}.wire.log")
        fun safeWire(response: InteropHttpResponse?): List<String> = response?.curlVerboseStderr
            .orEmpty()
            .let(::redactDiagnosticSecrets)
            .lineSequence()
            .filter { line ->
                line.startsWith("> ") || line.startsWith("< ") || line.startsWith("curl:") ||
                    line.startsWith("* Empty reply") || line.startsWith("* Closing connection") ||
                    line.startsWith("* Recv failure") || line.startsWith("* Send failure") || line.startsWith("* Failed")
            }
            .toList()
            .takeLast(MAX_PRIVATE_HTTP_DIAGNOSTIC_LINES)
            .map { it.take(MAX_PRIVATE_HTTP_DIAGNOSTIC_LINE_CHARS) }
        val baselineLines = safeWire(baseline)
        val controlLines = safeWire(zeroLengthControl)
        val content = buildString {
            appendLine("scenario=sphereon-subordinate-withdrawal")
            appendLine("request=DELETE /subordinates/{id}")
            appendLine("baselineFraming=bodyless-without-explicit-content-length")
            appendLine("baselineRequestStartedAt=${baseline.requestStartedAt}")
            appendLine("baselineRequestEndedAt=${baseline.requestEndedAt}")
            appendLine("baselineHttpStatus=${baseline.status.takeIf { it > 0 } ?: "none"}")
            appendLine("baselineCurlExitCode=${baseline.curlExitCode}")
            appendLine("baselineCurlVerboseStderrLines=${baselineLines.size}")
            baselineLines.forEach(::appendLine)
            appendLine("zeroLengthControlAttempted=$zeroLengthControlAttempted")
            appendLine("zeroLengthControlFraming=bodyless-with-explicit-content-length-zero")
            appendLine("zeroLengthControlRequestStartedAt=${zeroLengthControl?.requestStartedAt ?: "not-run"}")
            appendLine("zeroLengthControlRequestEndedAt=${zeroLengthControl?.requestEndedAt ?: "not-run"}")
            appendLine("zeroLengthControlHttpStatus=${zeroLengthControl?.status?.takeIf { it > 0 } ?: "none"}")
            appendLine("zeroLengthControlCurlExitCode=${zeroLengthControl?.curlExitCode ?: "not-run"}")
            appendLine("zeroLengthControlCurlVerboseStderrLines=${controlLines.size}")
            controlLines.forEach(::appendLine)
        }
        Files.writeString(output, content, StandardCharsets.UTF_8)
        System.err.println("Redacted private DELETE wire diagnostics retained outside the evidence directory: $output")
        return output
    }

    private fun redactDiagnosticSecrets(line: String): String {
        var redacted = line
        diagnosticSecrets.filter { it.isNotBlank() }.forEach { secret ->
            redacted = redacted.replace(secret, "<redacted-secret>")
        }
        redacted = redacted
            .replace(PEM_BLOCK, "<redacted-pem>")
            .replace(BEARER_TOKEN, "Bearer <redacted>")
            .replace(JWT_VALUE, "<redacted-jwt>")
            .replace(PRIVATE_JWK_FIELD, "\"<private-jwk-field>\":\"<redacted>\"")
        return redacted.lineSequence().joinToString("\n") { safeLine ->
            if (SENSITIVE_LOG_LINE.containsMatchIn(safeLine)) "<redacted sensitive log line>" else safeLine
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        listOf(adminServer, publicServer, gateway, keycloak, postgres, client).forEach { container ->
            try {
                container.stop()
            } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure!!.addSuppressed(cause)
            }
        }
        try {
            network.close()
        } catch (cause: Throwable) {
            if (failure == null) failure = cause else failure!!.addSuppressed(cause)
        }
        try {
            deleteOwnedWorkDirectory(workRoot, workDirectory)
        } catch (cause: Throwable) {
            if (failure == null) failure = cause else failure!!.addSuppressed(cause)
        }
        failure?.let { throw it }
    }

    private fun awaitHttps(url: String) {
        val deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val response = curlRequest("GET", url, null, null, null)
                if (response.status == 200) return
                lastFailure = IllegalStateException("HTTPS health endpoint returned HTTP ${response.status}")
            } catch (cause: Throwable) {
                lastFailure = cause
            }
            Thread.sleep(HEALTH_POLL.toMillis())
        }
        throw IllegalStateException("Sphereon HTTPS endpoint did not become healthy: $url", lastFailure)
    }

    private fun curlRequest(
        method: String,
        url: String,
        body: String?,
        bearer: String?,
        account: String?,
        form: List<String> = emptyList(),
        captureWire: Boolean = false,
        explicitZeroLengthBody: Boolean = false,
    ): InteropHttpResponse {
        require(url.startsWith("https://")) { "interop requests must use HTTPS" }
        require(url.length <= MAX_URL_LENGTH) { "interop URL exceeds the length limit" }
        require(method.matches(Regex("[A-Z]{3,10}"))) { "invalid HTTP method" }
        require(body == null || body.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "interop request body exceeds the 1 MiB limit"
        }
        require(account == null || (account.length <= 256 && account.none(Char::isISOControl))) {
            "invalid account header value"
        }
        require(bearer == null || (bearer.length <= 16_384 && bearer.none(Char::isISOControl))) {
            "invalid bearer token value"
        }
        val command = mutableListOf(
            "curl", "--silent", "--show-error", "--max-time", "20", "--connect-timeout", "5",
            "--max-filesize", MAX_RESPONSE_BYTES.toString(), "--cacert", "/run/interop/ca.pem",
            "--request", method, "--write-out", "\n__INTEROP_HTTP_STATUS__:%{http_code}",
        )
        if (captureWire) command += "--verbose"
        if (bearer != null) command += listOf("--header", "Authorization: Bearer $bearer")
        if (account != null) command += listOf("--header", "X-Account-Username: $account")
        if (explicitZeroLengthBody) {
            require(captureWire && method == "DELETE" && body == null && form.isEmpty()) {
                "explicit zero-length framing is restricted to the diagnostic bodyless DELETE control"
            }
            command += listOf("--header", "Content-Length: 0")
        }
        if (body != null) command += listOf("--header", "Content-Type: application/json", "--data-binary", body)
        form.forEach { command += listOf("--data-urlencode", it) }
        command += url
        val requestStartedAt = Instant.now()
        val result = client.execInContainer(*command.toTypedArray())
        val requestEndedAt = Instant.now()
        val marker = "\n__INTEROP_HTTP_STATUS__:"
        val markerAt = result.stdout.lastIndexOf(marker)
        if (!captureWire) {
            check(result.exitCode == 0 && markerAt >= 0) {
                "bounded HTTPS request failed (curl exit=${result.exitCode})"
            }
        }
        val responseBody = if (markerAt >= 0) result.stdout.substring(0, markerAt) else ""
        require(responseBody.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "interop response exceeded the 1 MiB limit"
        }
        val status = if (markerAt >= 0) result.stdout.substring(markerAt + marker.length).trim().toIntOrNull() else null
        if (!captureWire) requireNotNull(status) { "curl did not return an HTTP status" }
        return InteropHttpResponse(
            status = status ?: 0,
            body = responseBody,
            curlExitCode = result.exitCode,
            curlVerboseStderr = if (captureWire) result.stderr else null,
            requestStartedAt = requestStartedAt,
            requestEndedAt = requestEndedAt,
        )
    }

    private fun isSubordinateDeletePath(path: String): Boolean =
        path.matches(Regex("/subordinates/[A-Za-z0-9._~-]{1,128}"))

    companion object {
        const val PUBLIC_ENTITY_ID = "https://sphereon.test"
        const val STATEMENT_LIFETIME_SECONDS = 3600L
        private const val MAX_RESPONSE_BYTES = 1_048_576
        private const val MAX_URL_LENGTH = 8_192
        private const val MAX_PRIVATE_HTTP_DIAGNOSTIC_LINES = 40
        private const val MAX_PRIVATE_HTTP_DIAGNOSTIC_LINE_CHARS = 512
        private val JWT_VALUE = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])")
        private val BEARER_TOKEN = Regex("(?i)Bearer\\s+\\S+")
        private val PEM_BLOCK = Regex("-----BEGIN [^-]+-----[\\s\\S]*?-----END [^-]+-----")
        private val PRIVATE_JWK_FIELD = Regex("(?i)\"(?:d|p|q|dp|dq|qi|k|oth)\"\\s*:\\s*(?:\"(?:\\\\.|[^\"])*\"|\\[[^\\]]*\\]|\\{[^}]*\\}|[^,}\\s]+)")
        private val SENSITIVE_LOG_LINE = Regex("""(?i)(?:password|passphrase|secret|credential|authorization|bearer|cookie|private[_ -]?key|client[_-]?secret|api[_-]?key|(?:access|refresh|id)[_-]?token)["']?\s*[:=]""")
        private val HEALTH_TIMEOUT = Duration.ofSeconds(90)
        private val HEALTH_POLL = Duration.ofMillis(500)

        fun start(): InteropEnvironment {
            val parent = Path.of(System.getProperty("interop.work.directory")
                ?: error("Set -Dinterop.work.directory to a disposable build directory"))
            Files.createDirectories(parent)
            val workRoot = parent.toRealPath()
            val work = Files.createTempDirectory(parent, "sphereon-interop-")
            var allocatedNetwork: Network? = null
            val owned = mutableListOf<GenericContainer<*>>()
            var env: InteropEnvironment? = null
            try {
                val network = InteropNetwork.create().also { allocatedNetwork = it }
                val material = PeerTlsCertificateFactory.create("sphereon.test", "admin.test", "auth.test", "lighthouse.test", "fedservice.test", "offa.test")
                val tlsDirectory = Files.createDirectories(work.resolve("tls"))
                val caPath = tlsDirectory.resolve("ca.pem")
                val certPath = tlsDirectory.resolve("leaf.pem")
                val keyPath = tlsDirectory.resolve("leaf-key.pem")
                Files.writeString(caPath, material.caPem)
                Files.writeString(certPath, material.leafPem)
                Files.writeString(keyPath, material.keyPem)
                val trustStorePassword = randomSecret()
                val trustStorePath = tlsDirectory.resolve("truststore.p12")
                createTrustStore(material.caPem, trustStorePassword, trustStorePath)

                val client = container("curl", network)
                    .withNetworkAliases("interop-curl")
                    .withCopyFileToContainer(MountableFile.forHostPath(caPath), "/run/interop/ca.pem")
                    .withCreateContainerCmdModifier { cmd -> cmd.withEntrypoint("/bin/sh", "-c").withCmd("sleep 2147483647") }
                owned += client
                client.start()

                val dbPassword = randomSecret()
                val postgres = container("postgres", network)
                    .withNetworkAliases("interop-postgres")
                    .withEnv("POSTGRES_DB", "openid-federation-db")
                    .withEnv("POSTGRES_USER", "openid-federation-db-user")
                    .withEnv("POSTGRES_PASSWORD", dbPassword)
                    .withLogConsumer { }
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 1))
                owned += postgres
                postgres.start()

                val clientSecret = randomSecret()
                val keycloakAdminPassword = randomSecret()
                val realmFile = work.resolve("interop-realm.json")
                Files.writeString(realmFile, keycloakRealm(clientSecret), StandardCharsets.UTF_8)
                val keycloak = container("keycloak", network)
                    .withNetworkAliases("interop-keycloak")
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", keycloakAdminPassword)
                    .withEnv("KC_HOSTNAME", "https://auth.test")
                    .withEnv("KC_HOSTNAME_STRICT", "false")
                    .withEnv("KC_HTTP_ENABLED", "true")
                    .withEnv("KC_PROXY_HEADERS", "xforwarded")
                    .withEnv("KC_HEALTH_ENABLED", "true")
                    .withEnv("JAVA_OPTS_KC_HEAP", "-Xms64m -Xmx512m")
                    .withCopyFileToContainer(MountableFile.forHostPath(realmFile), "/opt/keycloak/data/import/interop-realm.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forLogMessage(".*Listening on: http://0.0.0.0:8080.*\\n", 1))
                owned += keycloak
                keycloak.start()

                val nginxConfig = resourcePath("/interop/sphereon/nginx.conf")
                val gateway = container("nginx", network)
                    .withNetworkAliases("sphereon.test", "admin.test", "auth.test", "interop-gateway")
                    .withCopyFileToContainer(MountableFile.forHostPath(certPath), "/run/interop/leaf.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(keyPath), "/run/interop/leaf-key.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(nginxConfig), "/etc/nginx/conf.d/default.conf")
                owned += gateway
                // Variable-based proxy_pass with Docker's embedded resolver lets upstream
                // aliases appear after Nginx starts, while supporting eager issuer discovery.
                gateway.start()
                awaitReady(client, "https://auth.test/realms/interop/.well-known/openid-configuration")

                val adminDistribution = distribution("interop.admin.distribution")
                val publicDistribution = distribution("interop.public.distribution")
                val adminLib = adminDistribution.resolve("lib")
                val publicLib = publicDistribution.resolve("lib")
                require(Files.isDirectory(adminLib)) { "Admin installDist lib directory not found: $adminLib" }
                require(Files.isDirectory(publicLib)) { "Public installDist lib directory not found: $publicLib" }
                val kmsPassword = randomSecret()
                val applicationConfig = work.resolve("application.yaml")
                Files.writeString(applicationConfig, kmsConfiguration(kmsPassword), StandardCharsets.UTF_8)

                val baseEnv = mapOf(
                    "OIDF_FEDERATION_ROOT_IDENTIFIER" to PUBLIC_ENTITY_ID,
                    "OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS" to STATEMENT_LIFETIME_SECONDS.toString(),
                    "OIDF_IDENTITY_MODE" to "account",
                    "OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM" to "azp",
                    "OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS" to "interop-admin",
                    "OIDF_OAUTH2_ISSUER_URI" to "https://auth.test/realms/interop",
                    "OIDF_OAUTH2_AUDIENCE" to "openid-federation-admin",
                    "OIDF_OAUTH2_JWT_AUTH_ENABLED" to "true",
                    "OIDF_DATASOURCE_URL" to "jdbc:postgresql://interop-postgres:5432/openid-federation-db",
                    "OIDF_DATASOURCE_USER" to "openid-federation-db-user",
                    "OIDF_DATASOURCE_PASSWORD" to dbPassword,
                    "OIDF_KMS_DEFAULT_PROVIDER" to "interop",
                )
                val publicServer = appContainer(
                    network = network,
                    image = PeerLock.loadHarnessImage("jre"),
                    libDirectory = publicLib,
                    mainClass = "com.sphereon.openid.fed.server.federation.ktor.ApplicationKt",
                    env = baseEnv + ("OIDF_SERVER_FEDERATION_PORT" to "8080"),
                    keystorePath = work.resolve("keystore"),
                    trustStorePath = trustStorePath,
                    trustStorePassword = trustStorePassword,
                    applicationConfig = applicationConfig,
                ).withNetworkAliases("interop-sphereon-public")
                owned += publicServer
                publicServer.start()
                awaitReady(client, "https://sphereon.test/health")

                val adminServer = appContainer(
                    network = network,
                    image = PeerLock.loadHarnessImage("jre"),
                    libDirectory = adminLib,
                    mainClass = "com.sphereon.openid.fed.server.admin.ktor.KtorAdminServerKt",
                    env = baseEnv + ("OIDF_SERVER_ADMIN_PORT" to "8081"),
                    keystorePath = work.resolve("keystore"),
                    trustStorePath = trustStorePath,
                    trustStorePassword = trustStorePassword,
                    applicationConfig = applicationConfig,
                ).withNetworkAliases("interop-sphereon-admin")
                owned += adminServer
                adminServer.start()
                awaitReady(client, "https://admin.test/health")

                env = InteropEnvironment(
                    network, material, client, gateway, keycloak, postgres, publicServer, adminServer,
                    work, workRoot, work.resolve("keystore"), kmsPassword, adminLib, publicLib, "interop-admin", clientSecret,
                    listOf(dbPassword, clientSecret, keycloakAdminPassword, trustStorePassword, kmsPassword),
                )
                env.awaitHttps("https://admin.test/health")
                return env
            } catch (failure: Throwable) {
                if (env != null) {
                    try { env.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                } else {
                    owned.asReversed().forEach { container ->
                        try { container.stop() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    try { allocatedNetwork?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    try { deleteOwnedWorkDirectory(workRoot, work) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }

        private fun container(imageKey: String, network: Network): InteropContainer =
            InteropContainer(DockerImageName.parse(PeerLock.loadHarnessImage(imageKey))).withNetwork(network)

        private fun appContainer(
            network: Network,
            image: String,
            libDirectory: Path,
            mainClass: String,
            env: Map<String, String>,
            keystorePath: Path,
            trustStorePath: Path,
            trustStorePassword: String,
            applicationConfig: Path,
        ): InteropContainer {
            Files.createDirectories(keystorePath)
            return InteropContainer(DockerImageName.parse(image)).withNetwork(network)
                .withFileSystemBind(libDirectory.toAbsolutePath().toString(), "/app/lib", BindMode.READ_ONLY)
                .withFileSystemBind(keystorePath.toAbsolutePath().toString(), "/keystore", BindMode.READ_WRITE)
                .withFileSystemBind(trustStorePath.toAbsolutePath().toString(), "/run/interop/truststore.p12", BindMode.READ_ONLY)
                .withCopyFileToContainer(MountableFile.forHostPath(applicationConfig), "/app/config/application.yaml")
                .withEnv(env)
                .withCommand(
                    "java", "-Xms64m", "-Xmx512m",
                    "-Djavax.net.ssl.trustStore=/run/interop/truststore.p12",
                    "-Djavax.net.ssl.trustStorePassword=$trustStorePassword", "-Djavax.net.ssl.trustStoreType=PKCS12",
                    "-Dkms.software.keystore-root=/keystore",
                    "-Dcom.sphereon.config.location=/app/config",
                    "-cp", "/app:/app/lib/*", mainClass,
                )
        }

        private fun distribution(property: String): Path = Path.of(System.getProperty(property)
            ?: error("Set -D$property to the production installDist directory"))
            .toAbsolutePath().normalize()

        private fun resourcePath(resource: String): Path {
            val url = InteropEnvironment::class.java.getResource(resource)
                ?: error("Missing interop resource $resource")
            return Path.of(url.toURI())
        }

        private fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        private fun createTrustStore(caPem: String, password: String, path: Path) {
            val certificate = PEMParser(StringReader(caPem)).use { parser ->
                JcaX509CertificateConverter().getCertificate(parser.readObject() as X509CertificateHolder)
            }
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, password.toCharArray())
            store.setCertificateEntry("interop-test-ca", certificate)
            Files.newOutputStream(path).use { store.store(it, password.toCharArray()) }
        }

        private fun hashJarDirectory(directory: Path): String {
            require(Files.isDirectory(directory)) { "installDist runtime directory is missing: $directory" }
            val jars = Files.list(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .sorted(compareBy { it.fileName.toString() })
                    .toList()
            }
            require(jars.isNotEmpty()) { "installDist has no runtime JARs: $directory" }
            val digest = MessageDigest.getInstance("SHA-256")
            jars.forEach { jar ->
                val name = jar.fileName.toString().toByteArray(StandardCharsets.UTF_8)
                digest.update(name)
                digest.update(0.toByte())
                Files.newInputStream(jar).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.update(0.toByte())
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun awaitReady(client: GenericContainer<*>, url: String) {
            val deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos()
            var lastStatus = "no response"
            while (System.nanoTime() < deadline) {
                val result = client.execInContainer(
                    "curl", "--silent", "--show-error", "--max-time", "10", "--connect-timeout", "3",
                    "--cacert", "/run/interop/ca.pem", "--output", "/dev/null", "--write-out", "%{http_code}", url,
                )
                if (result.exitCode == 0 && result.stdout.trim() == "200") return
                lastStatus = if (result.exitCode == 0) "HTTP ${result.stdout.trim()}" else "curl exit ${result.exitCode}"
                Thread.sleep(HEALTH_POLL.toMillis())
            }
            error("HTTPS service did not become ready at $url ($lastStatus)")
        }

        private fun kmsConfiguration(password: String): String = """
            kms:
              providers:
                interop:
                  type: software
                  id: interop
                  enabled: true
                  autoCreateCertificate: true
                  keystore:
                    type: pkcs12
                    id: interop
                    password: "$password"
                    path: /keystore/interop.p12
                    persist: true
        """.trimIndent()

        private fun deleteOwnedWorkDirectory(root: Path, candidate: Path) {
            val normalizedRoot = root.toRealPath()
            val absoluteCandidate = candidate.toAbsolutePath().normalize()
            require(absoluteCandidate.parent == normalizedRoot) { "interop temp directory escaped its configured build root" }
            require(!Files.isSymbolicLink(absoluteCandidate)) { "interop temp directory became a symbolic link" }
            if (!Files.exists(absoluteCandidate)) return
            val realCandidate = absoluteCandidate.toRealPath()
            require(realCandidate.parent == normalizedRoot) { "interop temp directory resolved outside its configured build root" }
            Files.walkFileTree(realCandidate, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            check(!Files.exists(absoluteCandidate)) { "interop temp directory cleanup was incomplete" }
        }

        private fun keycloakRealm(secret: String): String = """
            {
              "realm": "interop",
              "enabled": true,
              "sslRequired": "none",
              "clients": [{
                "clientId": "interop-admin",
                "name": "interop-admin",
                "enabled": true,
                "protocol": "openid-connect",
                "publicClient": false,
                "secret": "$secret",
                "serviceAccountsEnabled": true,
                "standardFlowEnabled": false,
                "directAccessGrantsEnabled": false,
                "authorizationServicesEnabled": false,
                "attributes": {
                  "access.token.header.type.rfc9068": "true"
                },
                "defaultClientScopes": ["web-origins", "acr", "roles", "profile", "email"],
                "protocolMappers": [{
                  "name": "openid-federation-admin audience",
                  "protocol": "openid-connect",
                  "protocolMapper": "oidc-audience-mapper",
                  "consentRequired": false,
                  "config": {
                    "included.custom.audience": "openid-federation-admin",
                    "access.token.claim": "true",
                    "id.token.claim": "false"
                  }
                }]
              }]
            }
        """.trimIndent()

        private class InteropContainer(image: DockerImageName) : GenericContainer<InteropContainer>(image)
    }
}

data class InteropHttpResponse(
    val status: Int,
    val body: String,
    val curlExitCode: Int = 0,
    internal val curlVerboseStderr: String? = null,
    internal val requestStartedAt: Instant? = null,
    internal val requestEndedAt: Instant? = null,
)

