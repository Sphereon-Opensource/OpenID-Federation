package com.sphereon.openid.fed.core.tenant

/**
 * How OpenID Federation resolves multi-entity (tenant) isolation and identity.
 *
 * ## ACCOUNT (default open-source standalone)
 * Federation entity context is an [Account] row. Management REST (`/accounts`) enabled.
 * Authn is always Bearer. Entity selection via `X-Account-Username` is an **account-mode**
 * privilege controlled by [IdentityConfig.accountHeaderAllowedPrincipals] (who may switch),
 * not by hard-coded AS `sub=root`.
 *
 * ## EXTERNAL
 * Host embeddings with IDK. Session tenant comes from the **validated access token only**.
 * No account-header identity, no root-account login. Impersonation/delegation is entirely
 * the AS (token claims). No account/user/party management APIs in OIDFed.
 */
enum class IdentityMode {
    ACCOUNT,
    EXTERNAL;

    companion object {
        /**
         * Parse config / env value.
         *
         * Canonical: `account`, `external`.
         * Aliases (compat): `legacy`/`accounts` → ACCOUNT; `platform`/`idk`/`session` → EXTERNAL.
         */
        fun parse(value: String?): IdentityMode {
            if (value.isNullOrBlank()) return ACCOUNT
            return when (value.trim().lowercase()) {
                "account", "accounts", "legacy" -> ACCOUNT
                "external", "platform", "idk", "session" -> EXTERNAL
                else -> ACCOUNT
            }
        }
    }
}

/**
 * Values stored in Account.tenant_source for how a federation tenant row was created.
 */
object TenantSource {
    /** Created via account management APIs. */
    const val ACCOUNT = "account"

    /** Auto-provisioned from external/IDK session tenant. */
    const val IDK = "idk"

    /** Explicit ensure/provision without account UX. */
    const val PROVISIONED = "provisioned"
}

/**
 * How the IDK **session** tenant id is chosen relative to federation entity isolation.
 *
 * ## L1 (`FIXED`)
 * Session always uses a fixed id (typically `"default"`).
 *
 * ## L2 (`ACCOUNT`) — default
 * ACCOUNT mode: session tenant aligned with selected Account.id.
 * EXTERNAL mode: session tenant from JWT claims (this flag does not enable headers).
 */
enum class SessionAlignment {
    ACCOUNT,
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
 * @property mode [IdentityMode.ACCOUNT] or [IdentityMode.EXTERNAL]
 * @property externalRootTenantId Optional token tenant id that **owns the federation root
 *   entity URL** (not a login root; not a session fallback). Config key also accepts
 *   legacy `oidf.identity.platform.root.tenant.id`.
 * @property sessionAlignment L1 fixed vs L2 account-aligned session tenant
 * @property sessionFixedTenantId Fixed/bootstrap session tenant id (default `"default"`)
 * @property accountHeaderPrincipalClaim JWT claim name used to match header-switch allow-list
 *   (default `sub`). ACCOUNT mode only.
 * @property accountHeaderAllowedPrincipals Values of that claim allowed to send
 *   `X-Account-Username`. **Empty by default (deny)** — no header rebind until configured.
 *   Explicit `*` allows any authenticated principal (ops/test only; never ship as production default).
 *   ACCOUNT mode only; EXTERNAL never honors the header.
 */
data class IdentityConfig(
    val mode: IdentityMode = IdentityMode.ACCOUNT,
    val externalRootTenantId: String? = null,
    val sessionAlignment: SessionAlignment = SessionAlignment.ACCOUNT,
    val sessionFixedTenantId: String = "default",
    val accountHeaderPrincipalClaim: String = "sub",
    val accountHeaderAllowedPrincipals: List<String> = emptyList(),
) {
    val isAccount: Boolean get() = mode == IdentityMode.ACCOUNT
    val isExternal: Boolean get() = mode == IdentityMode.EXTERNAL

    /** True when ACCOUNT mode should bind IDK session to Account.id (L2). */
    val isSessionAccountAligned: Boolean
        get() = sessionAlignment == SessionAlignment.ACCOUNT

    /**
     * Whether [accountHeaderAllowedPrincipals] contains an explicit `*` wildcard.
     * Empty list means **nobody** may use the entity header (fail closed).
     */
    val accountHeaderAllowsAnyAuthenticated: Boolean
        get() = accountHeaderAllowedPrincipals.any { it.trim() == "*" }
}
