package com.sphereon.openid.fed.account

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.tenant.FederationTenantProvisioner
import com.sphereon.openid.fed.core.tenant.TenantSource
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * SQLDelight-backed [FederationTenantProvisioner].
 *
 * Uses Account.ensureTenantExists so PLATFORM mode can key federation domain
 * tables by IDK tenant id without going through `/accounts` CRUD.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FederationTenantProvisioner>())
class FederationTenantProvisionerImpl : FederationTenantProvisioner {

    private val accountQueries = Persistence.accountQueries

    override suspend fun ensureTenantExists(
        tenantId: String,
        source: String,
        username: String
    ): Boolean {
        if (tenantId.isBlank()) return false
        return try {
            // Account.username has a partial unique index (active rows). Empty username
            // can only exist once — PLATFORM tenants must get a stable unique placeholder
            // (legacy CRUD still passes real usernames).
            val resolvedUsername =
                username.ifBlank { "idk:$tenantId" }.take(255)
            accountQueries.ensureTenantExists(
                id = tenantId,
                username = resolvedUsername,
                tenant_source = source.ifBlank { TenantSource.IDK }
            )
            tenantExists(tenantId)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun tenantExists(tenantId: String): Boolean {
        if (tenantId.isBlank()) return false
        return try {
            accountQueries.findTenantById(tenantId).executeAsOneOrNull() != null
        } catch (_: Exception) {
            false
        }
    }
}
