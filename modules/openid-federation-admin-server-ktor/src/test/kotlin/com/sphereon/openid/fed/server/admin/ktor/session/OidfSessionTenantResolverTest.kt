package com.sphereon.openid.fed.server.admin.ktor.session

import com.sphereon.core.defaults.context.DefaultTenantInputString
import com.sphereon.core.defaults.context.JwtClaimsInput
import com.sphereon.core.defaults.context.markValidated
import com.sphereon.di.context.TenantInputString
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.openid.fed.core.config.CorsConfig
import com.sphereon.openid.fed.core.config.DatasourceConfig
import com.sphereon.openid.fed.core.config.FederationConfig
import com.sphereon.openid.fed.core.config.KmsConfig
import com.sphereon.openid.fed.core.config.LoggerConfig
import com.sphereon.openid.fed.core.config.OAuth2Config
import com.sphereon.openid.fed.core.config.OidfAppConfig
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.ServerConfig
import com.sphereon.openid.fed.core.config.TenantConfig
import com.sphereon.openid.fed.core.tenant.IdentityConfig
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.SessionAlignment
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [OidfSessionTenantResolver] L1/L2/PLATFORM branches.
 *
 * LEGACY L2 Account DB lookup is covered by HTTP integration tests
 * (`SessionAlignmentApiTest`) against a live admin server.
 */
class OidfSessionTenantResolverTest {

    private class FakeBinder(private val identity: IdentityConfig) : OidfConfigBinder {
        override fun getFederationConfig() = FederationConfig()
        override fun getServerConfig(type: OidfConfigBinder.ServerType) = ServerConfig(port = 8081)
        override fun getCorsConfig() = CorsConfig()
        override fun getLoggerConfig() = LoggerConfig()
        override fun getDatasourceConfig() = DatasourceConfig()
        override fun getOAuth2Config() = OAuth2Config()
        override fun getKmsConfig() = KmsConfig()
        override fun getIdentityConfig() = identity
        override fun getAppConfig() = OidfAppConfig(identity = identity)
        override fun getTenantConfig(tenantId: String): TenantConfig? = null
        override fun getProperty(key: String, default: String) = default
        override fun getBooleanProperty(key: String, default: Boolean) = default
        override fun getIntProperty(key: String, default: Int) = default
        override fun getLongProperty(key: String, default: Long) = default
        override fun getListProperty(key: String, default: List<String>) = default
    }

    @Test
    fun platform_mode_resolves_configured_platform_root_tenant() = testApplication {
        val resolver = OidfSessionTenantResolver(
            FakeBinder(
                IdentityConfig(
                    mode = IdentityMode.PLATFORM,
                    platformRootTenantId = "platform-root",
                    sessionAlignment = SessionAlignment.ACCOUNT,
                    sessionFixedTenantId = "default",
                ),
            ),
        )
        application {
            routing {
                get("/probe") {
                    val input = resolver.resolve(call)
                    call.respondText((input as TenantInputString).tenant)
                }
            }
        }
        val response = client.get("/probe")
        assertEquals("platform-root", response.bodyAsText())
    }

    @Test
    fun fixed_alignment_ignores_account_header_and_uses_fixed_id() = testApplication {
        val resolver = OidfSessionTenantResolver(
            FakeBinder(
                IdentityConfig(
                    mode = IdentityMode.LEGACY,
                    sessionAlignment = SessionAlignment.FIXED,
                    sessionFixedTenantId = "locked-tenant",
                ),
            ),
        )
        application {
            routing {
                get("/probe") {
                    val input = resolver.resolve(call)
                    call.respondText((input as TenantInputString).tenant)
                }
            }
        }
        val response = client.get("/probe") {
            header("X-Account-Username", "some-other-account")
        }
        assertEquals("locked-tenant", response.bodyAsText())
    }

    @Test
    fun platform_without_root_falls_back_to_fixed_tenant_id() = testApplication {
        val resolver = OidfSessionTenantResolver(
            FakeBinder(
                IdentityConfig(
                    mode = IdentityMode.PLATFORM,
                    platformRootTenantId = null,
                    sessionFixedTenantId = "default",
                ),
            ),
        )
        application {
            routing {
                get("/probe") {
                    val input = resolver.resolve(call)
                    call.respondText((input as TenantInputString).tenant)
                }
            }
        }
        assertEquals("default", client.get("/probe").bodyAsText())
    }

    @Test
    fun platform_uses_validated_jwt_tenant_claim_over_config_root() = testApplication {
        val resolver = OidfSessionTenantResolver(
            FakeBinder(
                IdentityConfig(
                    mode = IdentityMode.PLATFORM,
                    platformRootTenantId = "config-root",
                    sessionAlignment = SessionAlignment.ACCOUNT,
                ),
            ),
        )
        application {
            routing {
                get("/probe") {
                    // Simulate host jwt-auth plugin stamping validated claims
                    call.attributes.put(
                        ValidatedJwtClaimsAttribute,
                        JwtClaimsInput(
                            claims = mapOf("tenant_id" to JsonPrimitive("jwt-tenant-42")),
                            rawToken = "header.payload.sig",
                        ).markValidated(),
                    )
                    val input = resolver.resolve(call)
                    call.respondText((input as TenantInputString).tenant)
                }
            }
        }
        assertEquals("jwt-tenant-42", client.get("/probe").bodyAsText())
    }

    @Test
    fun platform_ignores_account_username_header() = testApplication {
        val resolver = OidfSessionTenantResolver(
            FakeBinder(
                IdentityConfig(
                    mode = IdentityMode.PLATFORM,
                    platformRootTenantId = "platform-only",
                ),
            ),
        )
        application {
            routing {
                get("/probe") {
                    val input = resolver.resolve(call)
                    call.respondText((input as TenantInputString).tenant)
                }
            }
        }
        val response = client.get("/probe") {
            header("X-Account-Username", "should-not-apply")
        }
        assertEquals("platform-only", response.bodyAsText())
    }

    @Test
    fun default_tenant_input_string_wraps_id() {
        val input = DefaultTenantInputString("acct-uuid-1")
        assertTrue(input is TenantInputString)
        assertEquals("acct-uuid-1", input.tenant)
        assertFalse(IdentityConfig(sessionAlignment = SessionAlignment.FIXED).isSessionAccountAligned)
    }
}
