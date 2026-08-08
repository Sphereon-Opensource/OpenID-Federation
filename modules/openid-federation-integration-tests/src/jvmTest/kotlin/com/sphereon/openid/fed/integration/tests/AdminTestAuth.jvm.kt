package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.common.config.getEnvironmentVariable
import com.sphereon.openid.fed.integration.tests.platform.AccountInProcessFixture

/**
 * JVM: prefer env overrides for external servers + pre-minted token; otherwise boot
 * in-process IDK AS + ACCOUNT admin + federation public server (admin always JWT-authenticated).
 *
 * Test harness URLs/tokens are not product config keys; they still go through
 * [getEnvironmentVariable] (IDK Env + [OidfEnvOverrides]) rather than System.getenv.
 */
actual fun adminTestBaseUrl(): String =
    getEnvironmentVariable("ADMIN_SERVER_BASE_URL")
        ?.takeIf { it.isNotBlank() }
        ?: AccountInProcessFixture.shared.adminBaseUrl

actual fun federationTestBaseUrl(): String =
    getEnvironmentVariable("FEDERATION_SERVER_BASE_URL")
        ?.takeIf { it.isNotBlank() }
        ?: AccountInProcessFixture.shared.federationBaseUrl

actual fun adminTestBearerToken(): String =
    getEnvironmentVariable("ADMIN_BEARER_TOKEN")
        ?.takeIf { it.isNotBlank() }
        ?: AccountInProcessFixture.shared.defaultAccessToken()
