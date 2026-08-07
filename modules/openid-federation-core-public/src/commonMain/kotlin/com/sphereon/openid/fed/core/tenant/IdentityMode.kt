package com.sphereon.openid.fed.core.tenant

/**
 * How OpenID Federation resolves multi-entity (tenant) isolation and identity.
 *
 * ## LEGACY
 * Standalone open-source deployments. Federation entity context is an [Account]
 * row selected via `X-Account-Username` (default `root`). Account management REST
 * (`/accounts`) is enabled. Does **not** depend on IDK party/tenant stores.
 *
 * ## PLATFORM
 * Embeddings next to IDK (and optionally EDK/VDX). Federation entity context is
 * the runtime IDK session tenant (`SessionExecution.tenantId` / JWT-resolved).
 * No account/user/party management APIs in OIDFed — the host owns those.
 * Federation rows are auto-provisioned under the session tenant id.
 */
enum class IdentityMode {
    LEGACY,
    PLATFORM;

    companion object {
        fun parse(value: String?): IdentityMode {
            if (value.isNullOrBlank()) return LEGACY
            return when (value.trim().lowercase()) {
                "legacy", "account", "accounts" -> LEGACY
                "platform", "idk", "session" -> PLATFORM
                else -> LEGACY
            }
        }
    }
}

/**
 * Values stored in Account.tenant_source for how a federation tenant row was created.
 */
object TenantSource {
    /** Created via legacy Account management APIs. */
    const val ACCOUNT = "account"

    /** Auto-provisioned from IDK/platform session tenant. */
    const val IDK = "idk"

    /** Explicit ensure/provision without account UX. */
    const val PROVISIONED = "provisioned"
}

/**
 * How the IDK **session** tenant id is chosen relative to federation entity isolation.
 *
 * ## L1 (`FIXED`)
 * Session always uses a fixed id (typically `"default"`). Business isolation may still
 * use Account.id via [TenantContextResolver] — KMS/config scopes stay on the fixed id.
 *
 * ## L2 (`ACCOUNT`) — default
 * LEGACY: session tenant = Account.id for `X-Account-Username` (same entity key as domain FKs).
 * PLATFORM: session tenant = [IdentityConfig.platformRootTenantId] or fixed fallback
 * (host JWT can replace the resolver entirely).
 */
enum class SessionAlignment {
    /** L2: align session with Account.id in LEGACY */
    ACCOUNT,

    /** L1: fixed session tenant only */
    FIXED;

    companion object {
        fun parse(value: String?): SessionAlignment {
            if (value.isNullOrBlank()) return ACCOUNT
            return when (value.trim().lowercase()) {
                "account", "l2", "aligned" -> ACCOUNT
                "fixed", "l1", "default" -> FIXED
                else -> ACCOUNT
            }
        }
    }
}

/**
 * Identity / multi-tenancy configuration (APP scope).
 *
 * @property mode Active identity mode (default LEGACY for open-source standalone).
 * @property platformRootTenantId Optional IDK tenant id that maps to the federation
 *   root entity identifier ([com.sphereon.openid.fed.core.config.FederationConfig.rootIdentifier]).
 *   When null in PLATFORM mode, the session tenant uses tenant-scoped identifier config
 *   or falls back to rootIdentifier only when the tenant is considered the app root.
 * @property allowAnonymousAdmin When false (default), PLATFORM mode refuses admin
 *   mutations if the IDK session is anonymous. LEGACY mode ignores this flag today
 *   for backwards compatibility (header-selected accounts).
 * @property sessionAlignment L1 fixed vs L2 account-aligned IDK session tenant (see [SessionAlignment]).
 * @property sessionFixedTenantId Fixed/fallback session tenant id (default `"default"`).
 */
data class IdentityConfig(
    val mode: IdentityMode = IdentityMode.LEGACY,
    val platformRootTenantId: String? = null,
    val allowAnonymousAdmin: Boolean = false,
    val sessionAlignment: SessionAlignment = SessionAlignment.ACCOUNT,
    val sessionFixedTenantId: String = "default",
) {
    val isLegacy: Boolean get() = mode == IdentityMode.LEGACY
    val isPlatform: Boolean get() = mode == IdentityMode.PLATFORM
    /** True when LEGACY should bind IDK session to Account.id (L2). */
    val isSessionAccountAligned: Boolean
        get() = sessionAlignment == SessionAlignment.ACCOUNT
}
