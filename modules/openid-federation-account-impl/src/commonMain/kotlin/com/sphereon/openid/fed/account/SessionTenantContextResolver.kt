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
 * PLATFORM-mode [TenantContextResolver]: IDK session tenant (post-JWT rebind).
 *
 * ## Resolution
 * Uses [SessionExecution.tenantId] only — the single source of truth after
 * admin/federation servers rebind the kotlin-inject session to validated JWT
 * tenant claims ([OidfJwtSessionRebindPlugin] / stamp plugin).
 *
 * Does **not** re-decode the Authorization Bearer payload. Unverified claim
 * parsing was removed so business isolation cannot diverge from DI session
 * (KMS/config/cache). PLATFORM multi-tenant requires JWT auth + rebind (or a
 * host that sets session tenant correctly before commands run).
 *
 * Does **not** read `X-Account-Username` / `X-Tenant-Id`. Auto-provisions a
 * federation tenant row on first resolution so domain FKs work.
 */
@Inject
class SessionTenantContextResolver(
    private val execution: SessionExecution,
    private val configBinder: OidfConfigBinder,
    private val tenantProvisioner: FederationTenantProvisioner
) : TenantContextResolver {

    private val accountQueries = Persistence.accountQueries

    override suspend fun resolveTenantId(request: GenericHttpRequest): String? {
        // Single source: DI session tenant (aligned to JWT after post-auth rebind)
        val tenantId = execution.tenantId.takeUnless { it.isAnonymousTenant() } ?: return null
        tenantProvisioner.ensureTenantExists(tenantId, TenantSource.IDK)
        return tenantId
    }

    override suspend fun resolveTenantIdByName(name: String): String? {
        // In platform mode, "name" is expected to be the tenant id itself (or configured alias later).
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

        // Tenant-scoped config override
        configBinder.getTenantConfig(tenantId)?.rootIdentifier?.let { return it }

        val federation = configBinder.getFederationConfig()
        val identity = configBinder.getIdentityConfig()
        val rootTenantId = identity.platformRootTenantId

        // Configured platform root tenant → federation root identifier
        if (rootTenantId != null && rootTenantId == tenantId) {
            return federation.rootIdentifier
        }

        // Single-tenant embeds often use FixedTenantResolver("default")
        if (rootTenantId == null && tenantId == "default") {
            return federation.rootIdentifier
        }

        // Other platform tenants get a deterministic entity URL under the root
        return "${federation.rootIdentifier.trimEnd('/')}/tenants/$tenantId"
    }

    private fun String.isAnonymousTenant(): Boolean =
        this == IdentityConstants.ANONYMOUS_TENANT_ID || this.equals("anonymous", ignoreCase = true)
}
