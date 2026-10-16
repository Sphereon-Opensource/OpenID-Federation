package com.sphereon.openid.fed.interop.harness

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.nimbusds.jose.util.JSONObjectUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64

private class OidcContainer(image: DockerImageName) : GenericContainer<OidcContainer>(image)

/**
 * Isolated, real-process OFFA browser-flow lane. The OP host is the combined OIDF+IDK
 * application from the adjacent candidate module. It occupies sphereon.test:443 directly;
 * the TLS Nginx gateway owns only admin.test and auth.test.
 *
 * This environment is deliberately separate from [InteropEnvironment]: its sphereon.test
 * listener is the combined OP and must not coexist with the core lane's public-server listener.
 */
internal class OidcInteropEnvironment private constructor(
    val network: Network,
    val client: GenericContainer<*>,
    val op: GenericContainer<*>,
    val offa: GenericContainer<*>,
    val browser: GenericContainer<*>,
    private val admin: GenericContainer<*>,
    private val postgres: GenericContainer<*>,
    private val keycloak: GenericContainer<*>,
    private val gateway: GenericContainer<*>,
    private val wrongCa: GenericContainer<*>,
    private val workDirectory: Path,
    private val workRoot: Path,
    private val tls: PeerTlsMaterial,
    private val tlsPassword: String,
    private val kmsPassword: String,
    private val dbPassword: String,
    private val clientSecret: String,
) : AutoCloseable {
    private var closed = false

    fun request(method: String, url: String, body: String? = null): InteropHttpResponse = curl(method, url, body)

    /** POST a form only to the discovered combined OP token endpoint over verified HTTPS. */
    fun tokenForm(url: String, fields: List<Pair<String, String>>): InteropHttpResponse {
        val endpoint = java.net.URI(url)
        require(endpoint.scheme == "https" && endpoint.host == "sphereon.test" && endpoint.path == "/token" &&
            endpoint.port in setOf(-1, 443) && endpoint.rawUserInfo == null && endpoint.query == null && endpoint.fragment == null) {
            "token replay control must use the discovered Sphereon token endpoint"
        }
        require(fields.isNotEmpty() && fields.all { it.first.matches(Regex("[a-z_]{1,64}")) })
        return curl("POST", url, null, form = fields.map { (name, value) -> "$name=$value" })
    }

    /** Reads one allowlisted, private in-container protocol observation without echoing it. */
    fun protocolEvidence(name: String): String {
        require(name in setOf("request-object.jwt", "client-assertion.jwt", "id-token.jwt", "rp-registration.json"))
        val result = op.execInContainer("cat", "/run/oidc-evidence/$name")
        check(result.exitCode == 0 && result.stdout.isNotBlank()) { "required private protocol observation is unavailable" }
        return result.stdout.trim()
    }

    /** Host-side Playwright connects only to browser RPC; page traffic stays on the Docker network. */
    fun browserEndpoint(): String {
        val endpointFile = workDirectory.resolve("browser-state/endpoint.txt")
        val endpoint = java.net.URI(Files.readString(endpointFile, StandardCharsets.UTF_8).trim())
        require(endpoint.scheme == "ws" && endpoint.rawPath.isNotBlank() && endpoint.query == null && endpoint.fragment == null) {
            "Playwright server returned an invalid WebSocket endpoint"
        }
        return "ws://127.0.0.1:${browser.getMappedPort(3000)}${endpoint.rawPath}"
    }

    /** Use the real Keycloak client-credentials grant and the production admin HTTP API. */
    fun admin(method: String, path: String, body: String? = null): InteropHttpResponse {
        require(path.startsWith('/') && !path.startsWith("//"))
        val token = curl(
            "POST",
            "https://auth.test/realms/interop/protocol/openid-connect/token",
            null,
            form = listOf(
                "grant_type=client_credentials",
                "client_id=interop-admin",
                "client_secret=$clientSecret",
            ),
        )
        check(token.status in 200..299) { "Keycloak service-account token request returned HTTP ${token.status}" }
        val bearer = JSONObjectUtils.getString(JSONObjectUtils.parse(token.body), "access_token")
        val commandBody = if (method == "POST" && body == null) "{}" else body
        return curl(method, "https://admin.test$path", commandBody, bearer = bearer)
    }

    fun awaitHttps(url: String) {
        val deadline = System.nanoTime() + HEALTH_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val result = curl("GET", url, null)
            if (result.status == 200) return
            Thread.sleep(500)
        }
        error("HTTPS service did not become ready at $url")
    }

    override fun close(): Unit = closeInternal()

    private fun closeInternal() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        listOf(browser, offa, admin, op, wrongCa, gateway, keycloak, postgres, client).forEach { container ->
            try { container.stop() } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure!!.addSuppressed(cause)
            }
        }
        try { network.close() } catch (cause: Throwable) {
            if (failure == null) failure = cause else failure!!.addSuppressed(cause)
        }
        try { deleteOwnedWorkDirectory(workRoot, workDirectory) } catch (cause: Throwable) {
            if (failure == null) failure = cause else failure!!.addSuppressed(cause)
        }
        failure?.let { throw it }
    }

    private fun curl(
        method: String,
        url: String,
        body: String?,
        bearer: String? = null,
        form: List<String> = emptyList(),
    ): InteropHttpResponse {
        require(url.startsWith("https://")) { "OIDC interop only permits verified HTTPS" }
        require(url.length <= 8192 && (body == null || body.toByteArray(StandardCharsets.UTF_8).size <= 1_048_576))
        val args = mutableListOf(
            "curl", "--silent", "--show-error", "--max-time", "20", "--connect-timeout", "5",
            "--cacert", "/run/interop/ca.pem", "--request", method,
            "--output", "-", "--write-out", "\n__INTEROP_HTTP_STATUS__:%{http_code}",
        )
        if (bearer != null) args += listOf("--header", "Authorization: Bearer $bearer")
        if (body != null) args += listOf("--header", "Content-Type: application/json", "--data-binary", body)
        form.forEach { args += listOf("--data-urlencode", it) }
        args += url
        val result = client.execInContainer(*args.toTypedArray())
        check(result.exitCode == 0) { "verified HTTPS request failed (curl exit=${result.exitCode})" }
        val marker = "\n__INTEROP_HTTP_STATUS__:"
        val at = result.stdout.lastIndexOf(marker)
        require(at >= 0) { "curl response omitted the HTTP status marker" }
        val responseBody = result.stdout.substring(0, at)
        require(responseBody.toByteArray(StandardCharsets.UTF_8).size <= 1_048_576)
        val status = result.stdout.substring(at + marker.length).trim().toIntOrNull()
            ?: error("curl returned an invalid HTTP status")
        return InteropHttpResponse(status, responseBody)
    }

    companion object {
        private const val HEALTH_TIMEOUT_NANOS = 90_000_000_000L
        private const val PLAYWRIGHT_IMAGE = "mcr.microsoft.com/playwright/java:v1.63.0-noble@sha256:013e2595272806f887d91041fbf26be71dda2f48a805c717cc7de3bbca5339c8"
        private const val OFFA_ENTITY_ID = "https://offa.test"
        private const val SPHEREON_ENTITY_ID = "https://sphereon.test"
        private const val OFFA_DEFAULT_OIDC_ALGORITHM = "ES512"
        private val SUPPORTED_OIDC_ALGORITHMS = setOf("RS256", "ES256", "ES384", "ES512")

        fun start(offaOidcAlgorithm: String? = null): OidcInteropEnvironment {
            require(offaOidcAlgorithm == null || offaOidcAlgorithm in SUPPORTED_OIDC_ALGORITHMS) {
                "unsupported OFFA/OP OIDC signing profile"
            }
            val signingAlgorithm = offaOidcAlgorithm ?: OFFA_DEFAULT_OIDC_ALGORITHM
            val parentPath = Path.of(System.getProperty("interop.work.directory")
                ?: error("Set -Dinterop.work.directory to a disposable build directory")).toAbsolutePath().normalize()
            Files.createDirectories(parentPath)
            val parent = parentPath.toRealPath()
            val work = Files.createTempDirectory(parent, "offa-oidc-interop-")
            var network: Network? = null
            val owned = mutableListOf<GenericContainer<*>>()
            var result: OidcInteropEnvironment? = null
            try {
                val networkValue = InteropNetwork.create().also { network = it }
                val material = PeerTlsCertificateFactory.create("sphereon.test", "admin.test", "auth.test", "offa.test")
                val tlsDir = Files.createDirectories(work.resolve("tls"))
                val ca = tlsDir.resolve("ca.pem")
                val leaf = tlsDir.resolve("leaf.pem")
                val leafKey = tlsDir.resolve("leaf-key.pem")
                Files.writeString(ca, material.caPem)
                Files.writeString(leaf, material.leafPem)
                Files.writeString(leafKey, material.keyPem)
                val tlsPassword = randomSecret()
                val tlsStore = tlsDir.resolve("server.p12")
                val trustStore = tlsDir.resolve("truststore.p12")
                createOpTlsStore(material, tlsPassword, tlsStore)
                createTrustStore(material, tlsPassword, trustStore)

                val client = container("curl", networkValue).withNetworkAliases("interop-curl")
                    .withCopyFileToContainer(MountableFile.forHostPath(ca), "/run/interop/ca.pem")
                    .withCreateContainerCmdModifier { cmd -> cmd.withEntrypoint("/bin/sh", "-c").withCmd("sleep 2147483647") }
                owned += client; client.start()

                val dbPassword = randomSecret()
                val postgres = container("postgres", networkValue).withNetworkAliases("interop-postgres")
                    .withEnv("POSTGRES_DB", "openid-federation-db")
                    .withEnv("POSTGRES_USER", "openid-federation-db-user")
                    .withEnv("POSTGRES_PASSWORD", dbPassword)
                    .withLogConsumer { }
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 1))
                owned += postgres; postgres.start()

                val clientSecret = randomSecret()
                val keycloakAdminPassword = randomSecret()
                val realm = work.resolve("interop-realm.json")
                Files.writeString(realm, keycloakRealm(clientSecret), StandardCharsets.UTF_8)
                val keycloak = container("keycloak", networkValue).withNetworkAliases("interop-keycloak")
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", keycloakAdminPassword)
                    .withEnv("KC_HOSTNAME", "https://auth.test")
                    .withEnv("KC_HOSTNAME_STRICT", "false")
                    .withEnv("KC_HTTP_ENABLED", "true")
                    .withEnv("KC_PROXY_HEADERS", "xforwarded")
                    .withEnv("KC_HEALTH_ENABLED", "true")
                    .withCopyFileToContainer(MountableFile.forHostPath(realm), "/opt/keycloak/data/import/interop-realm.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forLogMessage(".*Listening on: http://0.0.0.0:8080.*\\n", 1))
                owned += keycloak; keycloak.start()

                val gatewayConfig = work.resolve("nginx-admin-auth.conf")
                Files.writeString(gatewayConfig, nginxAdminAuthConfig(), StandardCharsets.UTF_8)
                val gateway = container("nginx", networkValue).withNetworkAliases("admin.test", "auth.test", "interop-gateway")
                    .withCopyFileToContainer(MountableFile.forHostPath(leaf), "/run/interop/leaf.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(leafKey), "/run/interop/leaf-key.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(gatewayConfig), "/etc/nginx/conf.d/default.conf")
                owned += gateway; gateway.start()

                val wrongCaMaterial = PeerTlsCertificateFactory.create("wrong-ca.test")
                val wrongCaDir = Files.createDirectories(work.resolve("wrong-ca"))
                val wrongCaLeaf = wrongCaDir.resolve("leaf.pem")
                val wrongCaKey = wrongCaDir.resolve("leaf-key.pem")
                Files.writeString(wrongCaLeaf, wrongCaMaterial.leafPem)
                Files.writeString(wrongCaKey, wrongCaMaterial.keyPem)
                val wrongCaConfig = wrongCaDir.resolve("nginx.conf")
                Files.writeString(wrongCaConfig, nginxWrongCaConfig(), StandardCharsets.UTF_8)
                val wrongCa = container("nginx", networkValue).withNetworkAliases("wrong-ca.test")
                    .withCopyFileToContainer(MountableFile.forHostPath(wrongCaLeaf), "/run/interop/leaf.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(wrongCaKey), "/run/interop/leaf-key.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(wrongCaConfig), "/etc/nginx/conf.d/default.conf")
                owned += wrongCa; wrongCa.start()

                val offaConfigDirectory = Files.createDirectories(work.resolve("offa-config"))
                Files.writeString(offaConfigDirectory.resolve("config.yaml"), offaConfiguration(offaOidcAlgorithm), StandardCharsets.UTF_8)
                val offaKeysDirectory = Files.createDirectories(work.resolve("offa-keys"))
                val offa = OidcContainer(DockerImageName.parse(PeerLock.load("offa").image)).withNetwork(networkValue).withNetworkAliases("offa.test")
                    .withFileSystemBind(offaConfigDirectory.toAbsolutePath().toString(), "/config", BindMode.READ_ONLY)
                    .withFileSystemBind(offaKeysDirectory.toAbsolutePath().toString(), "/data/keys", BindMode.READ_WRITE)
                    .withCopyFileToContainer(MountableFile.forHostPath(leaf), "/run/offa/leaf.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(leafKey), "/run/offa/leaf-key.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(ca), "/ca.pem")
                    .withEnv("SSL_CERT_FILE", "/ca.pem")
                    .withCommand("/entrypoint.sh")
                    .waitingFor(Wait.forListeningPort())
                owned += offa; offa.start()

                val opDistribution = distribution("interop.oidc.distribution")
                val adminDistribution = distribution("interop.admin.distribution")
                val opLib = opDistribution.resolve("lib")
                val adminLib = adminDistribution.resolve("lib")
                require(Files.isDirectory(opLib) && Files.isDirectory(adminLib)) { "OP/admin installDist library directory is missing" }
                val kmsPassword = randomSecret()
                val appConfig = work.resolve("application.yaml")
                Files.writeString(appConfig, kmsConfiguration(kmsPassword, signingAlgorithm), StandardCharsets.UTF_8)
                val baseEnv = mapOf(
                    "OIDF_FEDERATION_ROOT_IDENTIFIER" to SPHEREON_ENTITY_ID,
                    "OIDF_FEDERATION_STATEMENT_LIFETIME_SECONDS" to "3600",
                    "OIDF_IDENTITY_MODE" to "account",
                    "OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM" to "azp",
                    "OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS" to "interop-admin",
                    "OIDF_OAUTH2_ISSUER_URI" to "https://auth.test/realms/interop",
                    "OIDF_OAUTH2_AUDIENCE" to "openid-federation-admin",
                    "OIDF_OAUTH2_JWT_AUTH_ENABLED" to "true",
                    "OIDC_FEDERATION_TRUST_ANCHORS" to SPHEREON_ENTITY_ID,
                    "OIDC_FEDERATION_ALLOWED_REDIRECT_URIS" to "$OFFA_ENTITY_ID/redirect",
                    "OIDC_FEDERATION_ALLOWED_SCOPES" to "openid,profile,email",
                    "OIDC_FEDERATION_TOKEN_AUTH_ALGS" to signingAlgorithm,
                    "OIDC_FEDERATION_REQUEST_OBJECT_ALGS" to signingAlgorithm,
                    "OIDF_INTEROP_SIGNING_ALGORITHM" to signingAlgorithm,
                    "OIDF_DATASOURCE_URL" to "jdbc:postgresql://interop-postgres:5432/openid-federation-db",
                    "OIDF_DATASOURCE_USER" to "openid-federation-db-user",
                    "OIDF_DATASOURCE_PASSWORD" to dbPassword,
                    "OIDF_KMS_DEFAULT_PROVIDER" to "interop",
                    "SPHEREON_OAUTH2_SERVERS_DEFAULT_ISSUER" to SPHEREON_ENTITY_ID,
                    "OIDF_OP_TLS_KEYSTORE" to "/run/interop/server.p12",
                    "OIDF_OP_TLS_STORE_PASSWORD" to tlsPassword,
                    "OIDF_OP_TLS_KEY_PASSWORD" to tlsPassword,
                    "OIDF_OP_TLS_KEY_ALIAS" to "sphereon-https",
                )
                val op = opContainer(networkValue, PeerLock.loadHarnessImage("jre"), opLib,
                    baseEnv + ("OIDC_PROTOCOL_EVIDENCE_DIRECTORY" to "/run/oidc-evidence"),
                    work.resolve("keystore"), trustStore, tlsPassword, appConfig)
                    .withNetworkAliases("sphereon.test", "interop-sphereon-op")
                    .withTmpFs(mapOf("/run/oidc-evidence" to "rw,noexec,nosuid,nodev,size=1m"))
                    .withFileSystemBind(tlsStore.toAbsolutePath().toString(), "/run/interop/server.p12", BindMode.READ_ONLY)
                owned += op; op.start()
                awaitReady(client, ca, "https://sphereon.test/health")

                val opAddress = ownNetworkIpv4(op, networkValue)
                val offaAddress = ownNetworkIpv4(offa, networkValue)
                require(opAddress != offaAddress)
                Files.writeString(appConfig, kmsConfiguration(kmsPassword, signingAlgorithm) + "\n" + egressConfiguration(
                    "sphereon.test:443=$opAddress;offa.test:443=$offaAddress",
                ), StandardCharsets.UTF_8)
                val docker = DockerClientFactory.instance().client()
                val opId = requireNotNull(op.containerId)
                docker.stopContainerCmd(opId).withTimeout(10).exec()
                op.copyFileToContainer(MountableFile.forHostPath(appConfig), "/app/config/application.yaml")
                docker.startContainerCmd(opId).exec()
                awaitReady(client, ca, "https://offa.test/.well-known/openid-federation")
                awaitReady(client, ca, "https://sphereon.test/health")

                val admin = opContainer(networkValue, PeerLock.loadHarnessImage("jre"), adminLib, baseEnv + ("OIDF_SERVER_ADMIN_PORT" to "8081"),
                    work.resolve("keystore"), trustStore, tlsPassword, appConfig, mainClass = "com.sphereon.openid.fed.server.admin.ktor.KtorAdminServerKt")
                    .withNetworkAliases("interop-sphereon-admin")
                owned += admin; admin.start()
                awaitReady(client, ca, "https://admin.test/health")

                val browserDistribution = distribution("interop.browser.distribution")
                require(Files.isDirectory(browserDistribution.resolve("lib"))) {
                    "Playwright browser-server installDist library directory is missing"
                }
                val browserState = Files.createDirectories(work.resolve("browser-state"))
                val endpointFile = browserState.resolve("endpoint.txt")
                val browser = OidcContainer(DockerImageName.parse(PLAYWRIGHT_IMAGE)).withNetwork(networkValue)
                    .withNetworkAliases("interop-browser")
                    .withExposedPorts(3000)
                    .withCreateContainerCmdModifier { command ->
                        val hostConfig = requireNotNull(command.hostConfig) {
                            "Docker did not provide the browser container host configuration"
                        }
                        command.withHostConfig(hostConfig.withPortBindings(
                            PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort(3000)),
                        ))
                        command.withEntrypoint("/bin/sh", "-c")
                            .withCmd("update-ca-certificates && exec /app/bin/openid-federation-interop-browser-server")
                    }
                    .withFileSystemBind(browserDistribution.toAbsolutePath().toString(), "/app", BindMode.READ_ONLY)
                    .withFileSystemBind(browserState.toAbsolutePath().toString(), "/run/interop", BindMode.READ_WRITE)
                    .withCopyFileToContainer(MountableFile.forHostPath(ca), "/usr/local/share/ca-certificates/interop-ca.crt")
                    .waitingFor(Wait.forListeningPort())
                owned += browser; browser.start()
                awaitBrowserEndpoint(endpointFile)

                val environment = OidcInteropEnvironment(
                    networkValue, client, op, offa, browser, admin, postgres, keycloak, gateway, wrongCa,
                    work, parent, material, tlsPassword, kmsPassword, dbPassword, clientSecret,
                )
                
                result = environment
                return environment
            } catch (failure: Throwable) {
                if (result != null) runCatching { result!!.closeInternal() }.exceptionOrNull()?.let(failure::addSuppressed)
                else {
                    owned.asReversed().forEach { runCatching { it.stop() }.exceptionOrNull()?.let(failure::addSuppressed) }
                    runCatching { network?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                    runCatching { deleteOwnedWorkDirectory(parent, work) }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }

        private fun container(image: String, network: Network): GenericContainer<*> =
            OidcContainer(DockerImageName.parse(PeerLock.loadHarnessImage(image))).withNetwork(network)

        private fun opContainer(
            network: Network, image: String, lib: Path, env: Map<String, String>, keystore: Path,
            trustStore: Path, trustStorePassword: String, config: Path,
            mainClass: String = "com.sphereon.oauth2.oidf.op.OidfOpApplicationKt",
        ): GenericContainer<*> {
            Files.createDirectories(keystore)
            return OidcContainer(DockerImageName.parse(image)).withNetwork(network)
                .withFileSystemBind(lib.toAbsolutePath().toString(), "/app/lib", BindMode.READ_ONLY)
                .withFileSystemBind(keystore.toAbsolutePath().toString(), "/keystore", BindMode.READ_WRITE)
                .withFileSystemBind(trustStore.toAbsolutePath().toString(), "/run/interop/truststore.p12", BindMode.READ_ONLY)
                .withCopyFileToContainer(MountableFile.forHostPath(config), "/app/config/application.yaml")
                .withEnv(env)
                .withCommand(
                    "java", "-Xms64m", "-Xmx768m",
                    "-Djavax.net.ssl.trustStore=/run/interop/truststore.p12",
                    "-Djavax.net.ssl.trustStorePassword=$trustStorePassword",
                    "-Djavax.net.ssl.trustStoreType=PKCS12",
                    "-Dkms.software.keystore-root=/keystore",
                    "-Dcom.sphereon.config.location=/app/config",
                    "-cp", "/app:/app/lib/*", mainClass,
                )
        }

        private fun distribution(property: String): Path = Path.of(System.getProperty(property)
            ?: error("Set -D$property to a production installDist directory")).toAbsolutePath().normalize()

        private fun awaitReady(client: GenericContainer<*>, ca: Path, url: String) {
            val caInClient = "/run/interop/ca.pem"
            val deadline = System.nanoTime() + HEALTH_TIMEOUT_NANOS
            var status = "no response"
            while (System.nanoTime() < deadline) {
                val result = client.execInContainer("curl", "--silent", "--show-error", "--max-time", "10",
                    "--connect-timeout", "3", "--cacert", caInClient, "--output", "/dev/null", "--write-out", "%{http_code}", url)
                if (result.exitCode == 0 && result.stdout.trim() == "200") return
                status = if (result.exitCode == 0) "HTTP ${result.stdout.trim()}" else "curl exit ${result.exitCode}"
                Thread.sleep(500)
            }
            error("verified HTTPS service did not become ready at $url ($status)")
        }

        private fun awaitBrowserEndpoint(path: Path) {
            val deadline = System.nanoTime() + HEALTH_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(path) && runCatching { Files.readString(path).startsWith("ws://") }.getOrDefault(false)) return
                Thread.sleep(100)
            }
            error("Playwright browser server did not publish its private connection endpoint")
        }

        private fun ownNetworkIpv4(container: GenericContainer<*>, network: Network): String {
            val endpoints = DockerClientFactory.instance().client().inspectContainerCmd(requireNotNull(container.containerId)).exec()
                .networkSettings.networks.values.filter { it.networkID == network.getId() }
            check(endpoints.size == 1) { "container must have exactly one endpoint on the owned OIDC network" }
            val value = requireNotNull(endpoints.single().ipAddress)
            val octets = value.split('.').map { it.toIntOrNull() ?: error("network address is not canonical IPv4") }
            require(octets.size == 4 && octets.all { it in 0..255 })
            require(octets.joinToString(".") == value) { "network address is not canonical IPv4" }
            require(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168))
            return value
        }

        private fun createOpTlsStore(material: PeerTlsMaterial, password: String, path: Path) {
            val pem = PEMParser(StringReader(material.leafPem))
            val cert = try { JcaX509CertificateConverter().getCertificate(pem.readObject() as X509CertificateHolder) } finally { pem.close() }
            val caReader = PEMParser(StringReader(material.caPem))
            val ca = try { JcaX509CertificateConverter().getCertificate(caReader.readObject() as X509CertificateHolder) } finally { caReader.close() }
            val keyReader = PEMParser(StringReader(material.keyPem))
            val keyObject = try { keyReader.readObject() } finally { keyReader.close() }
            val converter = JcaPEMKeyConverter()
            val privateKey = when (keyObject) {
                is PEMKeyPair -> converter.getKeyPair(keyObject).private
                is PrivateKeyInfo -> converter.getPrivateKey(keyObject)
                else -> error("generated TLS key is not a supported PKCS#8 PEM object")
            }
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, password.toCharArray())
            store.setKeyEntry("sphereon-https", privateKey, password.toCharArray(), arrayOf(cert, ca))
            Files.newOutputStream(path).use { store.store(it, password.toCharArray()) }
        }

        private fun createTrustStore(material: PeerTlsMaterial, password: String, path: Path) {
            val reader = PEMParser(StringReader(material.caPem))
            val ca = try { JcaX509CertificateConverter().getCertificate(reader.readObject() as X509CertificateHolder) } finally { reader.close() }
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, password.toCharArray())
            store.setCertificateEntry("interop-ca", ca)
            Files.newOutputStream(path).use { store.store(it, password.toCharArray()) }
        }

        private fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        private fun kmsConfiguration(password: String, signingAlgorithm: String): String = """
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
            oauth2:
              servers:
                default:
                  signing-algorithms-supported: $signingAlgorithm
                  id-token-signing-alg-values-supported: $signingAlgorithm
                  request-object-signing-alg-values-supported: $signingAlgorithm
        """.trimIndent()

        private fun egressConfiguration(targets: String): String = """
            oidf:
              client:
                counterparty:
                  egress:
                    private:
                      ipv4:
                        targets: '$targets'
        """.trimIndent()

        private fun offaConfiguration(oidcAlgorithm: String?): String {
            val base = """
            server:
              ip_listen: 0.0.0.0
              tls:
                enabled: true
                redirect_http: false
                cert: /run/offa/leaf.pem
                key: /run/offa/leaf-key.pem
            federation:
              entity_id: https://offa.test
              client_name: OFFA Interop RP
              scopes: [openid, profile, email]
              trust_anchors:
                - entity_id: https://sphereon.test
            signing:
              key_storage: /data/keys
              federation:
                generate_keys: true
                alg: ES512
              oidc:
                generate_keys: true
            """.trimIndent()
            if (oidcAlgorithm == null) return base
            require(oidcAlgorithm in SUPPORTED_OIDC_ALGORITHMS)
            return base.replace(
                "  oidc:\n    generate_keys: true\n",
                "  oidc:\n    generate_keys: true\n    default_alg: $oidcAlgorithm\n    algs: [$oidcAlgorithm]\n",
            )
        }

        private fun nginxAdminAuthConfig(): String = """
            server {
              listen 443 ssl;
              server_name admin.test;
              resolver 127.0.0.11 valid=1s ipv6=off;
              ssl_certificate /run/interop/leaf.pem;
              ssl_certificate_key /run/interop/leaf-key.pem;
              ssl_protocols TLSv1.2 TLSv1.3;
              location / { proxy_pass http://interop-sphereon-admin:8081; proxy_set_header Host ${'$'}host; proxy_set_header X-Forwarded-Host ${'$'}host; proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for; }
            }
            server {
              listen 443 ssl;
              server_name auth.test;
              resolver 127.0.0.11 valid=1s ipv6=off;
              ssl_certificate /run/interop/leaf.pem;
              ssl_certificate_key /run/interop/leaf-key.pem;
              ssl_protocols TLSv1.2 TLSv1.3;
              location / { proxy_pass http://interop-keycloak:8080; proxy_set_header Host ${'$'}host; proxy_set_header X-Forwarded-Host ${'$'}host; proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for; }
            }
        """.trimIndent()

        private fun nginxWrongCaConfig(): String = """
            server {
              listen 443 ssl;
              server_name wrong-ca.test;
              ssl_certificate /run/interop/leaf.pem;
              ssl_certificate_key /run/interop/leaf-key.pem;
              ssl_protocols TLSv1.2 TLSv1.3;
              location / { return 200 'wrong CA endpoint'; }
            }
        """.trimIndent()

        private fun keycloakRealm(secret: String): String = """
            {"realm":"interop","enabled":true,"sslRequired":"none","clients":[{"clientId":"interop-admin","enabled":true,"protocol":"openid-connect","publicClient":false,"secret":"$secret","serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"authorizationServicesEnabled":false,"attributes":{"access.token.header.type.rfc9068":"true"},"defaultClientScopes":["web-origins","acr","roles","profile","email"],"protocolMappers":[{"name":"openid-federation-admin audience","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper","consentRequired":false,"config":{"included.custom.audience":"openid-federation-admin","access.token.claim":"true","id.token.claim":"false"}}]}]}
        """.trimIndent()

        private fun deleteOwnedWorkDirectory(root: Path, candidate: Path) {
            val realRoot = root.toRealPath()
            val normalized = candidate.toAbsolutePath().normalize()
            require(normalized.parent == realRoot && !Files.isSymbolicLink(normalized))
            if (!Files.exists(normalized)) return
            require(normalized.toRealPath().parent == realRoot)
            Files.walkFileTree(normalized, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }
}


