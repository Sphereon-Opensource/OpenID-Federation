package com.sphereon.openid.fed.account

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.di.context.IdentityConstants
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.BearerTokenSupport
import com.sphereon.openid.fed.core.tenant.FederationTenantProvisioner
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.core.tenant.TenantSource
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.Inject

/**
 * PLATFORM-mode [TenantContextResolver]: JWT tenant claims + IDK session tenant.
 *
 * ## Resolution order
 * 1. Tenant claim from `Authorization` Bearer payload (see [BearerTokenSupport]) —
 *    use only when JWT validation already ran (IDK JwtAuthentication / gateway).
 * 2. [SessionExecution.tenantId] from DI session (fixed/platform root bootstrap)
 *
 * Does **not** read `X-Account-Username` / `X-Tenant-Id`. Auto-provisions a federation
 * tenant row on first resolution so domain FKs work.
 */
@Inject
class SessionTenantContextResolver(
    private val execution: SessionExecution,
    private val configBinder: OidfConfigBinder,
    private val tenantProvisioner: FederationTenantProvisioner
) : TenantContextResolver {

    private val accountQueries = Persistence.accountQueries

    override suspend fun resolveTenantId(request: GenericHttpRequest): String? {
        // Prefer tenant from Bearer claims (after JWT validation gate)
        val authHeader = request.headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
            ?: request.headers["Authorization"]
            ?: request.headers["authorization"]
        val fromJwt = BearerTokenSupport.platformTenantFromAuthorizationHeader(authHeader)
        if (fromJwt != null && !fromJwt.isAnonymousTenant()) {
            tenantProvisioner.ensureTenantExists(fromJwt, TenantSource.IDK)
            return fromJwt
        }

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
