package com.sphereon.openid.fed.server.admin.ktor

import com.sphereon.openid.fed.core.config.OidfConfigDefaults
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The Docker packaging has to agree with the ports the servers listen on: the health check of an image, the port the
 * compose file maps and the port the reverse proxy label routes to must all be the port the process binds. The earlier
 * Spring images listened on 8080 and the Ktor admin server defaults to 8081, so the admin image sets the port itself.
 */
class DockerImagePortContractTest {
    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.exists(it.resolve(".docker/openid-federation-server/Dockerfile")) }
        ?: fail("The checkout with the .docker directory was not found above ${Path.of("").toAbsolutePath()}")

    private fun read(relative: String): String = Files.readString(root.resolve(relative))

    /** The port a server binds in its image: the ENV of the Dockerfile when it sets one, otherwise the packaged default. */
    private fun boundPort(dockerfile: String, envName: String, key: String): Int =
        Regex("""^ENV\s+$envName[= ](\d+)""", RegexOption.MULTILINE).find(dockerfile)?.groupValues?.get(1)?.toInt()
            ?: OidfConfigDefaults.appProperties.getValue(key).toInt()

    private fun healthCheck(dockerfile: String): Pair<Int, String> {
        val match = assertNotNull(Regex("""HEALTHCHECK.*localhost:(\d+)(/\S*)""").find(dockerfile), "The Dockerfile has no HEALTHCHECK on localhost")
        return match.groupValues[1].toInt() to match.groupValues[2]
    }

    @Test
    fun shouldHealthCheckTheAdminImageOnThePortItBinds() {
        val dockerfile = read(".docker/openid-federation-admin-server/Dockerfile")
        val (port, path) = healthCheck(dockerfile)
        assertEquals(boundPort(dockerfile, "OIDF_SERVER_ADMIN_PORT", OidfConfigKeys.Server.Admin.PORT), port, "the admin image checks a port it does not listen on")
        assertEquals("/health", path)
    }

    @Test
    fun shouldHealthCheckThePublicImageOnThePortItBindsAndNotOnStoredData() {
        val dockerfile = read(".docker/openid-federation-server/Dockerfile")
        val (port, path) = healthCheck(dockerfile)
        assertEquals(boundPort(dockerfile, "OIDF_SERVER_FEDERATION_PORT", OidfConfigKeys.Server.Federation.PORT), port, "the public image checks a port it does not listen on")
        assertEquals("/health", path, "the entity configuration answers 404 until the operator publishes one, so it cannot be the health check")
    }

    @Test
    fun shouldMapTheDevComposeAdminPortToThePortTheImageBinds() {
        val bound = boundPort(read(".docker/openid-federation-admin-server/Dockerfile"), "OIDF_SERVER_ADMIN_PORT", OidfConfigKeys.Server.Admin.PORT)
        val admin = read("docker-compose.yaml").substringAfter("openid-federation-admin-server:").substringBefore("\n  keycloak:\n")
        assertEquals(bound, Regex("""-\s*"\d+:(\d+)"""").find(admin)?.groupValues?.get(1)?.toInt(), "the compose port mapping targets another port than the admin image binds")
    }

    @Test
    fun shouldRouteTheProductionProxyToThePortsTheImagesBind() {
        val compose = read(".docker/prod-deployment/docker-compose.yaml")
        val ports = Regex("""services\.([a-z-]+)\.loadbalancer\.server\.port=(\d+)""").findAll(compose).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(boundPort(read(".docker/openid-federation-admin-server/Dockerfile"), "OIDF_SERVER_ADMIN_PORT", OidfConfigKeys.Server.Admin.PORT), ports["openid-federation-admin-server"])
        assertEquals(boundPort(read(".docker/openid-federation-server/Dockerfile"), "OIDF_SERVER_FEDERATION_PORT", OidfConfigKeys.Server.Federation.PORT), ports["openid-federation-server"])
    }
}
