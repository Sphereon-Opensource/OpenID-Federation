package com.sphereon.openid.fed.account

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.di.context.IdentityConstants
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.FederationTenantProvisioner
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.core.tenant.TenantSource
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.Inject

/**
 * EXTERNAL-mode [TenantContextResolver]: IDK session tenant from the access token.
 *
 * ## Resolution
 * Uses [SessionExecution.tenantId] only — set from validated JWT tenant claims at
 * session open (admin pre-session stamp + EXTERNAL fail-closed without tenant claim).
 *
 * Does **not** re-decode the Authorization Bearer payload or read identity headers.
 * Auto-provisions a federation tenant row on first resolution so domain FKs work.
 */
@Inject
class SessionTenantContextResolver(
    private val execution: SessionExecution,
    private val configBinder: OidfConfigBinder,
    private val tenantProvisioner: FederationTenantProvisioner
) : TenantContextResolver {

    private val accountQueries = Persistence.accountQueries

    override suspend fun resolveTenantId(request: GenericHttpRequest): String? {
        // Single source: DI session tenant (JWT claims at open)
        val tenantId = execution.tenantId.takeUnless { it.isAnonymousTenant() } ?: return null
        tenantProvisioner.ensureTenantExists(tenantId, TenantSource.IDK)
        return tenantId
    }

    override suspend fun resolveTenantIdByName(name: String): String? {
        // EXTERNAL: "name" is typically the tenant id itself (or a configured alias later).
        if (name.isBlank() || name.isAnonymousTenant()) return null
        if (tenantProvisioner.tenantExists(name) ||
            tenantProvisioner.ensureTenantExists(name, TenantSource.IDK)
        ) {
            return name
        }
        return null
    }

    override suspend fun resolveIdentifier(tenantId: String): String? {
        if (tenantId.isBlank() || tenantId.isAnonymousTenant()) return null

        // Explicit identifier stored on the tenant/account row
        try {
            val account = accountQueries.findById(tenantId).executeAsOneOrNull()
            account?.identifier?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
            // fall through
        }

        // Tenant-overridable root (oidf.tenant.<id>.* / effective federation config)
        val federation = configBinder.getEffectiveFederationConfig(tenantId)
        val explicitRoot = configBinder.getTenantConfig(tenantId)?.rootIdentifier
        if (!explicitRoot.isNullOrBlank()) return explicitRoot

        val identity = configBinder.getIdentityConfig()
        // Entity-URL mapping only — not session/login identity. APP-fixed.
        val rootEntityOwnerTenantId = identity.externalRootTenantId

        // Token tenant that owns the federation root entity identifier URL
        if (rootEntityOwnerTenantId != null && rootEntityOwnerTenantId == tenantId) {
            return federation.rootIdentifier
        }

        // Compat: single-tenant embeds using fixed "default" without explicit mapping
        if (rootEntityOwnerTenantId == null && tenantId == "default") {
            return federation.rootIdentifier
        }

        // Other platform tenants get a deterministic entity URL under the root
        return "${federation.rootIdentifier.trimEnd('/')}/tenants/$tenantId"
    }

    private fun String.isAnonymousTenant(): Boolean =
        this == IdentityConstants.ANONYMOUS_TENANT_ID || this.equals("anonymous", ignoreCase = true)
}
