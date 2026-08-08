package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.ResolveResponse

data class ResolveEntityArgs(
    val tenantId: String,
    val sub: String,
    /** Trust Anchors in preference order (OIDFed 1.1 §8.3 — parameter may be repeated). */
    val trustAnchors: Array<String>,
    val entityTypes: Array<String>?
) {
    /** Single-TA convenience for callers. */
    constructor(
        tenantId: String,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?,
    ) : this(tenantId, sub, arrayOf(trustAnchor), entityTypes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ResolveEntityArgs
        if (tenantId != other.tenantId) return false
        if (sub != other.sub) return false
        if (!trustAnchors.contentEquals(other.trustAnchors)) return false
        if (entityTypes != null) {
            if (other.entityTypes == null) return false
            if (!entityTypes.contentEquals(other.entityTypes)) return false
        } else if (other.entityTypes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tenantId.hashCode()
        result = 31 * result + sub.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + (entityTypes?.contentHashCode() ?: 0)
        return result
    }
}

interface ResolveEntityCommand : ServiceCommand<ResolveEntityArgs, ResolveResponse, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.resolution.resolve-entity"
    }
}
