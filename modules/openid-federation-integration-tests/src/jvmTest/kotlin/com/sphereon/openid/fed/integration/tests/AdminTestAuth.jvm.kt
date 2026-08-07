package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.integration.tests.platform.AccountInProcessFixture

/**
 * JVM: prefer env overrides for external servers + pre-minted token; otherwise boot
 * in-process IDK AS + ACCOUNT admin + federation public server (admin always JWT-authenticated).
 */
actual fun adminTestBaseUrl(): String =
    System.getenv("ADMIN_SERVER_BASE_URL")
        ?.takeIf { it.isNotBlank() }
        ?: AccountInProcessFixture.shared.adminBaseUrl

actual fun federationTestBaseUrl(): String =
    System.getenv("FEDERATION_SERVER_BASE_URL")
        ?.takeIf { it.isNotBlank() }
        ?: AccountInProcessFixture.shared.federationBaseUrl

actual fun adminTestBearerToken(): String =
    System.getenv("ADMIN_BEARER_TOKEN")
        ?.takeIf { it.isNotBlank() }
        ?: AccountInProcessFixture.shared.defaultAccessToken()
